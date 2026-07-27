package dev.firsttake.probe

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionQualityEstimatorTest {
    @Test
    fun stationaryTopDownDeviceIsStableAndPointsDown() {
        val estimator = MotionQualityEstimator()
        var evidence: MotionEvidence? = null
        for (index in 0..200) {
            val timestampNs = index * 10_000_000L
            estimator.ingestAccelerometer(
                sensorTimestampNs = timestampNs,
                values = doubleArrayOf(0.0, 0.0, 9.81),
            )
            evidence = estimator.ingestGyroscope(
                sensorTimestampNs = timestampNs,
                arrivalElapsedRealtimeNs = timestampNs,
                values = doubleArrayOf(0.0, 0.0, 0.0),
            )
        }

        assertNotNull(evidence?.shakeRmsRadPerSec)
        assertTrue(evidence!!.shakeRmsRadPerSec!! < 0.01)
        assertTrue(evidence.cameraTiltFromGravityDegrees!! < 1.0)
    }

    @Test
    fun alternatingAngularMotionProducesHighFrequencyShake() {
        val estimator = MotionQualityEstimator()
        var evidence: MotionEvidence? = null
        for (index in 0..200) {
            val timestampNs = index * 10_000_000L
            estimator.ingestAccelerometer(
                sensorTimestampNs = timestampNs,
                values = doubleArrayOf(0.0, 0.0, 9.81),
            )
            val direction = if (index % 2 == 0) 1.0 else -1.0
            evidence = estimator.ingestGyroscope(
                sensorTimestampNs = timestampNs,
                arrivalElapsedRealtimeNs = timestampNs,
                values = doubleArrayOf(2.0 * direction, 0.0, 0.0),
            )
        }

        assertTrue(evidence!!.shakeRmsRadPerSec!! > 1.5)
    }

    @Test
    fun verticalCameraReportsNinetyDegreeTilt() {
        val estimator = MotionQualityEstimator()
        var evidence: MotionEvidence? = null
        for (index in 0..100) {
            val timestampNs = index * 10_000_000L
            estimator.ingestAccelerometer(
                sensorTimestampNs = timestampNs,
                values = doubleArrayOf(0.0, 9.81, 0.0),
            )
            evidence = estimator.ingestGyroscope(
                sensorTimestampNs = timestampNs,
                arrivalElapsedRealtimeNs = timestampNs,
                values = doubleArrayOf(0.0, 0.0, 0.0),
            )
        }

        assertTrue(
            evidence!!.cameraTiltFromGravityDegrees!! in 89.0..90.0,
        )
    }
}
