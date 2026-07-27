package dev.firsttake.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecoveryEvidenceBundleTest {
    @Test
    fun `bundle export is hashed deterministic and leaves sources untouched`() {
        val root = Files.createTempDirectory("firsttake-bundle").toFile()
        val sessions = File(root, "sessions").apply { mkdirs() }
        val session = File(sessions, "session-1").apply { mkdirs() }
        DurableSessionJournal.open(File(session, "session.wal")).use { journal ->
            journal.append("SESSION_OPENED", 10)
            journal.append(
                "VIDEO_CHECKPOINT",
                20,
                """{"recordedDurationNs":1000000000}""",
            )
        }
        File(session, "capture.mp4").writeBytes(byteArrayOf(1, 2, 3))
        File(session, "imu.jsonl").writeText("sample")
        val before = snapshot(session)
        val report = SessionRecoveryScanner(
            sessionsRoot = sessions,
            videoInspector = RecoveryVideoInspector {
                VideoArtifactInspection(
                    readable = true,
                    durationNs = 1_100_000_000,
                    sampleCount = 33,
                    error = null,
                )
            },
        ).scan(hashArtifacts = true).single()
        val encodedOnce = RecoveryEvidenceJson.encode(report)
        val encodedTwice = RecoveryEvidenceJson.encode(report)
        val bundleDirectory = File(root, "evidence/session-1")

        val bundle = RecoveryEvidenceBundleExporter.export(
            bundleDirectory = bundleDirectory,
            report = report,
            device = DeviceEvidence(
                manufacturer = "Test",
                model = "Phone",
                androidSdk = 36,
                appVersion = "0.1.0",
                buildFingerprint = null,
            ),
        )

        assertEquals(encodedOnce, encodedTwice)
        assertEquals(before, snapshot(session))
        assertTrue(bundle.recoveryReport.readText().contains(
            """"state":"INTERRUPTED_RECOVERABLE"""",
        ))
        assertTrue(bundle.recoveryReport.readText().contains(
            """"measuredLossUpperBoundNs":null""",
        ))
        assertEquals(3, bundle.sourceHashes.readLines().size)
        assertTrue(bundle.manifest.readText().contains("recovery-report.json"))
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryEvidenceBundleExporter.export(
                bundleDirectory = bundleDirectory,
                report = report,
                device = DeviceEvidence("Test", "Phone", 36, "0.1.0", null),
            )
        }
    }

    @Test
    fun `export refuses unhashed source evidence`() {
        val root = Files.createTempDirectory("firsttake-bundle-unhashed").toFile()
        val sessions = File(root, "sessions").apply { mkdirs() }
        val session = File(sessions, "session-2").apply { mkdirs() }
        DurableSessionJournal.open(File(session, "session.wal")).use { journal ->
            journal.append("SESSION_OPENED", 10)
        }
        File(session, "capture.mp4").writeBytes(byteArrayOf(1))
        File(session, "imu.jsonl").writeText("sample")
        val report = SessionRecoveryScanner(
            sessionsRoot = sessions,
            videoInspector = RecoveryVideoInspector {
                VideoArtifactInspection(true, 1, 1, null)
            },
        ).scan(hashArtifacts = false).single()

        assertThrows(IllegalArgumentException::class.java) {
            RecoveryEvidenceBundleExporter.export(
                bundleDirectory = File(root, "evidence"),
                report = report,
                device = DeviceEvidence("Test", "Phone", 36, "0.1.0", null),
            )
        }
    }

    @Test
    fun `bundle contains a new finalized MCAP when source was interrupted`() {
        val root = Files.createTempDirectory("firsttake-bundle-mcap").toFile()
        val sessions = File(root, "sessions").apply { mkdirs() }
        val session = File(sessions, "session-mcap").apply { mkdirs() }
        DurableSessionJournal.open(File(session, "session.wal")).use { journal ->
            journal.append("SESSION_OPENED", 10)
        }
        File(session, "capture.mp4").writeBytes(byteArrayOf(1, 2, 3))
        val imu = File(session, "imu.mcap")
        val writer = FirstTakeMcapWriter.create(imu)
        writer.writeMessage(
            channelId = FirstTakeMcapLayout.CHANNEL_GYROSCOPE,
            sequence = 0,
            logTimeNs = 100,
            data = byteArrayOf(1),
        )
        writer.sync()
        val killedBytes = imu.readBytes()
        writer.finish()
        imu.writeBytes(killedBytes)
        val sourceBefore = imu.readBytes()
        val report = SessionRecoveryScanner(
            sessionsRoot = sessions,
            videoInspector = RecoveryVideoInspector {
                VideoArtifactInspection(true, 100, 3, null)
            },
        ).scan(hashArtifacts = true).single()

        val bundle = RecoveryEvidenceBundleExporter.export(
            bundleDirectory = File(root, "evidence/session-mcap"),
            report = report,
            device = DeviceEvidence("Test", "Phone", 36, "0.1.0", null),
            sourceSessionDirectory = session,
        )

        assertEquals(sourceBefore.toList(), imu.readBytes().toList())
        assertEquals(
            McapFileState.FINALIZED_VALID,
            FirstTakeMcapRecovery.scan(bundle.recoveredImu!!).state,
        )
        assertTrue(bundle.manifest.readText().contains("recovered-imu.mcap"))
    }

    private fun snapshot(directory: File): Map<String, Pair<Long, Long>> =
        directory.listFiles()
            .orEmpty()
            .associate { it.name to (it.length() to it.lastModified()) }
}
