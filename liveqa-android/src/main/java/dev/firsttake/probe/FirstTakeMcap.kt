package dev.firsttake.probe

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

object FirstTakeMcapLayout {
    const val SCHEMA_IMU = 1
    const val SCHEMA_CLOCK_ANCHOR = 2
    const val SCHEMA_CAMERA_ANALYSIS_FRAME = 3
    const val SCHEMA_CAPTURE_EVENT = 4
    const val SCHEMA_CAMERA_CAPTURE_RESULT = 5

    const val CHANNEL_GYROSCOPE = 1
    const val CHANNEL_ACCELEROMETER = 2
    const val CHANNEL_CLOCK_ANCHOR = 3
    const val CHANNEL_CAMERA_ANALYSIS_FRAME = 4
    const val CHANNEL_CAPTURE_EVENT = 5
    const val CHANNEL_CAMERA_CAPTURE_RESULT = 6

    const val TOPIC_GYROSCOPE = "/imu/gyroscope"
    const val TOPIC_ACCELEROMETER = "/imu/accelerometer"
    const val TOPIC_CLOCK_ANCHOR = "/firsttake/clock_anchor"
    const val TOPIC_CAMERA_ANALYSIS_FRAME =
        "/firsttake/camera_analysis_frame"
    const val TOPIC_CAPTURE_EVENT = "/firsttake/capture_event"
    const val TOPIC_CAMERA_CAPTURE_RESULT =
        "/firsttake/camera_capture_result"

    val imuJsonSchema: ByteArray = """
        {
          "${'$'}schema":"https://json-schema.org/draft/2020-12/schema",
          "title":"dev.firsttake.ImuSample.v1",
          "type":"object",
          "required":[
            "schemaVersion",
            "sensorType",
            "sensorTimestampNs",
            "arrivalElapsedRealtimeNs",
            "accuracy",
            "units",
            "values"
          ],
          "properties":{
            "schemaVersion":{"const":"firsttake.imu.v1"},
            "sensorType":{"type":"string"},
            "sensorTimestampNs":{"type":"integer","minimum":0},
            "arrivalElapsedRealtimeNs":{"type":"integer","minimum":0},
            "accuracy":{"type":"integer"},
            "units":{"type":"string"},
            "values":{
              "type":"array",
              "minItems":3,
              "maxItems":6,
              "items":{"type":"number"}
            }
          },
          "additionalProperties":false
        }
    """.trimIndent().toByteArray(StandardCharsets.UTF_8)

    val clockAnchorJsonSchema: ByteArray = """
        {
          "${'$'}schema":"https://json-schema.org/draft/2020-12/schema",
          "title":"dev.firsttake.ClockAnchor.v1",
          "type":"object",
          "required":[
            "schemaVersion",
            "kind",
            "wallClockUnixNs",
            "elapsedRealtimeNs",
            "uptimeNs",
            "uncertaintyNs",
            "cameraTimestampSource",
            "cameraTimestampComparableToElapsedRealtime"
          ],
          "properties":{
            "schemaVersion":{"const":"firsttake.clock-anchor.v1"},
            "kind":{"enum":["START","STOP"]},
            "wallClockUnixNs":{"type":"integer","minimum":0},
            "elapsedRealtimeNs":{"type":"integer","minimum":0},
            "uptimeNs":{"type":"integer","minimum":0},
            "bootCount":{"type":["integer","null"]},
            "uncertaintyNs":{"type":"integer","minimum":0},
            "cameraId":{"type":["string","null"]},
            "cameraTimestampSource":{"type":"string"},
            "cameraTimestampComparableToElapsedRealtime":{"type":"boolean"}
          },
          "additionalProperties":false
        }
    """.trimIndent().toByteArray(StandardCharsets.UTF_8)

    val cameraAnalysisFrameJsonSchema: ByteArray = """
        {
          "${'$'}schema":"https://json-schema.org/draft/2020-12/schema",
          "title":"dev.firsttake.CameraAnalysisFrame.v1",
          "type":"object",
          "required":[
            "schemaVersion",
            "sensorTimestampNs",
            "acceptedAtElapsedRealtimeNs",
            "cameraTimestampComparableToElapsedRealtime",
            "profile",
            "width",
            "height"
          ],
          "properties":{
            "schemaVersion":{"const":"firsttake.camera-analysis-frame.v1"},
            "sensorTimestampNs":{"type":"integer","minimum":0},
            "acceptedAtElapsedRealtimeNs":{"type":"integer","minimum":0},
            "cameraTimestampComparableToElapsedRealtime":{"type":"boolean"},
            "profile":{"type":"string"},
            "width":{"type":"integer","minimum":1},
            "height":{"type":"integer","minimum":1}
          },
          "additionalProperties":false
        }
    """.trimIndent().toByteArray(StandardCharsets.UTF_8)

