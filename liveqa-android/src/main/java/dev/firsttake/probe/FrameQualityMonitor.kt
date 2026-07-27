package dev.firsttake.probe

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class LumaMetrics(
    val mean: Double,
    val standardDeviation: Double,
    val darkFraction: Double,
    val brightFraction: Double,
    val meanGradient: Double,
    val laplacianVariance: Double,
    val signature: DoubleArray,
)

object LumaMetricsComputer {
    fun compute(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        columns: Int = 32,
        rows: Int = 24,
        sharpnessColumns: Int = 160,
        sharpnessRows: Int = 120,
    ): LumaMetrics {
        require(width > 0 && height > 0)
        require(rowStride > 0 && pixelStride > 0)
        require(columns >= 2 && rows >= 2)
        require(sharpnessColumns >= 3 && sharpnessRows >= 3)
        val base = buffer.position()
        val signature = DoubleArray(columns * rows)
        var sum = 0.0
        var sumSquares = 0.0
        var dark = 0
        var bright = 0
        var gradientSum = 0.0
        var gradientCount = 0

        for (row in 0 until rows) {
            val y = row * (height - 1) / (rows - 1)
            for (column in 0 until columns) {
                val x = column * (width - 1) / (columns - 1)
                val index = base + y * rowStride + x * pixelStride
                require(index in 0 until buffer.limit()) {
                    "Y plane layout exceeds buffer bounds"
                }
                val value = (buffer.get(index).toInt() and 0xff) / 255.0
                val signatureIndex = row * columns + column
                signature[signatureIndex] = value
                sum += value
                sumSquares += value * value
                if (value <= DARK_PIXEL_THRESHOLD) {
                    dark += 1
                }
                if (value >= BRIGHT_PIXEL_THRESHOLD) {
                    bright += 1
                }
                if (column > 0) {
                    gradientSum += abs(value - signature[signatureIndex - 1])
                    gradientCount += 1
                }
                if (row > 0) {
                    gradientSum +=
                        abs(value - signature[signatureIndex - columns])
                    gradientCount += 1
                }
            }
        }

        val count = signature.size.toDouble()
        val mean = sum / count
        val variance = max(0.0, sumSquares / count - mean * mean)
        val laplacianVariance = computeLaplacianVariance(
            buffer = buffer,
            base = base,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            columns = sharpnessColumns.coerceAtMost(width).coerceAtLeast(3),
            rows = sharpnessRows.coerceAtMost(height).coerceAtLeast(3),
        )
        return LumaMetrics(
            mean = mean,
            standardDeviation = sqrt(variance),
            darkFraction = dark / count,
            brightFraction = bright / count,
            meanGradient = if (gradientCount > 0) {
                gradientSum / gradientCount
            } else {
                0.0
            },
            laplacianVariance = laplacianVariance,
            signature = signature,
        )
    }

    private fun computeLaplacianVariance(
        buffer: ByteBuffer,
        base: Int,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        columns: Int,
        rows: Int,
    ): Double {
        val samples = DoubleArray(columns * rows)
        for (row in 0 until rows) {
            val y = row * (height - 1) / (rows - 1)
            for (column in 0 until columns) {
                val x = column * (width - 1) / (columns - 1)
                val index = base + y * rowStride + x * pixelStride
                require(index in 0 until buffer.limit()) {
                    "Y plane layout exceeds buffer bounds"
                }
                samples[row * columns + column] =
                    (buffer.get(index).toInt() and 0xff) / 255.0
            }
        }
        var sum = 0.0
        var sumSquares = 0.0
        var count = 0
        for (row in 1 until rows - 1) {
            for (column in 1 until columns - 1) {
                val center = row * columns + column
                val laplacian =
                    samples[center - columns] +
                        samples[center + columns] +
                        samples[center - 1] +
                        samples[center + 1] -
                        4.0 * samples[center]
                sum += laplacian
                sumSquares += laplacian * laplacian
                count += 1
            }
        }
        if (count == 0) {
            return 0.0
        }
        val mean = sum / count
        return max(0.0, sumSquares / count - mean * mean)
    }

    fun meanAbsoluteFrameDelta(
        previous: DoubleArray?,
        current: DoubleArray,
    ): Double? {
        if (previous == null || previous.size != current.size) {
            return null
        }
        if (current.isEmpty()) {
            return null
        }
        return previous.indices
            .sumOf { index -> abs(previous[index] - current[index]) } /
            current.size
    }

    private const val DARK_PIXEL_THRESHOLD = 16.0 / 255.0
    private const val BRIGHT_PIXEL_THRESHOLD = 245.0 / 255.0
}

