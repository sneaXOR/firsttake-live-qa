package dev.firsttake.probe

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.json.JSONObject

class SparseLumaAnalyzer(
    private val context: Context,
    private val gate: AnalyzerGate,
    private val telemetry: () -> ProbeTelemetryWriter?,
    private val cameraClockContract: () -> CameraClockContract,
    private val motionEvidence: () -> MotionEvidence?,
    private val thermalObservation: () -> Pair<ThermalStatus, Boolean>,
    private val onCameraAnalysisFrame: (CameraAnalysisFrameSample) -> Unit,
    private val onHandStatus: (HandBaselineStatus) -> Unit,
    private val onQualityTransition: (QualityTransition) -> Unit,
    private val onHandEdgeTransition: (HandEdgeTransition) -> Unit,
    private val onBudgetTransition: (AnalyzerBudgetTransition) -> Unit,
) : ImageAnalysis.Analyzer {
    private val sessionState = AnalysisSessionState()
    private var workerGeneration = 0L
    private var qualityMonitor = FrameQualityMonitor()
    private var handVisibilityMonitor = HandVisibilityMonitor()
    private var budgetController = AnalyzerBudgetController(gate)
    private var handCadenceGate = CadenceGate()
    private var previousSignature: DoubleArray? = null
    private var lastHandInferenceNs = 0L
    private var handBaseline: OnDeviceHandBaseline? = null
    private var handBaselineInitializationAttempted = false
    private var handBaselineInitializationError: String? = null
    private var previewPrewarmComplete = false

    override fun analyze(image: ImageProxy) {
        val generation = sessionState.activeGeneration()
        if (generation == 0L) {
            // Load model assets once on the analyzer thread, but do not allow
            // preview observations to affect recording evidence or budgets.
            if (!previewPrewarmComplete) {
                analyzeHands(image)
                previewPrewarmComplete = true
            }
            image.close()
            return
        }
        if (workerGeneration != generation) {
            resetTemporalState(generation)
        }
        val acceptedAtNs = SystemClock.elapsedRealtimeNanos()
        if (!gate.shouldAnalyze(acceptedAtNs)) {
            image.close()
            return
        }
        try {
            val profileUsed = gate.currentProfile()
            val startedNs = SystemClock.elapsedRealtimeNanos()
            val plane = image.planes.firstOrNull()
            if (plane == null) {
                return
            }
            val buffer = plane.buffer.duplicate()
            val metrics = LumaMetricsComputer.compute(
                buffer = buffer,
                width = image.width,
                height = image.height,
                rowStride = plane.rowStride,
                pixelStride = plane.pixelStride,
            )
            val frameDelta = LumaMetricsComputer.meanAbsoluteFrameDelta(
                previous = previousSignature,
                current = metrics.signature,
            )
            previousSignature = metrics.signature
            val clockContract = cameraClockContract()
            onCameraAnalysisFrame(
                CameraAnalysisFrameSample(
                    sensorTimestampNs = image.imageInfo.timestamp,
                    acceptedAtElapsedRealtimeNs = acceptedAtNs,
                    cameraTimestampComparableToElapsedRealtime =
                        clockContract.comparableToElapsedRealtime,
                    profile = profileUsed,
                    width = image.width,
                    height = image.height,
                ),
            )
            val motion = motionEvidence()
            val evaluation = qualityMonitor.ingest(
                FrameQualityObservation(
                    observedAtElapsedRealtimeNs = acceptedAtNs,
                    metrics = metrics,
                    previousFrameDelta = frameDelta,
                    motion = motion,
                    cameraTimestampComparableToElapsedRealtime =
                        clockContract.comparableToElapsedRealtime,
                ),
            )
            val assessmentsJson = JSONObject()
            evaluation.assessments.forEach { (defect, assessment) ->
                assessmentsJson.put(defect.name, assessment.name)
            }
            val deterministicFinishedNs = SystemClock.elapsedRealtimeNanos()
            val runHandInference = handCadenceGate.shouldRun(
                acceptedAtNs,
                profileUsed.handSampleHz,
            )
            val handObservation = if (runHandInference) {
                analyzeHands(image).also {
                    lastHandInferenceNs = it.inferenceNs
                }
            } else {
                null
            }
            val handEvaluation = handObservation?.let {
                onHandStatus(it.status)
                handVisibilityMonitor.ingest(
                    observedAtElapsedRealtimeNs = acceptedAtNs,
                    status = it.status,
                )
            }
            val finishedNs = SystemClock.elapsedRealtimeNanos()
            val analysisNs = finishedNs - startedNs
            val deterministicNs = deterministicFinishedNs - startedNs
            val estimatedDutyCycle = (
                deterministicNs.toDouble() * profileUsed.sampleHz +
                    lastHandInferenceNs.toDouble() *
                    profileUsed.handSampleHz
                ) / 1_000_000_000.0
            val (thermalStatus, thermalSignalAvailable) = thermalObservation()
            val budgetTransition = budgetController.observe(
                analysisNs = analysisNs,
                thermalStatus = thermalStatus,
                thermalSignalAvailable = thermalSignalAvailable,
                estimatedDutyCycle = estimatedDutyCycle,
            )
            val payload = JSONObject()
                .put("schemaVersion", "firsttake.probe.telemetry.v1")
                .put("type", "ANALYSIS_SAMPLE")
                .put("sensorTimestampNs", image.imageInfo.timestamp)
                .put(
                    "cameraTimestampSource",
                    clockContract.timestampSourceName,
                )
                .put(
                    "cameraTimestampComparableToElapsedRealtime",
                    clockContract.comparableToElapsedRealtime,
                )
                .put("acceptedAtNs", acceptedAtNs)
                .put("analysisNs", analysisNs)
                .put("deterministicAnalysisNs", deterministicNs)
                .put("estimatedDutyCycle", estimatedDutyCycle)
                .put("profileUsed", profileUsed.name)
                .put("profileAfter", gate.currentProfile().name)
                .put("thermalStatus", thermalStatus.name)
                .put("thermalSignalAvailable", thermalSignalAvailable)
                .put("width", image.width)
                .put("height", image.height)
                .put("lumaMean", metrics.mean)
                .put("lumaStdDev", metrics.standardDeviation)
                .put("darkFraction", metrics.darkFraction)
                .put("brightFraction", metrics.brightFraction)
                .put("meanGradient", metrics.meanGradient)
                .put("laplacianVariance", metrics.laplacianVariance)
                .put("previousFrameDelta", frameDelta)
                .put(
                    "gyroMagnitudeRadPerSec",
                    motion?.gyroscopeMagnitudeRadPerSec,
                )
                .put("shakeRmsRadPerSec", motion?.shakeRmsRadPerSec)
                .put(
                    "cameraTiltFromGravityDegrees",
                    motion?.cameraTiltFromGravityDegrees,
                )
                .put(
                    "motionWindowDurationNs",
                    motion?.motionWindowDurationNs,
                )
                .put("qualityAssessments", assessmentsJson)
                .put("handInferenceExecuted", runHandInference)
                .put(
                    "handBaselineStatus",
                    handObservation?.status?.name,
                )
                .put(
                    "handEdgeAssessment",
                    handEvaluation?.assessment?.name,
                )
                .put(
                    "handGlobalDetected",
                    handObservation?.global?.detectedHands,
                )
                .put(
                    "handBottomDetected",
                    handObservation?.bottom?.detectedHands,
                )
                .put("handInferenceNs", handObservation?.inferenceNs)
                .put("handModelSha256", handObservation?.modelSha256)
                .put("handBaselineError", handObservation?.error)
            telemetry()?.offerJson(payload.toString())
            handEvaluation?.transition?.let { transition ->
                telemetry()?.offerJson(
                    JSONObject()
                        .put(
                            "schemaVersion",
                            "firsttake.probe.telemetry.v1",
                        )
                        .put("type", "HAND_EDGE_TRANSITION")
                        .put("kind", transition.kind.name)
                        .put("cause", transition.cause.name)
                        .put(
                            "elapsedRealtimeNs",
                            transition.observedAtElapsedRealtimeNs,
                        )
                        .put("reason", transition.reason)
                        .toString(),
                )
                onHandEdgeTransition(transition)
            }
            if (budgetTransition != null) {
                telemetry()?.offerJson(
                    JSONObject()
                        .put(
                            "schemaVersion",
                            "firsttake.probe.telemetry.v1",
                        )
                        .put("type", "ANALYSIS_PROFILE_DEGRADED")
                        .put(
                            "previousProfile",
                            budgetTransition.previousProfile.name,
                        )
                        .put(
                            "newProfile",
                            budgetTransition.newProfile.name,
                        )
                        .put(
                            "reasons",
                            budgetTransition.reasons.joinToString(","),
                        )
                        .put(
                            "observedP95Ms",
                            budgetTransition.observedP95Ms,
                        )
                        .put(
                            "samplesInWindow",
                            budgetTransition.samplesInWindow,
                        )
                        .put(
                            "thermalStatus",
                            budgetTransition.thermalStatus.name,
                        )
                        .put(
                            "thermalSignalAvailable",
                            budgetTransition.thermalSignalAvailable,
                        )
                        .toString(),
                )
                onBudgetTransition(budgetTransition)
            }
            evaluation.transitions.forEach { transition ->
                telemetry()?.offerJson(
                    JSONObject()
                        .put(
                            "schemaVersion",
                            "firsttake.probe.telemetry.v1",
                        )
                        .put("type", "QUALITY_TRANSITION")
                        .put("defect", transition.defect.name)
                        .put("kind", transition.kind.name)
                        .put(
                            "elapsedRealtimeNs",
                            transition.observedAtElapsedRealtimeNs,
                        )
                        .put("reason", transition.reason)
                        .toString(),
                )
                onQualityTransition(transition)
            }
        } finally {
            image.close()
        }
    }

    fun beginSession(): Long = sessionState.begin()

    fun endSession(generation: Long) {
        sessionState.end(generation)
    }

    fun close() {
        handBaseline?.close()
        handBaseline = null
    }

    private fun resetTemporalState(generation: Long) {
        qualityMonitor = FrameQualityMonitor()
        handVisibilityMonitor = HandVisibilityMonitor()
        budgetController = AnalyzerBudgetController(gate)
        handCadenceGate.reset()
        previousSignature = null
        lastHandInferenceNs = 0L
        workerGeneration = generation
    }

    private fun analyzeHands(image: ImageProxy): HandBaselineObservation {
        val baseline = handBaseline ?: initializeHandBaseline()
        if (baseline == null) {
            return HandBaselinePolicy.combine(
                global = null,
                bottom = null,
                inferenceNs = 0,
                error = handBaselineInitializationError
                    ?: "hand baseline unavailable",
            )
        }
        return baseline.analyze(image)
    }

    private fun initializeHandBaseline(): OnDeviceHandBaseline? {
        if (handBaselineInitializationAttempted) {
            return handBaseline
        }
        handBaselineInitializationAttempted = true
        return try {
            OnDeviceHandBaseline(context.applicationContext).also {
                handBaseline = it
            }
        } catch (error: Exception) {
            handBaselineInitializationError =
                error.message ?: error.javaClass.simpleName
            null
        }
    }
}