    val captureEventJsonSchema: ByteArray = """
        {
          "${'$'}schema":"https://json-schema.org/draft/2020-12/schema",
          "title":"dev.firsttake.CaptureEvent.v1",
          "type":"object",
          "required":[
            "schemaVersion",
            "type",
            "elapsedRealtimeNs",
            "payload"
          ],
          "properties":{
            "schemaVersion":{"const":"firsttake.capture-event.v1"},
            "type":{"type":"string"},
            "elapsedRealtimeNs":{"type":"integer","minimum":0},
            "payload":{"type":"object"}
          },
          "additionalProperties":false
        }
    """.trimIndent().toByteArray(StandardCharsets.UTF_8)

    val cameraCaptureResultJsonSchema: ByteArray = """
        {
          "${'$'}schema":"https://json-schema.org/draft/2020-12/schema",
          "title":"dev.firsttake.CameraCaptureResult.v1",
          "type":"object",
          "required":[
            "schemaVersion",
            "sensorTimestampNs",
            "receivedAtElapsedRealtimeNs",
            "frameNumber",
            "sequenceId"
          ],
          "properties":{
            "schemaVersion":{"const":"firsttake.camera-capture-result.v1"},
            "sensorTimestampNs":{"type":"integer","minimum":0},
            "receivedAtElapsedRealtimeNs":{"type":"integer","minimum":0},
            "frameNumber":{"type":"integer","minimum":0},
            "sequenceId":{"type":"integer"},
            "zoomRatio":{"type":"number","exclusiveMinimum":0},
            "activePhysicalCameraId":{"type":"string"}
          },
          "additionalProperties":false
        }
    """.trimIndent().toByteArray(StandardCharsets.UTF_8)
}

/**
 * Minimal unchunked MCAP writer for the high-rate mobile sensor path.
 *
 * Each message is emitted as one complete record. The file is valid after
 * [finish]; after process death, [FirstTakeMcapRecovery] can copy only the
 * verified complete record prefix into a new finalized file.
 */