data class MotionEvidence(
    val observedAtElapsedRealtimeNs: Long,
    val gyroscopeMagnitudeRadPerSec: Double,
    val shakeRmsRadPerSec: Double? = null,
    val cameraTiltFromGravityDegrees: Double? = null,
    val motionWindowDurationNs: Long = 0,
)

enum class FrameDefect {
    DARK_OR_COVERED,
    OVEREXPOSED,
    POSSIBLE_BLUR,
    FRAME_NOT_RESPONDING_TO_MOTION,
    CAMERA_SHAKE,
    CAMERA_ANGLE,
}

enum class DefectAssessment {
    DETECTED,
    CLEAR,
    UNKNOWN,
}

enum class QualityTransitionKind {
    ALERT,
    RECOVERED,
}

data class QualityTransition(
    val defect: FrameDefect,
    val kind: QualityTransitionKind,
    val observedAtElapsedRealtimeNs: Long,
    val reason: String,
)

data class FrameQualityObservation(
    val observedAtElapsedRealtimeNs: Long,
    val metrics: LumaMetrics,
    val previousFrameDelta: Double?,
    val motion: MotionEvidence?,
    val cameraTimestampComparableToElapsedRealtime: Boolean,
)

data class FrameQualityEvaluation(
    val assessments: Map<FrameDefect, DefectAssessment>,
    val transitions: List<QualityTransition>,
)

