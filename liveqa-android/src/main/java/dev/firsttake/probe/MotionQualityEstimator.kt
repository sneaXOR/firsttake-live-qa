package dev.firsttake.probe

import java.util.ArrayDeque
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Extracts capture-quality motion signals from raw IMU samples.
 *
 * A low-pass gyroscope represents deliberate smooth motion. The residual
 * represents high-frequency shake, accumulated as a one-second RMS window.
 * A separately filtered accelerometer estimates the angle between the camera
 * optical axis (device Z) and gravity only while acceleration is plausible.
 */
class MotionQualityEstimator(
    private val shakeWindowNs: Long = 1_000_000_000L,
    private val minimumShakeWindowNs: Long = 800_000_000L,
) {
    private data class SquaredMotion(
        val sensorTimestampNs: Long,
        val squaredMagnitude: Double,
    )

    private val shakeWindow = ArrayDeque<SquaredMotion>()
    private var shakeSquaredSum = 0.0
    private var gyroLowPass: DoubleArray? = null
    private var previousGyroTimestampNs: Long? = null
    private var gravityLowPass: DoubleArray? = null
    private var previousAccelerometerTimestampNs: Long? = null
    private var cameraTiltFromGravityDegrees: Double? = null
    private var cameraTiltObservedAtSensorNs: Long? = null

    fun ingestAccelerometer(
        sensorTimestampNs: Long,
        values: DoubleArray,
    ) {
        require(values.size >= 3)
        val vector = values.copyOfRange(0, 3)
        val rawMagnitude = magnitude(vector)
        val previousTimestamp = previousAccelerometerTimestampNs
        previousAccelerometerTimestampNs = sensorTimestampNs
        if (rawMagnitude !in MIN_GRAVITY_MAGNITUDE..MAX_GRAVITY_MAGNITUDE) {
            return
        }
        val filtered = gravityLowPass
        if (filtered == null || previousTimestamp == null) {
            gravityLowPass = vector
        } else {
            val alpha = lowPassAlpha(
                sensorTimestampNs - previousTimestamp,
                GRAVITY_LOW_PASS_TAU_SECONDS,
            )
            for (index in 0..2) {
                filtered[index] += alpha * (vector[index] - filtered[index])
            }
        }
        val gravity = gravityLowPass ?: return
        val gravityMagnitude = magnitude(gravity)
        if (gravityMagnitude <= 1e-6) {
            return
        }
        val cosine = (
            kotlin.math.abs(gravity[2]) / gravityMagnitude
            ).coerceIn(0.0, 1.0)
        cameraTiltFromGravityDegrees = Math.toDegrees(acos(cosine))
        cameraTiltObservedAtSensorNs = sensorTimestampNs
    }

    fun ingestGyroscope(
        sensorTimestampNs: Long,
        arrivalElapsedRealtimeNs: Long,
        values: DoubleArray,
    ): MotionEvidence {
        require(values.size >= 3)
        val vector = values.copyOfRange(0, 3)
        val previousTimestamp = previousGyroTimestampNs
        previousGyroTimestampNs = sensorTimestampNs
        val filtered = gyroLowPass
        if (filtered == null || previousTimestamp == null) {
            gyroLowPass = vector.copyOf()
        } else {
            val alpha = lowPassAlpha(
                sensorTimestampNs - previousTimestamp,
                GYRO_LOW_PASS_TAU_SECONDS,
            )
            for (index in 0..2) {
                filtered[index] += alpha * (vector[index] - filtered[index])
            }
        }
        val lowPass = gyroLowPass ?: vector
        val residualSquared = (0..2).sumOf { index ->
            val residual = vector[index] - lowPass[index]
            residual * residual
        }
        shakeWindow.addLast(
            SquaredMotion(sensorTimestampNs, residualSquared),
        )
        shakeSquaredSum += residualSquared
        while (
            shakeWindow.isNotEmpty() &&
            sensorTimestampNs - shakeWindow.first.sensorTimestampNs >
            shakeWindowNs
        ) {
            shakeSquaredSum -= shakeWindow.removeFirst().squaredMagnitude
        }
        val windowDurationNs = if (shakeWindow.size >= 2) {
            sensorTimestampNs - shakeWindow.first.sensorTimestampNs
        } else {
            0L
        }
        val shakeRms = if (
            windowDurationNs >= minimumShakeWindowNs &&
            shakeWindow.isNotEmpty()
        ) {
            sqrt(
                (shakeSquaredSum / shakeWindow.size)
                    .coerceAtLeast(0.0),
            )
        } else {
            null
        }
        val tiltFresh = cameraTiltObservedAtSensorNs?.let {
            sensorTimestampNs - it in 0..MAX_TILT_AGE_NS
        } == true
        return MotionEvidence(
            observedAtElapsedRealtimeNs = arrivalElapsedRealtimeNs,
            gyroscopeMagnitudeRadPerSec = magnitude(vector),
            shakeRmsRadPerSec = shakeRms,
            cameraTiltFromGravityDegrees = if (tiltFresh) {
                cameraTiltFromGravityDegrees
            } else {
                null
            },
            motionWindowDurationNs = windowDurationNs,
        )
    }

    private fun lowPassAlpha(
        deltaNs: Long,
        tauSeconds: Double,
    ): Double {
        val deltaSeconds = (
            deltaNs.toDouble() / 1_000_000_000.0
            ).coerceIn(MIN_DELTA_SECONDS, MAX_DELTA_SECONDS)
        return deltaSeconds / (tauSeconds + deltaSeconds)
    }

    private fun magnitude(vector: DoubleArray): Double =
        sqrt(vector.sumOf { it * it })

    private companion object {
        const val GYRO_LOW_PASS_TAU_SECONDS = 0.25
        const val GRAVITY_LOW_PASS_TAU_SECONDS = 0.50
        const val MIN_DELTA_SECONDS = 0.0001
        const val MAX_DELTA_SECONDS = 0.10
        const val MIN_GRAVITY_MAGNITUDE = 7.0
        const val MAX_GRAVITY_MAGNITUDE = 13.0
        const val MAX_TILT_AGE_NS = 500_000_000L
    }
}