class FirstTakeMcapWriter private constructor(
    private val file: File,
    private val output: FileOutputStream,
) : AutoCloseable {
    private var finished = false
    private var messageCountSinceSync = 0

    init {
        output.write(MCAP_MAGIC)
        writeRecord(OP_HEADER) {
            writeString("")
            writeString("firsttake-android/0.1.0")
        }
        writeRecord(OP_SCHEMA) {
            writeUInt16(FirstTakeMcapLayout.SCHEMA_IMU)
            writeString("dev.firsttake.ImuSample.v1")
            writeString("jsonschema")
            writeLengthPrefixedBytes(FirstTakeMcapLayout.imuJsonSchema)
        }
        writeRecord(OP_SCHEMA) {
            writeUInt16(FirstTakeMcapLayout.SCHEMA_CLOCK_ANCHOR)
            writeString("dev.firsttake.ClockAnchor.v1")
            writeString("jsonschema")
            writeLengthPrefixedBytes(FirstTakeMcapLayout.clockAnchorJsonSchema)
        }
        writeRecord(OP_SCHEMA) {
            writeUInt16(FirstTakeMcapLayout.SCHEMA_CAMERA_ANALYSIS_FRAME)
            writeString("dev.firsttake.CameraAnalysisFrame.v1")
            writeString("jsonschema")
            writeLengthPrefixedBytes(
                FirstTakeMcapLayout.cameraAnalysisFrameJsonSchema,
            )
        }
        writeRecord(OP_SCHEMA) {
            writeUInt16(FirstTakeMcapLayout.SCHEMA_CAPTURE_EVENT)
            writeString("dev.firsttake.CaptureEvent.v1")
            writeString("jsonschema")
            writeLengthPrefixedBytes(
                FirstTakeMcapLayout.captureEventJsonSchema,
            )
        }
        writeRecord(OP_SCHEMA) {
            writeUInt16(FirstTakeMcapLayout.SCHEMA_CAMERA_CAPTURE_RESULT)
            writeString("dev.firsttake.CameraCaptureResult.v1")
            writeString("jsonschema")
            writeLengthPrefixedBytes(
                FirstTakeMcapLayout.cameraCaptureResultJsonSchema,
            )
        }
        writeChannel(
            id = FirstTakeMcapLayout.CHANNEL_GYROSCOPE,
            schemaId = FirstTakeMcapLayout.SCHEMA_IMU,
            topic = FirstTakeMcapLayout.TOPIC_GYROSCOPE,
            metadata = mapOf(
                "units" to "rad/s",
                "raw_timestamp_basis" to "android_elapsed_realtime_ns",
                "log_time_basis" to "derived_unix_ns",
            ),
        )
        writeChannel(
            id = FirstTakeMcapLayout.CHANNEL_CAMERA_ANALYSIS_FRAME,
            schemaId = FirstTakeMcapLayout.SCHEMA_CAMERA_ANALYSIS_FRAME,
            topic = FirstTakeMcapLayout.TOPIC_CAMERA_ANALYSIS_FRAME,
            metadata = mapOf(
                "raw_timestamp_basis" to "camera_sensor_ns",
                "log_time_basis" to "derived_unix_ns",
            ),
        )
        writeChannel(
            id = FirstTakeMcapLayout.CHANNEL_CAPTURE_EVENT,
            schemaId = FirstTakeMcapLayout.SCHEMA_CAPTURE_EVENT,
            topic = FirstTakeMcapLayout.TOPIC_CAPTURE_EVENT,
            metadata = mapOf(
                "raw_timestamp_basis" to "android_elapsed_realtime_ns",
                "log_time_basis" to "derived_unix_ns",
            ),
        )
        writeChannel(
            id = FirstTakeMcapLayout.CHANNEL_CAMERA_CAPTURE_RESULT,
            schemaId = FirstTakeMcapLayout.SCHEMA_CAMERA_CAPTURE_RESULT,
            topic = FirstTakeMcapLayout.TOPIC_CAMERA_CAPTURE_RESULT,
            metadata = mapOf(
                "raw_timestamp_basis" to "camera_sensor_ns",
                "log_time_basis" to "derived_unix_ns",
            ),
        )
        writeChannel(
            id = FirstTakeMcapLayout.CHANNEL_ACCELEROMETER,
            schemaId = FirstTakeMcapLayout.SCHEMA_IMU,
            topic = FirstTakeMcapLayout.TOPIC_ACCELEROMETER,
            metadata = mapOf(
                "units" to "m/s^2",
                "raw_timestamp_basis" to "android_elapsed_realtime_ns",
                "log_time_basis" to "derived_unix_ns",
            ),
        )
        writeChannel(
            id = FirstTakeMcapLayout.CHANNEL_CLOCK_ANCHOR,
            schemaId = FirstTakeMcapLayout.SCHEMA_CLOCK_ANCHOR,
            topic = FirstTakeMcapLayout.TOPIC_CLOCK_ANCHOR,
            metadata = mapOf(
                "log_time_basis" to "unix_ns",
            ),
        )
        sync()
    }

    @Synchronized
    fun writeMessage(
        channelId: Int,
        sequence: Long,
        logTimeNs: Long,
        publishTimeNs: Long = logTimeNs,
        data: ByteArray,
    ) {
        check(!finished) { "MCAP writer is finished" }
        require(
            channelId in setOf(
                FirstTakeMcapLayout.CHANNEL_GYROSCOPE,
                FirstTakeMcapLayout.CHANNEL_ACCELEROMETER,
                FirstTakeMcapLayout.CHANNEL_CLOCK_ANCHOR,
                FirstTakeMcapLayout.CHANNEL_CAMERA_ANALYSIS_FRAME,
                FirstTakeMcapLayout.CHANNEL_CAPTURE_EVENT,
                FirstTakeMcapLayout.CHANNEL_CAMERA_CAPTURE_RESULT,
            ),
        ) { "Unknown FirstTake channel: $channelId" }
        require(sequence in 0..UINT32_MAX) {
            "sequence must fit uint32"
        }
        require(logTimeNs >= 0) { "logTimeNs must be non-negative" }
        require(publishTimeNs >= 0) { "publishTimeNs must be non-negative" }
        require(data.size <= MAX_MESSAGE_BYTES) {
            "message exceeds $MAX_MESSAGE_BYTES bytes"
        }

        writeRecord(OP_MESSAGE) {
            writeUInt16(channelId)
            writeUInt32(sequence)
            writeUInt64(logTimeNs)
            writeUInt64(publishTimeNs)
            write(data)
        }
        messageCountSinceSync += 1
        if (messageCountSinceSync >= SYNC_EVERY_MESSAGES) {
            sync()
        }
    }

    @Synchronized
    fun sync() {
        check(!finished) { "MCAP writer is finished" }
        output.flush()
        output.fd.sync()
        messageCountSinceSync = 0
    }

    @Synchronized
    fun finish() {
        if (finished) {
            return
        }
        try {
            writeRecord(OP_DATA_END) {
                writeUInt32(0)
            }
            writeRecord(OP_FOOTER) {
                writeUInt64(0)
                writeUInt64(0)
                writeUInt32(0)
            }
            output.write(MCAP_MAGIC)
            output.flush()
            output.fd.sync()
        } finally {
            finished = true
            output.close()
        }
    }

    override fun close() = finish()

    private fun writeChannel(
        id: Int,
        schemaId: Int,
        topic: String,
        metadata: Map<String, String>,
    ) {
        writeRecord(OP_CHANNEL) {
            writeUInt16(id)
            writeUInt16(schemaId)
            writeString(topic)
            writeString("json")
            writeStringMap(metadata)
        }
    }

    private fun writeRecord(
        opcode: Int,
        writeContent: LittleEndianContent.() -> Unit,
    ) {
        val content = LittleEndianContent().apply(writeContent).toByteArray()
        val envelope = LittleEndianContent()
            .apply {
                writeUInt8(opcode)
                writeUInt64(content.size.toLong())
                write(content)
            }
            .toByteArray()
        output.write(envelope)
    }

    companion object {
        fun create(file: File): FirstTakeMcapWriter {
            file.parentFile?.mkdirs()
            require(!file.exists() || file.length() == 0L) {
                "Refusing to overwrite non-empty MCAP: ${file.absolutePath}"
            }
            return FirstTakeMcapWriter(
                file = file,
                output = FileOutputStream(file, false),
            )
        }

        private const val SYNC_EVERY_MESSAGES = 256
        private const val MAX_MESSAGE_BYTES = 1 * 1024 * 1024
        private const val UINT32_MAX = 0xffff_ffffL
    }
}

