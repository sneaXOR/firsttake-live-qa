package dev.firsttake.probe

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

data class ImuFinalizeReport(
    val finalized: Boolean,
    val error: String?,
)

data class CameraAnalysisFrameSample(
    val sensorTimestampNs: Long,
    val acceptedAtElapsedRealtimeNs: Long,
    val cameraTimestampComparableToElapsedRealtime: Boolean,
    val profile: AnalysisProfile,
    val width: Int,
    val height: Int,
)

data class CameraCaptureResultSample(
    val sensorTimestampNs: Long,
    val receivedAtElapsedRealtimeNs: Long,
    val frameNumber: Long,
    val sequenceId: Int,
    val zoomRatio: Double? = null,
    val activePhysicalCameraId: String? = null,
)

class ImuRecorder(
    private val context: Context,
    output: File,
    private val cameraClockContract: CameraClockContract,
    private val onMotion: (MotionEvidence) -> Unit = {},
    private val onHealthEvent: (ImuStreamHealthEvent) -> Unit = {},
    private val onWriteError: (String) -> Unit = {},
) : SensorEventListener {
    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val writer = FirstTakeMcapWriter.create(output)
    private val thread = HandlerThread("firsttake-imu-writer").apply { start() }
    private val handler = Handler(thread.looper)
    private val accepting = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val sequences = mutableMapOf<Int, Long>()
    private val completion = CompletableFuture<ImuFinalizeReport>()
    private val streamMonitor = ImuStreamMonitor()
    private val motionQualityEstimator = MotionQualityEstimator()
    private var firstWriteError: String? = null
    private var unixOffsetNs = 0L

    fun start(): List<String> {
        if (!accepting.compareAndSet(false, true)) {
            return emptyList()
        }
        val startAnchor = sampleClockAnchor()
        unixOffsetNs = startAnchor.wallClockUnixNs -
            startAnchor.elapsedRealtimeNs
        writeClockAnchor("START", startAnchor)

        val gyro =
            sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val accelerometer =
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        check(gyro != null) { "A gyroscope is required for RGB-IMU capture" }
        check(accelerometer != null) {
            "An accelerometer is required for RGB-IMU capture"
        }
        val registered = mutableListOf<String>()
        for (sensor in listOf(gyro, accelerometer)) {
            if (
                sensorManager.registerListener(
                    this,
                    sensor,
                    SensorManager.SENSOR_DELAY_FASTEST,
                    handler,
                )
            ) {
                registered += sensor.stringType
            } else {
                sensorManager.unregisterListener(this)
                throw IllegalStateException(
                    "Could not register required sensor ${sensor.stringType}",
                )
            }
        }
        return registered
    }

    fun stop(): CompletableFuture<ImuFinalizeReport> {
        if (!accepting.compareAndSet(true, false)) {
            return completion
        }
        sensorManager.unregisterListener(this)
        handler.post {
            var finalizeError: String? = firstWriteError
            try {
                writeClockAnchor("STOP", sampleClockAnchor())
            } catch (error: Exception) {
                finalizeError = finalizeError ?: safeMessage(error)
                reportWriteError(error)
            }
            try {
                writer.finish()
            } catch (error: Exception) {
                finalizeError = finalizeError ?: safeMessage(error)
                reportWriteError(error)
            } finally {
                completion.complete(
                    ImuFinalizeReport(
                        finalized = finalizeError == null,
                        error = finalizeError,
                    ),
                )
                thread.quitSafely()
            }
        }
        return completion
    }

    fun recordCameraAnalysisFrame(sample: CameraAnalysisFrameSample) {
        if (!accepting.get() || failed.get()) {
            return
        }
        handler.post {
            if (failed.get()) {
                return@post
            }
            val json = JSONObject()
                .put(
                    "schemaVersion",
                    "firsttake.camera-analysis-frame.v1",
                )
                .put("sensorTimestampNs", sample.sensorTimestampNs)
                .put(
                    "acceptedAtElapsedRealtimeNs",
                    sample.acceptedAtElapsedRealtimeNs,
                )
                .put(
                    "cameraTimestampComparableToElapsedRealtime",
                    sample.cameraTimestampComparableToElapsedRealtime,
                )
                .put("profile", sample.profile.name)
                .put("width", sample.width)
                .put("height", sample.height)
            try {
                writer.writeMessage(
                    channelId =
                        FirstTakeMcapLayout.CHANNEL_CAMERA_ANALYSIS_FRAME,
                    sequence = nextSequence(
                        FirstTakeMcapLayout.CHANNEL_CAMERA_ANALYSIS_FRAME,
                    ),
                    logTimeNs = sample.sensorTimestampNs + unixOffsetNs,
                    data = json.toString().toByteArray(Charsets.UTF_8),
                )
            } catch (error: Exception) {
                reportWriteError(error)
            }
        }
    }

    fun recordCaptureEvent(
        type: String,
        elapsedRealtimeNs: Long,
        payload: JSONObject = JSONObject(),
    ) {
        if (!accepting.get() || failed.get()) {
            return
        }
        handler.post {
            if (failed.get()) {
                return@post
            }
            val json = JSONObject()
                .put("schemaVersion", "firsttake.capture-event.v1")
                .put("type", type)
                .put("elapsedRealtimeNs", elapsedRealtimeNs)
                .put("payload", payload)
            try {
                writer.writeMessage(
                    channelId = FirstTakeMcapLayout.CHANNEL_CAPTURE_EVENT,
                    sequence = nextSequence(
                        FirstTakeMcapLayout.CHANNEL_CAPTURE_EVENT,
                    ),
                    logTimeNs = elapsedRealtimeNs + unixOffsetNs,
                    data = json.toString().toByteArray(Charsets.UTF_8),
                )
            } catch (error: Exception) {
                reportWriteError(error)
            }
        }
    }

    fun recordCameraCaptureResult(sample: CameraCaptureResultSample) {
        if (!accepting.get() || failed.get()) {
            return
        }
        handler.post {
            if (failed.get()) {
                return@post
            }
            val json = JSONObject()
                .put(
                    "schemaVersion",
                    "firsttake.camera-capture-result.v1",
                )
                .put("sensorTimestampNs", sample.sensorTimestampNs)
                .put(
                    "receivedAtElapsedRealtimeNs",
                    sample.receivedAtElapsedRealtimeNs,
                )
                .put("frameNumber", sample.frameNumber)
                .put("sequenceId", sample.sequenceId)
            sample.zoomRatio?.let { value ->
                json.put("zoomRatio", value)
            }
            sample.activePhysicalCameraId?.let { value ->
                json.put("activePhysicalCameraId", value)
            }
            try {
                writer.writeMessage(
                    channelId =
                        FirstTakeMcapLayout.CHANNEL_CAMERA_CAPTURE_RESULT,
                    sequence = nextSequence(
                        FirstTakeMcapLayout.CHANNEL_CAMERA_CAPTURE_RESULT,
                    ),
                    logTimeNs = sample.sensorTimestampNs + unixOffsetNs,
                    data = json.toString().toByteArray(Charsets.UTF_8),
                )
            } catch (error: Exception) {
                reportWriteError(error)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!accepting.get() || failed.get()) {
            return
        }
        val arrivalElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos()
        val json = JSONObject()
            .put("schemaVersion", "firsttake.imu.v1")
            .put("sensorType", event.sensor.stringType)
            .put("sensorTimestampNs", event.timestamp)
            .put("arrivalElapsedRealtimeNs", arrivalElapsedRealtimeNs)
            .put("accuracy", event.accuracy)
            .put("units", unitsFor(event.sensor))
            .put(
                "values",
                JSONArray(event.values.map { it.toDouble() }),
            )
        val channelId = channelFor(event.sensor) ?: return
        streamMonitor.observe(channelId, event.timestamp)?.let(onHealthEvent)
        if (event.values.size >= 3) {
            val values = DoubleArray(3) { index ->
                event.values[index].toDouble()
            }
            when (channelId) {
                FirstTakeMcapLayout.CHANNEL_ACCELEROMETER ->
                    motionQualityEstimator.ingestAccelerometer(
                        sensorTimestampNs = event.timestamp,
                        values = values,
                    )
                FirstTakeMcapLayout.CHANNEL_GYROSCOPE ->
                    onMotion(
                        motionQualityEstimator.ingestGyroscope(
                            sensorTimestampNs = event.timestamp,
                            arrivalElapsedRealtimeNs =
                                arrivalElapsedRealtimeNs,
                            values = values,
                        ),
                    )
            }
        }
        val logTimeNs = event.timestamp + unixOffsetNs
        try {
            writer.writeMessage(
                channelId = channelId,
                sequence = nextSequence(channelId),
                logTimeNs = logTimeNs,
                data = json.toString().toByteArray(Charsets.UTF_8),
            )
        } catch (error: Exception) {
            reportWriteError(error)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun writeClockAnchor(
        kind: String,
        anchor: ClockAnchorSample,
    ) {
        val json = JSONObject()
            .put("schemaVersion", "firsttake.clock-anchor.v1")
            .put("kind", kind)
            .put("wallClockUnixNs", anchor.wallClockUnixNs)
            .put("elapsedRealtimeNs", anchor.elapsedRealtimeNs)
            .put("uptimeNs", anchor.uptimeNs)
            .put("uncertaintyNs", anchor.uncertaintyNs)
            .put("bootCount", anchor.bootCount ?: JSONObject.NULL)
            .put(
                "cameraId",
                cameraClockContract.cameraId ?: JSONObject.NULL,
            )
            .put(
                "cameraTimestampSource",
                cameraClockContract.timestampSourceName,
            )
            .put(
                "cameraTimestampComparableToElapsedRealtime",
                cameraClockContract.comparableToElapsedRealtime,
            )
        writer.writeMessage(
            channelId = FirstTakeMcapLayout.CHANNEL_CLOCK_ANCHOR,
            sequence = nextSequence(
                FirstTakeMcapLayout.CHANNEL_CLOCK_ANCHOR,
            ),
            logTimeNs = anchor.wallClockUnixNs,
            data = json.toString().toByteArray(Charsets.UTF_8),
        )
        writer.sync()
    }

    private fun sampleClockAnchor(): ClockAnchorSample {
        val elapsedBeforeNs = SystemClock.elapsedRealtimeNanos()
        val wallClockUnixNs = System.currentTimeMillis() * 1_000_000L
        val uptimeNs = SystemClock.uptimeMillis() * 1_000_000L
        val elapsedAfterNs = SystemClock.elapsedRealtimeNanos()
        val elapsedMidpointNs =
            elapsedBeforeNs + (elapsedAfterNs - elapsedBeforeNs) / 2L
        val bootCount = try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.BOOT_COUNT,
            )
        } catch (_: Exception) {
            null
        }
        return ClockAnchorSample(
            wallClockUnixNs = wallClockUnixNs,
            elapsedRealtimeNs = elapsedMidpointNs,
            uptimeNs = uptimeNs,
            bootCount = bootCount,
            uncertaintyNs =
                (elapsedAfterNs - elapsedBeforeNs) / 2L +
                    WALL_CLOCK_QUANTIZATION_NS,
        )
    }

    private fun nextSequence(channelId: Int): Long {
        val current = sequences[channelId] ?: 0L
        sequences[channelId] = current + 1L
        return current
    }

    private fun channelFor(sensor: Sensor): Int? = when (sensor.type) {
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
        -> FirstTakeMcapLayout.CHANNEL_GYROSCOPE

        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
        -> FirstTakeMcapLayout.CHANNEL_ACCELEROMETER

        else -> null
    }

    private fun unitsFor(sensor: Sensor): String = when (sensor.type) {
        Sensor.TYPE_GYROSCOPE,
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
        -> "rad/s"

        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
        -> "m/s^2"

        else -> "unknown"
    }

    private fun reportWriteError(error: Exception) {
        if (failed.compareAndSet(false, true)) {
            firstWriteError = safeMessage(error)
            onWriteError(firstWriteError!!)
        }
    }

    private fun safeMessage(error: Exception): String =
        error.message ?: error.javaClass.simpleName

    private data class ClockAnchorSample(
        val wallClockUnixNs: Long,
        val elapsedRealtimeNs: Long,
        val uptimeNs: Long,
        val bootCount: Int?,
        val uncertaintyNs: Long,
    )

    private companion object {
        const val WALL_CLOCK_QUANTIZATION_NS = 500_000L
    }
}
