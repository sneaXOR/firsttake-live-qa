package dev.firsttake.probe

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@SuppressLint("UnsafeOptInUsageError")
class CaptureCoordinator(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val initialAnalysisProfile: AnalysisProfile = AnalysisProfile.FULL,
    private val feedback: CaptureFeedback = NoopCaptureFeedback,
    private val onHandStatusChanged: (HandBaselineStatus) -> Unit = {},
    private val onStatus: (String) -> Unit,
) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val gate = AnalyzerGate(initialAnalysisProfile)
    private val feedbackState = OperatorFeedbackState()
    private val recentMotion = AtomicReference<MotionEvidence?>(null)
    private var sparseAnalyzer: SparseLumaAnalyzer? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var telemetry: ProbeTelemetryWriter? = null
    private var imuRecorder: ImuRecorder? = null
    private var imuStopFuture: CompletableFuture<ImuFinalizeReport>? = null
    private var sessionJournal: DurableSessionJournal? = null
    private var activeVideoFile: File? = null
    private var activeSessionDirectory: File? = null
    private var activeSessionId: String? = null
    private var activeSessionCreatedAtUnixMs: Long? = null
    private var activeSessionInitialAnalysisProfile: AnalysisProfile? = null
    private var recorderHealthMonitor: RecorderHealthMonitor? = null
    private var cameraClockContract = CameraClockContract.unavailable
    private var cameraSelection = CameraSelectionEvidence.unavailable
    private var lastJournalCheckpointDurationNs = 0L
    private var coordinatorClosed = false
    private var activeAnalysisGeneration: Long? = null
    private var recordingHasAudio = false
    @Volatile
    private var recordCameraCaptureResults = false
    private val cameraCaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                if (!recordCameraCaptureResults) {
                    return
                }
                val sensorTimestampNs =
                    result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                imuRecorder?.recordCameraCaptureResult(
                    CameraCaptureResultSample(
                        sensorTimestampNs = sensorTimestampNs,
                        receivedAtElapsedRealtimeNs =
                            SystemClock.elapsedRealtimeNanos(),
                        frameNumber = result.frameNumber,
                        sequenceId = result.sequenceId,
                        zoomRatio = result.get(
                            CaptureResult.CONTROL_ZOOM_RATIO,
                        )?.toDouble(),
                        activePhysicalCameraId = result.get(
                            CaptureResult
                                .LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID,
                        ),
                    ),
                )
            }
        }

    fun bindCamera(onReady: () -> Unit = {}) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.FHD,
                        ),
                    )
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                val imageAnalysisBuilder = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                Camera2Interop.Extender(imageAnalysisBuilder)
                    .setSessionCaptureCallback(cameraCaptureCallback)
                val imageAnalysis = imageAnalysisBuilder.build()
                val analyzer = SparseLumaAnalyzer(
                        context = context,
                        gate = gate,
                        telemetry = { telemetry },
                        cameraClockContract = { cameraClockContract },
                        motionEvidence = { recentMotion.get() },
                        thermalObservation = ::currentThermalObservation,
                        onCameraAnalysisFrame = { sample ->
                            imuRecorder?.recordCameraAnalysisFrame(sample)
                        },
                        onHandStatus = ::handleHandStatus,
                        onQualityTransition = ::handleQualityTransition,
                        onHandEdgeTransition = ::handleHandEdgeTransition,
                        onBudgetTransition = ::handleBudgetTransition,
                    )
                imageAnalysis.setAnalyzer(analysisExecutor, analyzer)
                sparseAnalyzer = analyzer
                val requestedSelection =
                    AndroidCameraSelectionResolver.resolve(
                        context,
                        provider.availableCameraInfos,
                    )
                provider.unbindAll()
                val camera = try {
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        requestedSelection.selector,
                        preview,
                        capture,
                        imageAnalysis,
                    ).also {
                        cameraSelection = requestedSelection.evidence
                    }
                } catch (physicalError: Exception) {
                    if (
                        requestedSelection.evidence.physicalCameraId == null
                    ) {
                        throw physicalError
                    }
                    Log.w(
                        LOG_TAG,
                        "PHYSICAL_CAMERA_FALLBACK " +
                            "requested=${requestedSelection.evidence.displayId}",
                        physicalError,
                    )
                    provider.unbindAll()
                    val fallback =
                        AndroidCameraSelectionResolver.logicalFallback(
                            requestedSelection.evidence.logicalCameraId,
                        )
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        fallback.selector,
                        preview,
                        capture,
                        imageAnalysis,
                    ).also {
                        cameraSelection = fallback.evidence
                    }
                }
                val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)
                cameraClockContract = CameraClockContract.from(
                    cameraId = camera2Info.cameraId,
                    rawTimestampSource = camera2Info.getCameraCharacteristic(
                        CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE,
                    ),
                )
                cameraProvider = provider
                boundCamera = camera
                videoCapture = capture
                applyWidestPublicZoom(camera) {
                    onStatus(
                        "Camera ready · ${gate.currentProfile().name} · " +
                            "clock ${cameraClockContract.timestampSourceName}",
                    )
                    onReady()
                }
            },
            mainExecutor,
        )
    }

    private fun applyWidestPublicZoom(
        camera: Camera,
        onComplete: () -> Unit,
    ) {
        val zoomState = camera.cameraInfo.zoomState.value
        val minimumZoomRatio = zoomState?.minZoomRatio?.toDouble()
        val requestedZoomRatio = (
            zoomState?.minZoomRatio ?: 1.0f
            ).coerceAtMost(1.0f)
        cameraSelection = cameraSelection.copy(
            minimumZoomRatio = minimumZoomRatio,
            requestedZoomRatio = requestedZoomRatio.toDouble(),
            appliedZoomRatio = null,
        )
        val future = camera.cameraControl.setZoomRatio(requestedZoomRatio)
        future.addListener(
            {
                val error = runCatching { future.get() }.exceptionOrNull()
                val appliedZoomRatio =
                    camera.cameraInfo.zoomState.value?.zoomRatio?.toDouble()
                cameraSelection = cameraSelection.copy(
                    appliedZoomRatio = appliedZoomRatio,
                )
                if (error == null) {
                    Log.i(
                        LOG_TAG,
                        "CAMERA_ZOOM_READY minimum=$minimumZoomRatio " +
                            "requested=$requestedZoomRatio " +
                            "applied=$appliedZoomRatio",
                    )
                } else {
                    Log.w(
                        LOG_TAG,
                        "CAMERA_ZOOM_APPLY_FAILED " +
                            "minimum=$minimumZoomRatio " +
                            "requested=$requestedZoomRatio " +
                            "observed=$appliedZoomRatio",
                        error,
                    )
                }
                onComplete()
            },
            mainExecutor,
        )
    }

    fun isRecording(): Boolean = recording != null

    fun setControlledExposureProbe(level: ControlledExposureLevel) {
        val camera = boundCamera ?: return
        val state = camera.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) {
            logControlledExposureProbe(
                level = level,
                requestedIndex = null,
                applied = false,
                error = "EXPOSURE_COMPENSATION_UNSUPPORTED",
            )
            return
        }
        val requestedIndex = when (level) {
            ControlledExposureLevel.MINIMUM ->
                state.exposureCompensationRange.lower
            ControlledExposureLevel.NOMINAL -> 0
            ControlledExposureLevel.MAXIMUM ->
                state.exposureCompensationRange.upper
        }
        val future = camera.cameraControl.setExposureCompensationIndex(
            requestedIndex,
        )
        future.addListener(
            {
                val result = runCatching { future.get() }
                logControlledExposureProbe(
                    level = level,
                    requestedIndex = requestedIndex,
                    applied = result.isSuccess,
                    appliedIndex = result.getOrNull(),
                    error = result.exceptionOrNull()?.message,
                )
            },
            mainExecutor,
        )
    }

    fun startRecording(withAudio: Boolean) {
        if (recording != null) {
            return
        }
        val (thermalStatus, thermalSignalAvailable) =
            currentThermalObservation()
        val startDecision = CaptureStartPolicy.decide(
            requestedProfile = initialAnalysisProfile,
            thermalStatus = thermalStatus,
            thermalSignalAvailable = thermalSignalAvailable,
        )
        if (!startDecision.allowed) {
            Log.w(
                LOG_TAG,
                "CAPTURE_START_BLOCKED reason=${startDecision.reason} " +
                    "thermal=${thermalStatus.name}",
            )
            onStatus(
                "Phone too hot to start safely · " +
                    "let it cool before recording",
            )
            return
        }
        gate.reset(startDecision.analysisProfile)
        recordCameraCaptureResults = false
        val capture = videoCapture ?: run {
            onStatus("Camera not ready")
            return
        }
        val root = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: context.filesDir
        val sessionId = UUID.randomUUID().toString()
        val sessionCreatedAtUnixMs = System.currentTimeMillis()
        val sessionDirectory = File(root, "FirstTake/$sessionId").apply {
            mkdirs()
        }
        val videoFile = File(sessionDirectory, "capture.mp4")
        val telemetryWriter = ProbeTelemetryWriter(
            File(sessionDirectory, "qa-events.jsonl"),
        )
        val imu = try {
            ImuRecorder(
                context = context,
                output = File(sessionDirectory, "session.mcap"),
                cameraClockContract = cameraClockContract,
                onMotion = { motion -> recentMotion.set(motion) },
                onHealthEvent = { event ->
                    telemetryWriter.offerJson(
                        JSONObject()
                            .put(
                                "schemaVersion",
                                "firsttake.probe.telemetry.v1",
                            )
                            .put("type", "IMU_STREAM_HEALTH_EVENT")
                            .put("channelId", event.channelId)
                            .put("issue", event.issue.name)
                            .put(
                                "previousTimestampNs",
                                event.previousTimestampNs,
                            )
                            .put(
                                "currentTimestampNs",
                                event.currentTimestampNs,
                            )
                            .put(
                                "observedDeltaNs",
                                event.observedDeltaNs,
                            )
                            .put(
                                "learnedMedianDeltaNs",
                                event.learnedMedianDeltaNs,
                            )
                            .toString(),
                    )
                },
                onWriteError = { message ->
                    telemetryWriter.offerJson(
                        JSONObject()
                            .put(
                                "schemaVersion",
                                "firsttake.probe.telemetry.v1",
                            )
                            .put("type", "IMU_WRITE_ERROR")
                            .put("message", message)
                            .toString(),
                    )
                },
            )
        } catch (error: Exception) {
            telemetryWriter.close()
            onStatus("Could not create IMU MCAP: ${error.message}")
            return
        }
        val sensors = try {
            imu.start()
        } catch (error: Exception) {
            runCatching {
                imu.stop().get(IMU_FINALIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            telemetryWriter.close()
            Log.e(
                LOG_TAG,
                "IMU_START_FAILED sessionId=$sessionId",
                error,
            )
            sessionDirectory.deleteRecursively()
            onStatus("Could not start IMU capture: ${error.message}")
            return
        }
        val journal = try {
            DurableSessionJournal.open(File(sessionDirectory, "session.wal")).also {
                it.append(
                    type = "SESSION_OPENED",
                    elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                    payload = JSONObject()
                        .put("sessionId", sessionId)
                        .put("videoPath", videoFile.name)
                        .put("imuPath", "session.mcap")
                        .put("imuSensors", sensors)
                        .put(
                            "initialAnalysisProfile",
                            gate.currentProfile().name,
                        )
                        .put("cameraId", cameraClockContract.cameraId)
                        .put(
                            "cameraPhysicalId",
                            cameraSelection.physicalCameraId,
                        )
                        .put(
                            "cameraHorizontalFovDegrees",
                            cameraSelection.horizontalFovDegrees,
                        )
                        .put(
                            "cameraSelectionPolicy",
                            cameraSelection.policy,
                        )
                        .put(
                            "cameraMinimumZoomRatio",
                            cameraSelection.minimumZoomRatio,
                        )
                        .put(
                            "cameraRequestedZoomRatio",
                            cameraSelection.requestedZoomRatio,
                        )
                        .put(
                            "cameraAppliedZoomRatio",
                            cameraSelection.appliedZoomRatio,
                        )
                        .put(
                            "cameraTimestampSource",
                            cameraClockContract.timestampSourceName,
                        )
                        .put(
                            "rgbImuTimestampComparable",
                            cameraClockContract.comparableToElapsedRealtime,
                        )
                        .toString(),
                )
            }
        } catch (error: Exception) {
            runCatching {
                imu.stop().get(IMU_FINALIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
            telemetryWriter.close()
            Log.e(
                LOG_TAG,
                "JOURNAL_START_FAILED sessionId=$sessionId",
                error,
            )
            sessionDirectory.deleteRecursively()
            onStatus("Could not create durable session journal: ${error.message}")
            return
        }
        telemetry = telemetryWriter
        imuRecorder = imu
        recentMotion.set(null)
        imuStopFuture = null
        sessionJournal = journal
        activeVideoFile = videoFile
        activeSessionDirectory = sessionDirectory
        activeSessionId = sessionId
        activeSessionCreatedAtUnixMs = sessionCreatedAtUnixMs
        activeSessionInitialAnalysisProfile =
            startDecision.analysisProfile
        recorderHealthMonitor = RecorderHealthMonitor()
        feedbackState.reset()
        lastJournalCheckpointDurationNs = 0L
        val audioEnabled =
            withAudio &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
        recordingHasAudio = audioEnabled
        telemetryWriter.offerJson(
            JSONObject()
                .put("schemaVersion", "firsttake.probe.telemetry.v1")
                .put("type", "SESSION_STARTED")
                .put("sessionId", sessionId)
                .put("elapsedRealtimeNs", SystemClock.elapsedRealtimeNanos())
                        .put("profile", gate.currentProfile().name)
                        .put("feedbackMode", feedback.mode.name)
                .put("audioRequested", withAudio)
                .put("audioEnabled", audioEnabled)
                .put("imuSensors", sensors)
                .put(
                    "cameraMinimumZoomRatio",
                    cameraSelection.minimumZoomRatio,
                )
                .put(
                    "cameraRequestedZoomRatio",
                    cameraSelection.requestedZoomRatio,
                )
                .put(
                    "cameraAppliedZoomRatio",
                    cameraSelection.appliedZoomRatio,
                )
                .put(
                    "cameraTimestampSource",
                    cameraClockContract.timestampSourceName,
                )
                .put(
                    "rgbImuTimestampComparable",
                    cameraClockContract.comparableToElapsedRealtime,
                )
                .put("thermalStatus", currentThermalStatus())
                .toString(),
        )
        recordMcapEvent(
            type = "SESSION_OPENED",
            payload = JSONObject()
                .put("sessionId", sessionId)
                .put("profile", gate.currentProfile().name)
                .put("cameraId", cameraClockContract.cameraId)
                .put(
                    "cameraPhysicalId",
                    cameraSelection.physicalCameraId,
                )
                .put(
                    "cameraHorizontalFovDegrees",
                    cameraSelection.horizontalFovDegrees,
                )
                .put("cameraSelectionPolicy", cameraSelection.policy)
                .put(
                    "cameraMinimumZoomRatio",
                    cameraSelection.minimumZoomRatio,
                )
                .put(
                    "cameraRequestedZoomRatio",
                    cameraSelection.requestedZoomRatio,
                )
                .put(
                    "cameraAppliedZoomRatio",
                    cameraSelection.appliedZoomRatio,
                )
                .put(
                    "cameraTimestampSource",
                    cameraClockContract.timestampSourceName,
                )
                .put("audioRequested", withAudio)
                .put("audioEnabled", audioEnabled),
        )
        Log.i(
            LOG_TAG,
            "SESSION_OPENED sessionId=$sessionId " +
                "path=${sessionDirectory.absolutePath}",
        )

        var pending = capture.output.prepareRecording(
            context,
            FileOutputOptions.Builder(videoFile).build(),
        )
        if (
            audioEnabled
        ) {
            pending = pending.withAudioEnabled()
        }
        recording = try {
            pending.start(mainExecutor) { event ->
                handleVideoEvent(event, sessionDirectory)
            }
        } catch (error: Exception) {
            endAnalysisSession()
            val imuFinalization = runCatching {
                stopImuIfNeeded()?.get(
                    IMU_FINALIZE_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS,
                )
            }.getOrNull()
            telemetryWriter.offerJson(
                JSONObject()
                    .put("schemaVersion", "firsttake.probe.telemetry.v1")
                    .put("type", "CAPTURE_START_FAILED")
                    .put("message", error.message)
                    .put("imuFinalized", imuFinalization?.finalized)
                    .toString(),
            )
            telemetryWriter.close()
            runCatching {
                journal.append(
                    type = "CAPTURE_START_FAILED",
                    elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                    payload = JSONObject()
                        .put("message", error.message)
                        .toString(),
                )
                journal.close()
            }
            clearActiveSessionState()
            Log.e(
                LOG_TAG,
                "CAPTURE_START_FAILED sessionId=$sessionId",
                error,
            )
            onStatus("Could not start video capture: ${error.message}")
            null
        }
    }

    fun stopRecording() {
        val observedAtNs = SystemClock.elapsedRealtimeNanos()
        recordMcapEvent(
            type = "STOP_REQUESTED",
            elapsedRealtimeNs = observedAtNs,
        )
        queueJournalEvent(
            type = "STOP_REQUESTED",
            elapsedRealtimeNs = observedAtNs,
            payload = "{}",
        )
        recording?.stop()
        stopImuIfNeeded()
        onStatus("Finalizing recording…")
    }

    fun close() {
        coordinatorClosed = true
        endAnalysisSession()
        val activeRecording = recording
        if (activeRecording != null) {
            val observedAtNs = SystemClock.elapsedRealtimeNanos()
            recordMcapEvent(
                type = "COORDINATOR_CLOSE_REQUESTED",
                elapsedRealtimeNs = observedAtNs,
            )
            queueJournalEvent(
                type = "COORDINATOR_CLOSE_REQUESTED",
                elapsedRealtimeNs = observedAtNs,
                payload = "{}",
            )
            activeRecording.stop()
        }
        stopImuIfNeeded()
        cameraProvider?.unbindAll()
        boundCamera = null
        sparseAnalyzer?.let { analyzer ->
            analysisExecutor.execute {
                analyzer.close()
            }
        }
        sparseAnalyzer = null
        analysisExecutor.shutdown()
        if (activeRecording == null) {
            telemetry?.close()
            telemetry = null
            sessionJournal?.close()
            sessionJournal = null
            ioExecutor.shutdown()
        }
    }

    private fun handleVideoEvent(
        event: VideoRecordEvent,
        sessionDirectory: File,
    ) {
        when (event) {
            is VideoRecordEvent.Start -> {
                recordCameraCaptureResults = true
                activeAnalysisGeneration = sparseAnalyzer?.beginSession()
                val observedAtNs = SystemClock.elapsedRealtimeNanos()
                Log.i(
                    LOG_TAG,
                    "VIDEO_STARTED sessionId=${activeSessionId ?: "unknown"}",
                )
                onStatus(feedbackState.render(gate.currentProfile()))
                writeVideoEvent("VIDEO_STARTED", event)
                emitOperatorFeedback(
                    category = "CAPTURE_STARTED",
                    assessment = FeedbackAssessment.INFO,
                    spokenText =
                        "Capture started. Live quality checks active.",
                )
                recordMcapEvent(
                    type = "VIDEO_STARTED",
                    elapsedRealtimeNs = observedAtNs,
                    payload = videoEventPayload(event),
                )
                queueJournalEvent(
                    type = "VIDEO_STARTED",
                    elapsedRealtimeNs = observedAtNs,
                    payload = videoEventPayload(event).toString(),
                )
            }

            is VideoRecordEvent.Status -> {
                writeVideoEvent("VIDEO_STATUS", event)
                observeRecorderHealth(event)
                val durationNs = event.recordingStats.recordedDurationNanos
                if (
                    durationNs - lastJournalCheckpointDurationNs >=
                    JOURNAL_CHECKPOINT_INTERVAL_NS
                ) {
                    lastJournalCheckpointDurationNs = durationNs
                    queueJournalEvent(
                        type = "VIDEO_CHECKPOINT",
                        elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                        payload = videoEventPayload(event).toString(),
                    )
                }
            }

            is VideoRecordEvent.Finalize -> {
                recordCameraCaptureResults = false
                writeVideoEvent("VIDEO_FINALIZED", event)
                endAnalysisSession()
                val finalAnalysisProfile = gate.currentProfile()
                val audioEnabled = recordingHasAudio
                val packageInfo = context.packageManager.getPackageInfo(
                    context.packageName,
                    0,
                )
                recording = null
                recordingHasAudio = false
                val imuFuture = stopImuIfNeeded()
                val telemetryWriter = telemetry
                telemetry = null
                val journal = sessionJournal
                sessionJournal = null
                val videoFile = activeVideoFile
                activeVideoFile = null
                activeSessionDirectory = null
                recorderHealthMonitor = null
                val sessionId = activeSessionId
                activeSessionId = null
                val sessionCreatedAtUnixMs =
                    activeSessionCreatedAtUnixMs ?: System.currentTimeMillis()
                activeSessionCreatedAtUnixMs = null
                val sessionInitialAnalysisProfile =
                    activeSessionInitialAnalysisProfile
                        ?: initialAnalysisProfile
                activeSessionInitialAnalysisProfile = null
                ioExecutor.execute {
                    val imuFinalization = awaitImuFinalization(imuFuture)
                    val integrity = if (videoFile != null) {
                        Mp4IntegrityInspector.inspect(videoFile)
                    } else {
                        Mp4IntegrityReport(false, null, null, null, "NO_VIDEO_FILE")
                    }
                    telemetryWriter?.offerJson(
                        JSONObject()
                            .put("schemaVersion", "firsttake.probe.telemetry.v1")
                            .put("type", "POSTFLIGHT")
                            .put("readable", integrity.readable)
                            .put("videoWidth", integrity.video?.width)
                            .put("videoHeight", integrity.video?.height)
                            .put(
                                "videoDeclaredFrameRate",
                                integrity.video?.declaredFrameRate,
                            )
                            .put("videoSamples", integrity.video?.sampleCount)
                            .put("videoMedianDeltaUs", integrity.video?.medianDeltaUs)
                            .put("videoP95DeltaUs", integrity.video?.p95DeltaUs)
                            .put("videoMaxDeltaUs", integrity.video?.maximumDeltaUs)
                            .put("videoLargeGaps", integrity.video?.largeGapCount)
                            .put("audioVideoEndDeltaUs", integrity.audioVideoEndDeltaUs)
                            .put("imuFinalized", imuFinalization.finalized)
                            .put("imuFinalizeError", imuFinalization.error)
                            .put("error", integrity.error)
                            .put("telemetryDrops", telemetryWriter.droppedCount())
                            .toString(),
                    )
                    val finalPayload = videoEventPayload(event)
                        .put("readable", integrity.readable)
                        .put("videoWidth", integrity.video?.width)
                        .put("videoHeight", integrity.video?.height)
                        .put("videoSamples", integrity.video?.sampleCount)
                        .put("audioVideoEndDeltaUs", integrity.audioVideoEndDeltaUs)
                        .put("postflightError", integrity.error)
                    telemetryWriter?.close()
                    val telemetryReport = telemetryWriter?.report()
                    val postflightInput = sessionId?.let {
                        SessionPostflightInput(
                            sessionId = it,
                            finalizedAtUnixMs = System.currentTimeMillis(),
                            cameraXFinalizeError = event.error,
                            video = integrity,
                            imu = imuFinalization,
                            telemetry = telemetryReport,
                        )
                    }
                    val acceptanceStatus = postflightInput?.let(
                        SessionArtifactWriter::acceptanceStatus,
                    ) ?: SessionAcceptanceStatus.INCOMPLETE
                    try {
                        journal?.append(
                            type = "VIDEO_FINALIZED",
                            elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                            payload = finalPayload.toString(),
                        )
                        journal?.append(
                            type = "IMU_FINALIZED",
                            elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                            payload = JSONObject()
                                .put("finalized", imuFinalization.finalized)
                                .put("error", imuFinalization.error)
                                .toString(),
                        )
                        journal?.append(
                            type = "TELEMETRY_FINALIZED",
                            elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                            payload = JSONObject()
                                .put(
                                    "writtenRecords",
                                    telemetryReport?.writtenRecords,
                                )
                                .put(
                                    "droppedRecords",
                                    telemetryReport?.droppedRecords,
                                )
                                .put(
                                    "lastHash",
                                    telemetryReport?.lastHash,
                                )
                                .put(
                                    "complete",
                                    telemetryReport?.complete ?: false,
                                )
                                .put("error", telemetryReport?.error)
                                .toString(),
                        )
                        journal?.append(
                            type = if (
                                acceptanceStatus ==
                                SessionAcceptanceStatus.PASS
                            ) {
                                "SESSION_COMMITTED"
                            } else {
                                "SESSION_INCOMPLETE"
                            },
                            elapsedRealtimeNs = SystemClock.elapsedRealtimeNanos(),
                            payload = JSONObject()
                                .put("videoReadable", integrity.readable)
                                .put("finalizeError", event.error)
                                .put("imuFinalized", imuFinalization.finalized)
                                .put("imuFinalizeError", imuFinalization.error)
                                .put(
                                    "telemetryComplete",
                                    telemetryReport?.complete ?: false,
                                )
                                .put(
                                    "acceptanceStatus",
                                    acceptanceStatus.name,
                                )
                                .toString(),
                        )
                    } catch (journalError: Exception) {
                        telemetryWriter?.offerJson(
                            JSONObject()
                                .put(
                                    "schemaVersion",
                                    "firsttake.probe.telemetry.v1",
                                )
                                .put("type", "JOURNAL_WRITE_ERROR")
                                .put("message", journalError.message)
                                .toString(),
                        )
                    } finally {
                        journal?.close()
                    }
                    val artifactResult = runCatching {
                        require(sessionId != null) {
                            "session id missing during finalization"
                        }
                        require(postflightInput != null) {
                            "postflight input missing during finalization"
                        }
                        SessionArtifactWriter.write(
                            sessionDirectory = sessionDirectory,
                            manifest = SessionManifestInput(
                                sessionId = sessionId,
                                createdAtUnixMs = sessionCreatedAtUnixMs,
                                appVersionName =
                                    packageInfo.versionName ?: "unknown",
                                appVersionCode =
                                    packageInfo.longVersionCode.toInt(),
                                deviceManufacturer = Build.MANUFACTURER,
                                deviceModel = Build.MODEL,
                                deviceName = Build.DEVICE,
                                androidRelease = Build.VERSION.RELEASE,
                                androidSdk = Build.VERSION.SDK_INT,
                                cameraId = cameraClockContract.cameraId,
                                cameraPhysicalId =
                                    cameraSelection.physicalCameraId,
                                cameraHorizontalFovDegrees =
                                    cameraSelection.horizontalFovDegrees,
                                cameraSelectionPolicy =
                                    cameraSelection.policy,
                                cameraTimestampSource =
                                    cameraClockContract.timestampSourceName,
                                cameraTimestampComparableToElapsedRealtime =
                                    cameraClockContract
                                        .comparableToElapsedRealtime,
                                audioEnabled = audioEnabled,
                                initialAnalysisProfile =
                                    sessionInitialAnalysisProfile,
                                finalAnalysisProfile = finalAnalysisProfile,
                                cameraMinimumZoomRatio =
                                    cameraSelection.minimumZoomRatio,
                                cameraRequestedZoomRatio =
                                    cameraSelection.requestedZoomRatio,
                                cameraAppliedZoomRatio =
                                    cameraSelection.appliedZoomRatio,
                            ),
                            postflight = postflightInput,
                        )
                    }
                    artifactResult.exceptionOrNull()?.let { artifactError ->
                        Log.e(
                            LOG_TAG,
                            "EVIDENCE_FINALIZE_FAILED " +
                                "sessionId=${sessionId ?: "unknown"}",
                            artifactError,
                        )
                    }
                    mainExecutor.execute {
                        val accepted =
                            artifactResult.getOrNull()?.acceptanceStatus ==
                                SessionAcceptanceStatus.PASS
                        val result = when {
                            event.hasError() ->
                                "Capture incomplete · " +
                                    "finalize error ${event.error}"
                            !integrity.readable ->
                                "Capture incomplete · MP4 unreadable"
                            artifactResult.isFailure ->
                                "Capture incomplete · " +
                                    "evidence finalization failed"
                            !accepted ->
                                "Capture incomplete · " +
                                    "postflight checks did not pass"
                            else ->
                                "Saved · verified · " +
                                    "${integrity.video?.sampleCount} " +
                                    "video samples"
                        }
                        onStatus(
                            "$result\n" +
                                sessionDirectory.absolutePath,
                        )
                        Log.i(
                            LOG_TAG,
                            "VIDEO_FINALIZED sessionId=${sessionId ?: "unknown"} " +
                                "readable=${integrity.readable} " +
                                "imuFinalized=${imuFinalization.finalized} " +
                                "evidenceWritten=${artifactResult.isSuccess} " +
                                "acceptanceStatus=${acceptanceStatus.name}",
                        )
                    }
                    if (coordinatorClosed) {
                        ioExecutor.shutdown()
                    }
                }
            }
        }
    }

    private fun writeVideoEvent(type: String, event: VideoRecordEvent) {
        telemetry?.offerJson(
            JSONObject()
                .put("schemaVersion", "firsttake.probe.telemetry.v1")
                .put("type", type)
                .put("elapsedRealtimeNs", SystemClock.elapsedRealtimeNanos())
                .put(
                    "recordedDurationNs",
                    event.recordingStats.recordedDurationNanos,
                )
                .put("bytesRecorded", event.recordingStats.numBytesRecorded)
                .put("thermalStatus", currentThermalStatus())
                .toString(),
        )
    }

    private fun videoEventPayload(event: VideoRecordEvent): JSONObject =
        JSONObject()
            .put(
                "recordedDurationNs",
                event.recordingStats.recordedDurationNanos,
            )
            .put("bytesRecorded", event.recordingStats.numBytesRecorded)
            .put("thermalStatus", currentThermalStatus())

    private fun observeRecorderHealth(event: VideoRecordEvent.Status) {
        val usableStorage = activeSessionDirectory?.usableSpace
            ?.takeIf { it > 0 }
        val observedAtNs = SystemClock.elapsedRealtimeNanos()
        val healthEvent = recorderHealthMonitor?.observe(
            RecorderHealthObservation(
                observedAtElapsedRealtimeNs = observedAtNs,
                recordedDurationNs =
                    event.recordingStats.recordedDurationNanos,
                bytesRecorded = event.recordingStats.numBytesRecorded,
                usableStorageBytes = usableStorage,
            ),
        ) ?: return
        telemetry?.offerJson(
            JSONObject()
                .put("schemaVersion", "firsttake.probe.telemetry.v1")
                .put("type", "RECORDER_HEALTH_TRANSITION")
                .put("previousState", healthEvent.previousState.name)
                .put("newState", healthEvent.newState.name)
                .put("action", healthEvent.action.name)
                .put("reason", healthEvent.reason)
                .put(
                    "estimatedStorageSecondsRemaining",
                    healthEvent.estimatedStorageSecondsRemaining,
                )
                .put("usableStorageBytes", healthEvent.usableStorageBytes)
                .toString(),
        )
        recordMcapEvent(
            type = "RECORDER_HEALTH_TRANSITION",
            elapsedRealtimeNs = observedAtNs,
            payload = JSONObject()
                .put("previousState", healthEvent.previousState.name)
                .put("newState", healthEvent.newState.name)
                .put("action", healthEvent.action.name)
                .put("reason", healthEvent.reason)
                .put(
                    "estimatedStorageSecondsRemaining",
                    healthEvent.estimatedStorageSecondsRemaining,
                )
                .put("usableStorageBytes", healthEvent.usableStorageBytes),
        )
        Log.w(
            LOG_TAG,
            "RECORDER_HEALTH_TRANSITION " +
                "state=${healthEvent.newState.name} " +
                "action=${healthEvent.action.name}",
        )
        when (healthEvent.action) {
            RecorderHealthAction.NONE -> Unit
            RecorderHealthAction.DISABLE_ANALYSIS ->
                gate.degradeTo(AnalysisProfile.WRITERS_ONLY)
            RecorderHealthAction.GRACEFUL_STOP -> {
                queueJournalEvent(
                    type = "GRACEFUL_STOP_STORAGE_CRITICAL",
                    payload = JSONObject()
                        .put(
                            "usableStorageBytes",
                            healthEvent.usableStorageBytes,
                        )
                        .put(
                            "estimatedStorageSecondsRemaining",
                            healthEvent.estimatedStorageSecondsRemaining,
                        )
                        .toString(),
                )
                recording?.stop()
                stopImuIfNeeded()
            }
        }
        feedbackState.apply(healthEvent)
        when (healthEvent.newState) {
            RecorderHealthState.STORAGE_WARNING ->
                emitOperatorFeedback(
                    category = "STORAGE_WARNING",
                    assessment = FeedbackAssessment.WARNING,
                    spokenText = "Storage low. Finish this take soon.",
                )
            RecorderHealthState.STORAGE_CRITICAL ->
                emitOperatorFeedback(
                    category = "STORAGE_CRITICAL",
                    assessment = FeedbackAssessment.WARNING,
                    spokenText = "Storage critical. Stopping safely.",
                )
            RecorderHealthState.WRITER_STALLED ->
                emitOperatorFeedback(
                    category = "WRITER_STALLED",
                    assessment = FeedbackAssessment.WARNING,
                    spokenText = "Recorder stalled.",
                )
            RecorderHealthState.HEALTHY -> Unit
        }
        onStatus(feedbackState.render(gate.currentProfile()))
    }

    private fun queueJournalEvent(
        type: String,
        elapsedRealtimeNs: Long = SystemClock.elapsedRealtimeNanos(),
        payload: String,
    ) {
        val journal = sessionJournal ?: return
        ioExecutor.execute {
            try {
                journal.append(
                    type = type,
                    elapsedRealtimeNs = elapsedRealtimeNs,
                    payload = payload,
                )
            } catch (error: Exception) {
                telemetry?.offerJson(
                    JSONObject()
                        .put("schemaVersion", "firsttake.probe.telemetry.v1")
                        .put("type", "JOURNAL_WRITE_ERROR")
                        .put("eventType", type)
                        .put("message", error.message)
                        .toString(),
                )
            }
        }
    }

    private fun currentThermalStatus(): Int {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.currentThermalStatus
    }

    private fun currentThermalObservation(): Pair<ThermalStatus, Boolean> {
        val status = when (currentThermalStatus()) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
            else -> ThermalStatus.UNKNOWN
        }
        return status to (status != ThermalStatus.UNKNOWN)
    }

    private fun handleBudgetTransition(transition: AnalyzerBudgetTransition) {
        val observedAtNs = SystemClock.elapsedRealtimeNanos()
        Log.w(
            LOG_TAG,
            "ANALYSIS_PROFILE_DEGRADED " +
                "from=${transition.previousProfile.name} " +
                "to=${transition.newProfile.name} " +
                "reasons=${transition.reasons.joinToString(",")}",
        )
        mainExecutor.execute {
            if (recording == null) {
                return@execute
            }
            recordMcapEvent(
                type = "ANALYSIS_PROFILE_DEGRADED",
                elapsedRealtimeNs = observedAtNs,
                payload = JSONObject()
                    .put("from", transition.previousProfile.name)
                    .put("to", transition.newProfile.name)
                    .put("reasons", transition.reasons.joinToString(",")),
            )
            onStatus(feedbackState.render(transition.newProfile))
        }
    }

    private fun handleHandEdgeTransition(transition: HandEdgeTransition) {
        val observedAtNs = SystemClock.elapsedRealtimeNanos()
        Log.i(
            LOG_TAG,
            "HAND_EDGE_TRANSITION kind=${transition.kind.name} " +
                "cause=${transition.cause.name}",
        )
        mainExecutor.execute {
            if (recording == null) {
                return@execute
            }
            recordMcapEvent(
                type = "HAND_EDGE_TRANSITION",
                elapsedRealtimeNs = observedAtNs,
                payload = JSONObject()
                    .put("kind", transition.kind.name)
                    .put("cause", transition.cause.name),
            )
            feedbackState.apply(transition)
            val recoveryCanBeAnnounced =
                !feedbackState.hasActionableProblems()
            if (
                transition.kind == HandEdgeTransitionKind.ALERT ||
                recoveryCanBeAnnounced
            ) {
                emitOperatorFeedback(
                    category = when (transition.cause) {
                        HandAlertCause.EDGE_RISK -> "HAND_EDGE_RISK"
                        HandAlertCause.LOST -> "HAND_LOST"
                    },
                    assessment = when (transition.kind) {
                        HandEdgeTransitionKind.ALERT ->
                            FeedbackAssessment.WARNING
                        HandEdgeTransitionKind.RECOVERED ->
                            FeedbackAssessment.RECOVERED
                    },
                    spokenText = when (transition.kind) {
                        HandEdgeTransitionKind.ALERT -> when (
                            transition.cause
                        ) {
                            HandAlertCause.EDGE_RISK ->
                                "Hand leaving frame. " +
                                    "Move it toward the center."
                            HandAlertCause.LOST ->
                                "Hand lost. Bring it back into frame."
                        }
                        HandEdgeTransitionKind.RECOVERED ->
                            "Hand back in frame."
                    },
                )
            }
            renderFeedbackStatus(
                recoveredMessage = if (
                    transition.kind == HandEdgeTransitionKind.RECOVERED &&
                    recoveryCanBeAnnounced
                ) {
                    "Hand back in frame"
                } else {
                    null
                },
            )
        }
    }

    private fun handleHandStatus(status: HandBaselineStatus) {
        mainExecutor.execute {
            if (recording != null) {
                onHandStatusChanged(status)
            }
        }
    }

    private fun handleQualityTransition(transition: QualityTransition) {
        val observedAtNs = SystemClock.elapsedRealtimeNanos()
        Log.i(
            LOG_TAG,
            "QUALITY_TRANSITION defect=${transition.defect.name} " +
                "kind=${transition.kind.name}",
        )
        mainExecutor.execute {
            if (recording == null) {
                return@execute
            }
            recordMcapEvent(
                type = "QUALITY_TRANSITION",
                elapsedRealtimeNs = observedAtNs,
                payload = JSONObject()
                    .put("defect", transition.defect.name)
                    .put("kind", transition.kind.name),
            )
            feedbackState.apply(transition)
            val warningText = when (transition.defect) {
                FrameDefect.DARK_OR_COVERED ->
                    "Image too dark. Check the lens or add light."
                FrameDefect.OVEREXPOSED ->
                    "Image too bright. Reduce glare."
                FrameDefect.POSSIBLE_BLUR ->
                    "Image blurry. Steady the camera."
                FrameDefect.FRAME_NOT_RESPONDING_TO_MOTION ->
                    "Camera image may be frozen. Check the camera."
                FrameDefect.CAMERA_SHAKE ->
                    "Camera too shaky. Adjust the mount."
                FrameDefect.CAMERA_ANGLE ->
                    "Camera angle wrong. Point it down at the task."
            }
            val recoveryCanBeAnnounced =
                !feedbackState.hasActionableProblems()
            if (
                transition.kind == QualityTransitionKind.ALERT ||
                recoveryCanBeAnnounced
            ) {
                emitOperatorFeedback(
                    category = transition.defect.name,
                    assessment = when (transition.kind) {
                        QualityTransitionKind.ALERT ->
                            FeedbackAssessment.WARNING
                        QualityTransitionKind.RECOVERED ->
                            FeedbackAssessment.RECOVERED
                    },
                    spokenText = if (
                        transition.kind == QualityTransitionKind.RECOVERED
                    ) {
                        "Capture recovered."
                    } else {
                        warningText
                    },
                )
            }
            renderFeedbackStatus(
                recoveredMessage = if (
                    transition.kind == QualityTransitionKind.RECOVERED &&
                    recoveryCanBeAnnounced
                ) {
                    "Capture recovered"
                } else {
                    null
                },
            )
        }
    }

    private fun renderFeedbackStatus(recoveredMessage: String?) {
        val current = feedbackState.render(gate.currentProfile())
        if (
            recoveredMessage == null ||
            current.contains("Fix now")
        ) {
            onStatus(current)
            return
        }
        onStatus(recoveredMessage)
        previewView.postDelayed(
            {
                if (recording != null) {
                    onStatus(feedbackState.render(gate.currentProfile()))
                }
            },
            RECOVERY_CONFIRMATION_VISIBLE_MS,
        )
    }

    private fun emitOperatorFeedback(
        category: String,
        assessment: FeedbackAssessment,
        spokenText: String,
    ) {
        if (recording == null) {
            return
        }
        feedback.emit(
            FeedbackMessage(
                category = category,
                assessment = assessment,
                spokenText = spokenText,
            ),
            allowVoice = !recordingHasAudio,
        )
        val observedAtNs = SystemClock.elapsedRealtimeNanos()
        telemetry?.offerJson(
            JSONObject()
                .put("schemaVersion", "firsttake.probe.telemetry.v1")
                .put("type", "OPERATOR_FEEDBACK")
                .put("category", category)
                .put("assessment", assessment.name)
                .put("mode", feedback.mode.name)
                .put("voiceAllowed", !recordingHasAudio)
                .put("elapsedRealtimeNs", observedAtNs)
                .toString(),
        )
        recordMcapEvent(
            type = "OPERATOR_FEEDBACK",
            elapsedRealtimeNs = observedAtNs,
            payload = JSONObject()
                .put("category", category)
                .put("assessment", assessment.name)
                .put("mode", feedback.mode.name)
                .put("voiceAllowed", !recordingHasAudio),
        )
    }

    private fun recordMcapEvent(
        type: String,
        elapsedRealtimeNs: Long = SystemClock.elapsedRealtimeNanos(),
        payload: JSONObject = JSONObject(),
    ) {
        imuRecorder?.recordCaptureEvent(
            type = type,
            elapsedRealtimeNs = elapsedRealtimeNs,
            payload = payload,
        )
    }

    private fun logControlledExposureProbe(
        level: ControlledExposureLevel,
        requestedIndex: Int?,
        applied: Boolean,
        appliedIndex: Int? = null,
        error: String? = null,
    ) {
        val observedAtNs = SystemClock.elapsedRealtimeNanos()
        val payload = JSONObject()
            .put("level", level.name)
            .put("requestedIndex", requestedIndex)
            .put("applied", applied)
            .put("appliedIndex", appliedIndex)
            .put("error", error)
        telemetry?.offerJson(
            JSONObject()
                .put("schemaVersion", "firsttake.probe.telemetry.v1")
                .put("type", "CONTROLLED_EXPOSURE_PROBE")
                .put("elapsedRealtimeNs", observedAtNs)
                .put("level", level.name)
                .put("requestedIndex", requestedIndex)
                .put("applied", applied)
                .put("appliedIndex", appliedIndex)
                .put("error", error)
                .toString(),
        )
        recordMcapEvent(
            type = "CONTROLLED_EXPOSURE_PROBE",
            elapsedRealtimeNs = observedAtNs,
            payload = payload,
        )
    }

    private fun stopImuIfNeeded(): CompletableFuture<ImuFinalizeReport>? {
        imuStopFuture?.let { return it }
        val imu = imuRecorder ?: return null
        imuRecorder = null
        return imu.stop().also { imuStopFuture = it }
    }

    private fun endAnalysisSession() {
        val generation = activeAnalysisGeneration ?: return
        activeAnalysisGeneration = null
        sparseAnalyzer?.endSession(generation)
    }

    private fun clearActiveSessionState() {
        telemetry = null
        imuRecorder = null
        imuStopFuture = null
        sessionJournal = null
        activeVideoFile = null
        activeSessionDirectory = null
        activeSessionId = null
        activeSessionCreatedAtUnixMs = null
        activeSessionInitialAnalysisProfile = null
        recorderHealthMonitor = null
        recordingHasAudio = false
    }

    private fun awaitImuFinalization(
        future: CompletableFuture<ImuFinalizeReport>?,
    ): ImuFinalizeReport {
        if (future == null) {
            return ImuFinalizeReport(
                finalized = false,
                error = "IMU recorder was unavailable",
            )
        }
        return try {
            future.get(IMU_FINALIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (error: Exception) {
            ImuFinalizeReport(
                finalized = false,
                error = "IMU finalization failed or timed out: ${error.message}",
            )
        }
    }

    private companion object {
        const val JOURNAL_CHECKPOINT_INTERVAL_NS = 2_000_000_000L
        const val IMU_FINALIZE_TIMEOUT_SECONDS = 15L
        const val RECOVERY_CONFIRMATION_VISIBLE_MS = 2_000L
        const val LOG_TAG = "FirstTakeProbe"
    }
}

enum class ControlledExposureLevel {
    MINIMUM,
    NOMINAL,
    MAXIMUM,
}
