package dev.firsttake.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class FrameQualityMonitorTest {
    @Test
    fun `luma metrics distinguish dark bright and textured frames`() {
        val dark = metrics(ByteArray(64 * 48) { 0 })
        val bright = metrics(ByteArray(64 * 48) { 255.toByte() })
        val checkerboard = metrics(
            ByteArray(64 * 48) { index ->
                val x = index % 64
                if ((x / 8) % 2 == 0) 0 else 255.toByte()
            },
        )

        assertEquals(1.0, dark.darkFraction, 1e-9)
        assertEquals(1.0, bright.brightFraction, 1e-9)
        assertTrue(checkerboard.meanGradient > dark.meanGradient)
        assertTrue(checkerboard.standardDeviation > 0.4)
        assertTrue(checkerboard.laplacianVariance > dark.laplacianVariance)
    }

    @Test
    fun `row and pixel stride are honored`() {
        val width = 4
        val height = 3
        val rowStride = 10
        val pixelStride = 2
        val bytes = ByteArray(rowStride * height) { 99.toByte() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                bytes[y * rowStride + x * pixelStride] = 128.toByte()
            }
        }

        val result = LumaMetricsComputer.compute(
            buffer = ByteBuffer.wrap(bytes),
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            columns = 4,
            rows = 3,
        )

        assertEquals(128.0 / 255.0, result.mean, 1e-9)
        assertEquals(0.0, result.standardDeviation, 1e-9)
    }

    @Test
    fun `frame delta is unknown first then exact`() {
        val first = doubleArrayOf(0.0, 0.5, 1.0)
        val second = doubleArrayOf(0.0, 0.0, 1.0)

        assertNull(LumaMetricsComputer.meanAbsoluteFrameDelta(null, first))
        assertEquals(
            1.0 / 6.0,
            LumaMetricsComputer.meanAbsoluteFrameDelta(first, second)!!,
            1e-9,
        )
    }

    @Test
    fun `darkness must persist and recovery must also persist`() {
        val monitor = FrameQualityMonitor(
            alertPersistenceNs = 2_000_000_000,
            recoveryPersistenceNs = 1_000_000_000,
        )
        val transitions = mutableListOf<QualityTransition>()
        transitions += monitor.ingest(observation(1, darkMetrics())).transitions
        transitions += monitor.ingest(observation(2, darkMetrics())).transitions
        transitions += monitor.ingest(observation(3, darkMetrics())).transitions
        assertEquals(1, transitions.size)
        assertEquals(QualityTransitionKind.ALERT, transitions.single().kind)

        transitions += monitor.ingest(observation(4, clearMetrics())).transitions
        transitions += monitor.ingest(observation(5, clearMetrics())).transitions
        assertEquals(2, transitions.size)
        assertEquals(QualityTransitionKind.RECOVERED, transitions.last().kind)
    }

    @Test
    fun `single transient dark sample never alerts`() {
        val monitor = FrameQualityMonitor(
            alertPersistenceNs = 2_000_000_000,
            recoveryPersistenceNs = 1_000_000_000,
        )

        val transitions = buildList {
            addAll(monitor.ingest(observation(1, clearMetrics())).transitions)
            addAll(monitor.ingest(observation(2, darkMetrics())).transitions)
            addAll(monitor.ingest(observation(3, clearMetrics())).transitions)
            addAll(monitor.ingest(observation(4, clearMetrics())).transitions)
        }

        assertTrue(transitions.isEmpty())
    }

    @Test
    fun `sharpness uses higher resolution Laplacian evidence`() {
        val width = 320
        val height = 240
        val smoothGradient = ByteArray(width * height) { index ->
            val x = index % width
            (x * 255 / (width - 1)).toByte()
        }
        val sharpStripes = ByteArray(width * height) { index ->
            val x = index % width
            if ((x / 4) % 2 == 0) 0 else 255.toByte()
        }
        val smooth = metrics(
            smoothGradient,
            width = width,
            height = height,
        )
        val sharp = metrics(
            sharpStripes,
            width = width,
            height = height,
        )

        assertTrue(smooth.standardDeviation > 0.04)
        assertTrue(smooth.laplacianVariance <= 0.0008)
        assertTrue(sharp.laplacianVariance >= 0.0015)
    }

    @Test
    fun `frozen-frame claim abstains without comparable camera clock`() {
        val monitor = FrameQualityMonitor(alertPersistenceNs = 0)
        val observation = observation(
            second = 1,
            metrics = clearMetrics(),
            frameDelta = 0.0,
            gyro = 1.0,
            clockComparable = false,
        )

        val result = monitor.ingest(observation)

        assertEquals(
            DefectAssessment.UNKNOWN,
            result.assessments[
                FrameDefect.FRAME_NOT_RESPONDING_TO_MOTION
            ],
        )
        assertTrue(result.transitions.none {
            it.defect == FrameDefect.FRAME_NOT_RESPONDING_TO_MOTION
        })
    }

    @Test
    fun `unchanged frames during fresh rotation alert when clocks compare`() {
        val monitor = FrameQualityMonitor(
            alertPersistenceNs = 1_000_000_000,
            recoveryPersistenceNs = 1_000_000_000,
        )
        val first = monitor.ingest(
            observation(
                second = 1,
                metrics = clearMetrics(),
                frameDelta = 0.0,
                gyro = 0.8,
                clockComparable = true,
            ),
        )
        val second = monitor.ingest(
            observation(
                second = 2,
                metrics = clearMetrics(),
                frameDelta = 0.0,
                gyro = 0.8,
                clockComparable = true,
            ),
        )

        assertTrue(first.transitions.isEmpty())
        assertEquals(
            FrameDefect.FRAME_NOT_RESPONDING_TO_MOTION,
            second.transitions.single().defect,
        )
    }

    @Test
    fun `persistent high frequency shake alerts`() {
        val monitor = FrameQualityMonitor(
            alertPersistenceNs = 2_000_000_000,
        )
        val transitions = (1L..3L).flatMap { second ->
            monitor.ingest(
                observation(
                    second = second,
                    metrics = clearMetrics(),
                    shakeRms = 1.10,
                    tiltDegrees = 20.0,
                ),
            ).transitions
        }

        assertTrue(transitions.any {
            it.defect == FrameDefect.CAMERA_SHAKE &&
                it.kind == QualityTransitionKind.ALERT
        })
    }

    @Test
    fun `persistent wrong camera angle alerts`() {
        val monitor = FrameQualityMonitor(
            alertPersistenceNs = 2_000_000_000,
        )
        val transitions = (1L..3L).flatMap { second ->
            monitor.ingest(
                observation(
                    second = second,
                    metrics = clearMetrics(),
                    shakeRms = 0.20,
                    tiltDegrees = 70.0,
                ),
            ).transitions
        }

        assertTrue(transitions.any {
            it.defect == FrameDefect.CAMERA_ANGLE &&
                it.kind == QualityTransitionKind.ALERT
        })
    }

    @Test
    fun `imu sampled during image analysis remains fresh`() {
        val monitor = FrameQualityMonitor(alertPersistenceNs = 0)
        val observedNs = 1_000_000_000L
        val result = monitor.ingest(
            FrameQualityObservation(
                observedAtElapsedRealtimeNs = observedNs,
                metrics = clearMetrics(),
                previousFrameDelta = 0.1,
                motion = MotionEvidence(
                    observedAtElapsedRealtimeNs =
                        observedNs + 20_000_000L,
                    gyroscopeMagnitudeRadPerSec = 0.0,
                    shakeRmsRadPerSec = 0.1,
                    cameraTiltFromGravityDegrees = 10.0,
                    motionWindowDurationNs = 1_000_000_000L,
                ),
                cameraTimestampComparableToElapsedRealtime = true,
            ),
        )

        assertEquals(
            DefectAssessment.CLEAR,
            result.assessments[FrameDefect.CAMERA_SHAKE],
        )
        assertEquals(
            DefectAssessment.CLEAR,
            result.assessments[FrameDefect.CAMERA_ANGLE],
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `out of order observations are rejected`() {
        val monitor = FrameQualityMonitor()
        monitor.ingest(observation(2, clearMetrics()))
        monitor.ingest(observation(1, clearMetrics()))
    }

    private fun metrics(
        bytes: ByteArray,
        width: Int = 64,
        height: Int = 48,
    ): LumaMetrics =
        LumaMetricsComputer.compute(
            buffer = ByteBuffer.wrap(bytes),
            width = width,
            height = height,
            rowStride = width,
            pixelStride = 1,
            sharpnessColumns = width.coerceAtMost(160),
            sharpnessRows = height.coerceAtMost(120),
        )

    private fun darkMetrics(): LumaMetrics = LumaMetrics(
        mean = 0.02,
        standardDeviation = 0.01,
        darkFraction = 0.98,
        brightFraction = 0.0,
        meanGradient = 0.01,
        laplacianVariance = 0.0,
        signature = doubleArrayOf(0.02),
    )

    private fun clearMetrics(): LumaMetrics = LumaMetrics(
        mean = 0.50,
        standardDeviation = 0.20,
        darkFraction = 0.05,
        brightFraction = 0.05,
        meanGradient = 0.10,
        laplacianVariance = 0.01,
        signature = doubleArrayOf(0.2, 0.8),
    )

    private fun observation(
        second: Long,
        metrics: LumaMetrics,
        frameDelta: Double? = 0.1,
        gyro: Double? = null,
        shakeRms: Double? = null,
        tiltDegrees: Double? = null,
        clockComparable: Boolean = true,
    ): FrameQualityObservation {
        val observedNs = second * 1_000_000_000L
        return FrameQualityObservation(
            observedAtElapsedRealtimeNs = observedNs,
            metrics = metrics,
            previousFrameDelta = frameDelta,
            motion = if (
                gyro != null ||
                shakeRms != null ||
                tiltDegrees != null
            ) {
                MotionEvidence(
                    observedAtElapsedRealtimeNs = observedNs - 10_000_000,
                    gyroscopeMagnitudeRadPerSec = gyro ?: 0.0,
                    shakeRmsRadPerSec = shakeRms,
                    cameraTiltFromGravityDegrees = tiltDegrees,
                    motionWindowDurationNs = 1_000_000_000L,
                )
            } else {
                null
            },
            cameraTimestampComparableToElapsedRealtime = clockComparable,
        )
    }
}