enum class McapFileState {
    FINALIZED_VALID,
    RECOVERABLE_PREFIX,
    EMPTY,
    CORRUPT,
}

data class McapRecordBoundary(
    val opcode: Int,
    val startOffset: Long,
    val endOffset: Long,
)

data class McapPrefixReport(
    val state: McapFileState,
    val records: List<McapRecordBoundary>,
    val messageCount: Int,
    val verifiedDataPrefixBytes: Long,
    val ignoredTailBytes: Long,
    val errors: List<String>,
)

object FirstTakeMcapRecovery {
    fun scan(file: File): McapPrefixReport {
        if (!file.exists() || file.length() == 0L) {
            return McapPrefixReport(
                state = McapFileState.EMPTY,
                records = emptyList(),
                messageCount = 0,
                verifiedDataPrefixBytes = 0,
                ignoredTailBytes = 0,
                errors = emptyList(),
            )
        }
        if (!file.isFile) {
            return corrupt(file.length(), "MCAP path is not a regular file")
        }
        val length = file.length()
        if (length < MCAP_MAGIC.size) {
            return corrupt(length, "leading MCAP magic is incomplete")
        }

        RandomAccessFile(file, "r").use { input ->
            val magic = ByteArray(MCAP_MAGIC.size)
            input.readFully(magic)
            if (!magic.contentEquals(MCAP_MAGIC)) {
                return corrupt(length, "leading MCAP magic mismatch")
            }

            val records = mutableListOf<McapRecordBoundary>()
            val errors = mutableListOf<String>()
            var cursor = MCAP_MAGIC.size.toLong()
            var messageCount = 0
            var sawDataEnd = false
            var sawFooter = false
            var finalized = false
            var verifiedDataPrefixBytes = cursor

            while (cursor < length) {
                val remaining = length - cursor
                if (sawFooter) {
                    if (remaining == MCAP_MAGIC.size.toLong()) {
                        input.seek(cursor)
                        val trailingMagic = ByteArray(MCAP_MAGIC.size)
                        input.readFully(trailingMagic)
                        if (trailingMagic.contentEquals(MCAP_MAGIC)) {
                            finalized = true
                            cursor = length
                        } else {
                            errors += "trailing MCAP magic mismatch"
                        }
                    }
                    break
                }
                if (remaining < RECORD_ENVELOPE_BYTES) {
                    break
                }

                input.seek(cursor)
                val opcode = input.readUnsignedByte()
                val contentLength = readUInt64AsLong(input)
                if (contentLength == null) {
                    errors += "record at $cursor has unsupported uint64 length"
                    break
                }
                if (contentLength > MAX_RECOVERY_RECORD_BYTES) {
                    errors +=
                        "record at $cursor exceeds recovery size limit"
                    break
                }
                val recordEnd = cursor + RECORD_ENVELOPE_BYTES + contentLength
                if (recordEnd < cursor || recordEnd > length) {
                    break
                }

                val index = records.size
                if (index == 0 && opcode != OP_HEADER) {
                    errors += "first record is not Header"
                    break
                }
                if (index > 0 && opcode == OP_HEADER) {
                    errors += "Header appears more than once"
                    break
                }
                if (sawDataEnd && opcode != OP_FOOTER) {
                    errors += "record after DataEnd is not Footer"
                    break
                }
                if (!sawDataEnd && opcode == OP_FOOTER) {
                    errors += "Footer appears before DataEnd"
                    break
                }

                records += McapRecordBoundary(
                    opcode = opcode,
                    startOffset = cursor,
                    endOffset = recordEnd,
                )
                when (opcode) {
                    OP_HEADER -> Unit
                    OP_MESSAGE -> messageCount += 1
                    OP_DATA_END -> {
                        if (contentLength != 4L) {
                            errors += "DataEnd has invalid content length"
                            break
                        }
                        sawDataEnd = true
                    }

                    OP_FOOTER -> {
                        if (contentLength != 20L) {
                            errors += "Footer has invalid content length"
                            break
                        }
                        sawFooter = true
                    }

                    else -> {
                        if (opcode !in ALLOWED_FIRSTTAKE_DATA_OPCODES) {
                            errors += "unsupported opcode 0x${opcode.toString(16)}"
                            break
                        }
                    }
                }
                if (!sawDataEnd) {
                    verifiedDataPrefixBytes = recordEnd
                }
                cursor = recordEnd
            }

            val ignoredTailBytes = length - cursor
            val state = when {
                errors.isNotEmpty() -> McapFileState.CORRUPT
                finalized && cursor == length -> McapFileState.FINALIZED_VALID
                records.isEmpty() -> McapFileState.CORRUPT
                else -> McapFileState.RECOVERABLE_PREFIX
            }
            return McapPrefixReport(
                state = state,
                records = records,
                messageCount = messageCount,
                verifiedDataPrefixBytes = verifiedDataPrefixBytes,
                ignoredTailBytes = ignoredTailBytes,
                errors = errors,
            )
        }
    }