class FrameQualityMonitor(
    private val alertPersistenceNs: Long = 2_000_000_000L,
    private val recoveryPersistenceNs: Long = 1_000_000_000L,
) {
    private val trackers = FrameDefect.entries.associateWith { Tracker() }
    private var lastObservationNs = Long.MIN_VALUE

    fun ingest(observation: FrameQualityObservation): FrameQualityEvaluation {
        require(
            observation.observedAtElapsedRealtimeNs > lastObservationNs,
        ) { "Frame observations must be strictly time ordered" }
        lastObservationNs = observation.observedAtElapsedRealtimeNs
        val assessments = classify(observation)
        val transitions = buildList {
            assessments.forEach { (defect, assessment) ->
                updateTracker(
                    defect = defect,
                    assessment = assessment,
                    nowNs = observation.observedAtElapsedRealtimeNs,
                )?.let(::add)
            }
        }
        return FrameQualityEvaluation(
            assessments = assessments,
            transitions = transitions,
        )
    }

    private fun classify(
        observation: FrameQualityObservation,
    ): Map<FrameDefect, DefectAssessment> {
        val metrics = observation.metrics
        val dark = when {
            metrics.mean <= 0.08 || metrics.darkFraction >= 0.85 ->
                DefectAssessment.DETECTED
            metrics.mean >= 0.12 && metrics.darkFraction < 0.70 ->
                DefectAssessment.CLEAR
            else -> DefectAssessment.UNKNOWN
        }
        val overexposed = when {
            metrics.mean >= 0.92 || metrics.brightFraction >= 0.85 ->
                DefectAssessment.DETECTED
            metrics.mean <= 0.88 && metrics.brightFraction < 0.70 ->
                DefectAssessment.CLEAR
            else -> DefectAssessment.UNKNOWN
        }
        val blur = when {
            dark == DefectAssessment.DETECTED ||
                overexposed == DefectAssessment.DETECTED ->
                DefectAssessment.UNKNOWN
            metrics.standardDeviation < 0.04 ->
                DefectAssessment.UNKNOWN
            metrics.laplacianVariance <= 0.0008 ->
                DefectAssessment.DETECTED
            metrics.laplacianVariance >= 0.0015 ->
                DefectAssessment.CLEAR
            else -> DefectAssessment.UNKNOWN
        }
        val motion = observation.motion
        val motionFresh = motion != null &&
            abs(
                observation.observedAtElapsedRealtimeNs -
                    motion.observedAtElapsedRealtimeNs,
            ) <= MOTION_MAX_AGE_NS
        val freeze = when {
            !observation.cameraTimestampComparableToElapsedRealtime ->
                DefectAssessment.UNKNOWN
            !motionFresh || observation.previousFrameDelta == null ->
                DefectAssessment.UNKNOWN
            motion!!.gyroscopeMagnitudeRadPerSec >= 0.30 &&
                observation.previousFrameDelta <= 0.008 ->
                DefectAssessment.DETECTED
            motion.gyroscopeMagnitudeRadPerSec >= 0.20 &&
                observation.previousFrameDelta >= 0.020 ->
                DefectAssessment.CLEAR
            else -> DefectAssessment.UNKNOWN
        }
        val shake = when {
            !motionFresh || motion?.shakeRmsRadPerSec == null ->
                DefectAssessment.UNKNOWN
            motion.shakeRmsRadPerSec >= SHAKE_DETECTED_RMS_RAD_PER_SEC ->
                DefectAssessment.DETECTED
            motion.shakeRmsRadPerSec <= SHAKE_CLEAR_RMS_RAD_PER_SEC ->
                DefectAssessment.CLEAR
            else -> DefectAssessment.UNKNOWN
        }
        val cameraAngle = when {
            !motionFresh ||
                motion?.cameraTiltFromGravityDegrees == null ->
                DefectAssessment.UNKNOWN
            motion.cameraTiltFromGravityDegrees >=
                CAMERA_ANGLE_DETECTED_DEGREES ->
                DefectAssessment.DETECTED
            motion.cameraTiltFromGravityDegrees <=
                CAMERA_ANGLE_CLEAR_DEGREES ->
                DefectAssessment.CLEAR
            else -> DefectAssessment.UNKNOWN
        }
        return linkedMapOf(
            FrameDefect.DARK_OR_COVERED to dark,
            FrameDefect.OVEREXPOSED to overexposed,
            FrameDefect.POSSIBLE_BLUR to blur,
            FrameDefect.FRAME_NOT_RESPONDING_TO_MOTION to freeze,
            FrameDefect.CAMERA_SHAKE to shake,
            FrameDefect.CAMERA_ANGLE to cameraAngle,
        )
    }

    private fun updateTracker(
        defect: FrameDefect,
        assessment: DefectAssessment,
        nowNs: Long,
    ): QualityTransition? {
        val tracker = trackers.getValue(defect)
        return when (assessment) {
            DefectAssessment.DETECTED -> {
                tracker.clearSinceNs = null
                val detectedSince = tracker.detectedSinceNs ?: nowNs.also {
                    tracker.detectedSinceNs = it
                }
                if (
                    !tracker.alertActive &&
                    nowNs - detectedSince >= alertPersistenceNs
                ) {
                    tracker.alertActive = true
                    QualityTransition(
                        defect = defect,
                        kind = QualityTransitionKind.ALERT,
                        observedAtElapsedRealtimeNs = nowNs,
                        reason = alertReason(defect),
                    )
                } else {
                    null
                }
            }

            DefectAssessment.CLEAR -> {
                tracker.detectedSinceNs = null
                if (!tracker.alertActive) {
                    tracker.clearSinceNs = null
                    null
                } else {
                    val clearSince = tracker.clearSinceNs ?: nowNs.also {
                        tracker.clearSinceNs = it
                    }
                    if (nowNs - clearSince >= recoveryPersistenceNs) {
                        tracker.alertActive = false
                        tracker.clearSinceNs = null
                        QualityTransition(
                            defect = defect,
                            kind = QualityTransitionKind.RECOVERED,
                            observedAtElapsedRealtimeNs = nowNs,
                            reason = "Signal returned to the clear range",
                        )
                    } else {
                        null
                    }
                }
            }

            DefectAssessment.UNKNOWN -> {
                tracker.detectedSinceNs = null
                tracker.clearSinceNs = null
                null
            }
        }
    }

    private fun alertReason(defect: FrameDefect): String = when (defect) {
        FrameDefect.DARK_OR_COVERED ->
            "Image has remained too dark or covered"
        FrameDefect.OVEREXPOSED ->
            "Image has remained heavily overexposed"
        FrameDefect.POSSIBLE_BLUR ->
            "Image has remained low-detail under assessable lighting"
        FrameDefect.FRAME_NOT_RESPONDING_TO_MOTION ->
            "Camera frames stayed nearly unchanged during measured rotation"
        FrameDefect.CAMERA_SHAKE ->
            "High-frequency camera shake remained above the safe range"
        FrameDefect.CAMERA_ANGLE ->
            "The camera optical axis remained too far from gravity"
    }

    private data class Tracker(
        var detectedSinceNs: Long? = null,
        var clearSinceNs: Long? = null,
        var alertActive: Boolean = false,
    )

    private companion object {
        const val MOTION_MAX_AGE_NS = 500_000_000L
        const val SHAKE_DETECTED_RMS_RAD_PER_SEC = 0.85
        const val SHAKE_CLEAR_RMS_RAD_PER_SEC = 0.55
        const val CAMERA_ANGLE_DETECTED_DEGREES = 55.0
        const val CAMERA_ANGLE_CLEAR_DEGREES = 45.0
    }
}
