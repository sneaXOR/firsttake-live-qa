package dev.firsttake.probe

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class FirstTakeMcapTest {
    @Test
    fun `finished writer produces a structurally valid MCAP`() {
        val file = tempFile("finished")
        FirstTakeMcapWriter.create(file).use { writer ->
            writer.writeMessage(
                channelId = FirstTakeMcapLayout.CHANNEL_CLOCK_ANCHOR,
                sequence = 0,
                logTimeNs = 1_700_000_000_000_000_000L,
                data = """{"kind":"START"}"""
                    .toByteArray(StandardCharsets.UTF_8),
            )
            writer.writeMessage(
                channelId = FirstTakeMcapLayout.CHANNEL_GYROSCOPE,
                sequence = 0,
                logTimeNs = 1_700_000_000_001_000_000L,
                data = """{"values":[0.1,0.2,0.3]}"""
                    .toByteArray(StandardCharsets.UTF_8),
            )
        }

        val report = FirstTakeMcapRecovery.scan(file)

        assertEquals(McapFileState.FINALIZED_VALID, report.state)
        assertEquals(2, report.messageCount)
        assertEquals(0L, report.ignoredTailBytes)
        assertTrue(report.errors.isEmpty())
    }

    @Test
    fun `live complete-record prefix can be recovered into a new file`() {
        val source = tempFile("live")
        val destination = File(source.parentFile, "recovered.mcap")
        val writer = FirstTakeMcapWriter.create(source)
        writer.writeMessage(
            channelId = FirstTakeMcapLayout.CHANNEL_ACCELEROMETER,
            sequence = 0,
            logTimeNs = 1_700_000_000_000_000_000L,
            data = """{"values":[1,2,3]}""".toByteArray(),
        )
        writer.sync()

        val liveCopy = File(source.parentFile, "killed-copy.mcap")
        source.copyTo(liveCopy)
        writer.finish()

        val liveReport = FirstTakeMcapRecovery.scan(liveCopy)
        assertEquals(McapFileState.RECOVERABLE_PREFIX, liveReport.state)
        assertEquals(1, liveReport.messageCount)
        val sourceBytesBeforeRecovery = liveCopy.readBytes()

        val recovered = FirstTakeMcapRecovery.recoverTo(
            source = liveCopy,
            destination = destination,
        )
        assertEquals(McapFileState.FINALIZED_VALID, recovered.state)
        assertEquals(1, recovered.messageCount)
        assertArrayEquals(sourceBytesBeforeRecovery, liveCopy.readBytes())
    }

    @Test
    fun `torn final message is excluded from recovered copy`() {
        val finalized = tempFile("torn-source")
        FirstTakeMcapWriter.create(finalized).use { writer ->
            repeat(2) { index ->
                writer.writeMessage(
                    channelId = FirstTakeMcapLayout.CHANNEL_GYROSCOPE,
                    sequence = index.toLong(),
                    logTimeNs = 1000L + index,
                    data = """{"sample":$index}""".toByteArray(),
                )
            }
        }
        val fullReport = FirstTakeMcapRecovery.scan(finalized)
        val secondMessage = fullReport.records
            .filter { it.opcode == 0x05 }
            .last()
        val torn = File(finalized.parentFile, "torn.mcap")
        val bytes = finalized.readBytes()
        torn.writeBytes(
            bytes.copyOf((secondMessage.startOffset + 12).toInt()),
        )

        val tornReport = FirstTakeMcapRecovery.scan(torn)
        assertEquals(McapFileState.RECOVERABLE_PREFIX, tornReport.state)
        assertEquals(1, tornReport.messageCount)
        assertTrue(tornReport.ignoredTailBytes > 0)

        val recovered = File(finalized.parentFile, "torn-recovered.mcap")
        val recoveredReport = FirstTakeMcapRecovery.recoverTo(torn, recovered)
        assertEquals(McapFileState.FINALIZED_VALID, recoveredReport.state)
        assertEquals(1, recoveredReport.messageCount)
    }

    @Test
    fun `bad leading magic is corrupt`() {
        val file = tempFile("bad-magic")
        file.writeBytes(ByteArray(64) { 0x42 })

        val report = FirstTakeMcapRecovery.scan(file)

        assertEquals(McapFileState.CORRUPT, report.state)
        assertTrue(report.errors.single().contains("magic"))
    }

    @Test
    fun `writer refuses to overwrite and recovery refuses in-place mutation`() {
        val file = tempFile("no-overwrite")
        file.writeText("existing")

        assertThrows(IllegalArgumentException::class.java) {
            FirstTakeMcapWriter.create(file)
        }

        val live = tempFile("in-place")
        val writer = FirstTakeMcapWriter.create(live)
        writer.writeMessage(
            channelId = FirstTakeMcapLayout.CHANNEL_GYROSCOPE,
            sequence = 0,
            logTimeNs = 1,
            data = byteArrayOf(1),
        )
        writer.sync()
        val copy = File(live.parentFile, "in-place-copy.mcap")
        live.copyTo(copy)
        writer.finish()

        assertThrows(IllegalArgumentException::class.java) {
            FirstTakeMcapRecovery.recoverTo(copy, copy)
        }
    }

    @Test
    fun `test artifact for official cross-language reader`() {
        val outputDirectory = File(
            System.getProperty("user.dir"),
            "build/test-artifacts",
        ).apply { mkdirs() }
        val output = File(outputDirectory, "firsttake-kotlin.mcap")
        if (output.exists()) {
            assertTrue(output.delete())
        }
        FirstTakeMcapWriter.create(output).use { writer ->
            writer.writeMessage(
                channelId = FirstTakeMcapLayout.CHANNEL_CLOCK_ANCHOR,
                sequence = 0,
                logTimeNs = 1_700_000_000_000_000_000L,
                data = """
                    {
                      "schemaVersion":"firsttake.clock-anchor.v1",
                      "kind":"START",
                      "wallClockUnixNs":1700000000000000000,
                      "elapsedRealtimeNs":1000000000,
                      "uptimeNs":1000000000,
                      "bootCount":4,
                      "uncertaintyNs":500000,
                      "cameraId":"0",
                      "cameraTimestampSource":"REALTIME",
                      "cameraTimestampComparableToElapsedRealtime":true
                    }
                """.trimIndent().toByteArray(),
            )
            writer.writeMessage(
                channelId = FirstTakeMcapLayout.CHANNEL_GYROSCOPE,
                sequence = 0,
                logTimeNs = 1_700_000_000_001_000_000L,
                data = """
                    {
                      "schemaVersion":"firsttake.imu.v1",
                      "sensorType":"android.sensor.gyroscope",
                      "sensorTimestampNs":1001000000,
                      "arrivalElapsedRealtimeNs":1001100000,
                      "accuracy":3,
                      "units":"rad/s",
                      "values":[0.1,0.2,0.3]
                    }
                """.trimIndent().toByteArray(),
            )
            writer.writeMessage(
                channelId = FirstTakeMcapLayout.CHANNEL_ACCELEROMETER,
                sequence = 0,
                logTimeNs = 1_700_000_000_002_000_000L,
                data = """
                    {
                      "schemaVersion":"firsttake.imu.v1",
                      "sensorType":"android.sensor.accelerometer",
                      "sensorTimestampNs":1002000000,
                      "arrivalElapsedRealtimeNs":1002100000,
                      "accuracy":3,
                      "units":"m/s^2",
                      "values":[0.0,0.0,9.81]
                    }
                """.trimIndent().toByteArray(),
            )
        }
        assertTrue(output.isFile)
        assertTrue(output.length() > 0)
        assertEquals(
            McapFileState.FINALIZED_VALID,
            FirstTakeMcapRecovery.scan(output).state,
        )
    }

    @Test
    fun `complete footer without trailing magic remains recoverable`() {
        val finalized = tempFile("missing-trailing-magic")
        FirstTakeMcapWriter.create(finalized).use { writer ->
            writer.writeMessage(
                channelId = FirstTakeMcapLayout.CHANNEL_GYROSCOPE,
                sequence = 0,
                logTimeNs = 10,
                data = byteArrayOf(1, 2, 3),
            )
        }
        RandomAccessFile(finalized, "rw").use { file ->
            file.setLength(file.length() - 8)
        }

        val report = FirstTakeMcapRecovery.scan(finalized)

        assertEquals(McapFileState.RECOVERABLE_PREFIX, report.state)
        assertEquals(1, report.messageCount)
        assertFalse(report.errors.isNotEmpty())
    }

    private fun tempFile(label: String): File {
        val root = Files.createTempDirectory("firsttake-mcap-$label").toFile()
        return File(root, "$label.mcap")
    }
}