    /**
     * Creates a new valid, unindexed MCAP from the complete data-record prefix.
     * The source is never modified and an existing destination is never
     * overwritten.
     */
    fun recoverTo(source: File, destination: File): McapPrefixReport {
        require(source.canonicalFile != destination.canonicalFile) {
            "Recovery destination must differ from source"
        }
        require(!destination.exists()) {
            "Refusing to overwrite recovery destination"
        }
        val sourceReport = scan(source)
        require(sourceReport.state == McapFileState.RECOVERABLE_PREFIX) {
            "Source is not a recoverable live MCAP: ${sourceReport.state}"
        }
        require(sourceReport.records.firstOrNull()?.opcode == OP_HEADER) {
            "Recoverable prefix has no Header"
        }

        destination.parentFile?.mkdirs()
        FileInputStream(source).use { input ->
            FileOutputStream(destination, false).use { output ->
                copyExactly(
                    input = input,
                    output = output,
                    byteCount = sourceReport.verifiedDataPrefixBytes,
                )
                writeRecordTo(output, OP_DATA_END) {
                    writeUInt32(0)
                }
                writeRecordTo(output, OP_FOOTER) {
                    writeUInt64(0)
                    writeUInt64(0)
                    writeUInt32(0)
                }
                output.write(MCAP_MAGIC)
                output.flush()
                output.fd.sync()
            }
        }
        val recoveredReport = scan(destination)
        require(recoveredReport.state == McapFileState.FINALIZED_VALID) {
            "Recovered MCAP did not validate: ${recoveredReport.errors}"
        }
        return recoveredReport
    }

    private fun copyExactly(
        input: FileInputStream,
        output: FileOutputStream,
        byteCount: Long,
    ) {
        var remaining = byteCount
        val buffer = ByteArray(128 * 1024)
        while (remaining > 0) {
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            val count = input.read(buffer, 0, requested)
            check(count > 0) { "Source ended before verified prefix boundary" }
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun writeRecordTo(
        output: FileOutputStream,
        opcode: Int,
        writeContent: LittleEndianContent.() -> Unit,
    ) {
        val content = LittleEndianContent().apply(writeContent).toByteArray()
        val envelope = LittleEndianContent().apply {
            writeUInt8(opcode)
            writeUInt64(content.size.toLong())
            write(content)
        }.toByteArray()
        output.write(envelope)
    }

    private fun corrupt(length: Long, error: String): McapPrefixReport =
        McapPrefixReport(
            state = McapFileState.CORRUPT,
            records = emptyList(),
            messageCount = 0,
            verifiedDataPrefixBytes = 0,
            ignoredTailBytes = length,
            errors = listOf(error),
        )
}

private class LittleEndianContent {
    private val output = ByteArrayOutputStream()

    fun write(bytes: ByteArray) {
        output.write(bytes)
    }

    fun writeUInt8(value: Int) {
        require(value in 0..0xff)
        output.write(value)
    }

    fun writeUInt16(value: Int) {
        require(value in 0..0xffff)
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    fun writeUInt32(value: Long) {
        require(value in 0..0xffff_ffffL)
        repeat(4) { shift ->
            output.write(((value ushr (shift * 8)) and 0xff).toInt())
        }
    }

    fun writeUInt64(value: Long) {
        require(value >= 0) {
            "FirstTake currently supports non-negative uint64 values"
        }
        repeat(8) { shift ->
            output.write(((value ushr (shift * 8)) and 0xff).toInt())
        }
    }

    fun writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeUInt32(bytes.size.toLong())
        write(bytes)
    }

    fun writeLengthPrefixedBytes(value: ByteArray) {
        writeUInt32(value.size.toLong())
        write(value)
    }

    fun writeStringMap(value: Map<String, String>) {
        val entries = LittleEndianContent()
        value.toSortedMap().forEach { (key, mapValue) ->
            entries.writeString(key)
            entries.writeString(mapValue)
        }
        val bytes = entries.toByteArray()
        writeUInt32(bytes.size.toLong())
        write(bytes)
    }

    fun toByteArray(): ByteArray = output.toByteArray()
}

private fun readUInt64AsLong(input: RandomAccessFile): Long? {
    var value = 0L
    repeat(8) { shift ->
        val byte = input.readUnsignedByte().toLong()
        if (shift == 7 && byte > 0x7f) {
            return null
        }
        value = value or (byte shl (shift * 8))
    }
    return value
}

private val MCAP_MAGIC = byteArrayOf(
    0x89.toByte(),
    'M'.code.toByte(),
    'C'.code.toByte(),
    'A'.code.toByte(),
    'P'.code.toByte(),
    '0'.code.toByte(),
    '\r'.code.toByte(),
    '\n'.code.toByte(),
)

private const val RECORD_ENVELOPE_BYTES = 9L
private const val MAX_RECOVERY_RECORD_BYTES = 16L * 1024L * 1024L
private const val OP_HEADER = 0x01
private const val OP_FOOTER = 0x02
private const val OP_SCHEMA = 0x03
private const val OP_CHANNEL = 0x04
private const val OP_MESSAGE = 0x05
private const val OP_DATA_END = 0x0f
private val ALLOWED_FIRSTTAKE_DATA_OPCODES = setOf(
    OP_SCHEMA,
    OP_CHANNEL,
    OP_MESSAGE,
)
