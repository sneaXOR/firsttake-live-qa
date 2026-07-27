package dev.firsttake.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionRecoveryScannerTest {
    private val readableVideoInspector = RecoveryVideoInspector {
        VideoArtifactInspection(
            readable = true,
            durationNs = 9_500_000_000L,
            sampleCount = 285,
            error = null,
        )
    }

    @Test
    fun `targeted scan inspects only the requested session`() {
        val root = Files.createTempDirectory("firsttake-targeted-scan").toFile()
        val wanted = File(root, "wanted").apply { mkdirs() }
        val unrelated = File(root, "unrelated").apply { mkdirs() }
        DurableSessionJournal.open(File(wanted, "session.wal")).use {
            it.append("SESSION_OPENED", 10)
        }
        File(unrelated, "capture.mp4").writeBytes(byteArrayOf(1))

        val report = SessionRecoveryScanner(
            sessionsRoot = root,
            videoInspector = readableVideoInspector,
        ).scanSession("wanted")

        assertEquals("wanted", report.sessionId)
        assertEquals(SessionRecoveryState.INTERRUPTED_PARTIAL, report.state)
        assertFalse(report.video.present)
    }

    @Test
    fun `targeted scan rejects path traversal`() {
        val root = Files.createTempDirectory("firsttake-targeted-safe").toFile()

        val report = SessionRecoveryScanner(
            sessionsRoot = root,
            videoInspector = readableVideoInspector,
        ).scanSession("../outside")

        assertEquals(SessionRecoveryState.NOT_ASSESSABLE, report.state)
        assertTrue(report.warnings.single().contains("invalid"))
    }

    @Test
    fun `complete committed session is classified without modifying sources`() {
        val root = Files.createTempDirectory("firsttake-scan-complete").toFile()
        val session = File(root, "session-a").apply { mkdirs() }
        val wal = File(session, "session.wal")
        DurableSessionJournal.open(wal).use { journal ->
            journal.append("SESSION_OPENED", 10)
            journal.append(
                "VIDEO_CHECKPOINT",
                20,
                """{"recordedDurationNs":9000000000}""",
            )
            journal.append("SESSION_COMMITTED", 30)
        }
        File(session, "capture.mp4").writeBytes(byteArrayOf(1, 2, 3))
        File(session, "imu.jsonl").writeText("""{"sample":1}""")
        val before = snapshot(session)

        val report = SessionRecoveryScanner(
            sessionsRoot = root,
            videoInspector = readableVideoInspector,
        ).scan().single()

        assertEquals(SessionRecoveryState.COMPLETE, report.state)
        assertEquals(9_000_000_000L, report.lastCheckpointDurationNs)
        assertEquals("SESSION_COMMITTED", report.lastEventType)
        assertEquals(ArtifactHashState.NOT_REQUESTED, report.video.hashState)
        assertNull(report.video.sha256)
        assertEquals(before, snapshot(session))
    }

    @Test
    fun `interrupted readable video and IMU are recoverable`() {
        val root = Files.createTempDirectory("firsttake-scan-recoverable").toFile()
        val session = File(root, "session-b").apply { mkdirs() }
        DurableSessionJournal.open(File(session, "session.wal")).use { journal ->
            journal.append("SESSION_OPENED", 10)
            journal.append("VIDEO_STARTED", 20)
            journal.append(
                "VIDEO_CHECKPOINT",
                30,
                """{"recordedDurationNs":8000000000}""",
            )
        }
        File(session, "capture.mp4").writeBytes(byteArrayOf(9, 8, 7))
        File(session, "imu.jsonl").writeText("sample")

        val report = SessionRecoveryScanner(
            sessionsRoot = root,
            videoInspector = readableVideoInspector,
        ).scan(hashArtifacts = true).single()

        assertEquals(SessionRecoveryState.INTERRUPTED_RECOVERABLE, report.state)
        assertEquals(ArtifactHashState.COMPUTED, report.video.hashState)
        assertEquals(64, report.video.sha256?.length)
        assertEquals(64, report.imu.sha256?.length)
        assertTrue(report.walErrors.isEmpty())
    }

    @Test
    fun `torn WAL tail preserves recoverable verified prefix`() {
        val root = Files.createTempDirectory("firsttake-scan-torn").toFile()
        val session = File(root, "session-c").apply { mkdirs() }
        val wal = File(session, "session.wal")
        DurableSessionJournal.open(wal).use { journal ->
            journal.append("SESSION_OPENED", 10)
            journal.append("VIDEO_STARTED", 20)
        }
        wal.appendText("firsttake.session.wal.v1|2|30|VIDEO_CHECK")
        File(session, "capture.mp4").writeBytes(byteArrayOf(1))
        File(session, "imu.jsonl").writeText("sample")

        val report = SessionRecoveryScanner(
            sessionsRoot = root,
            videoInspector = readableVideoInspector,
        ).scan().single()

        assertEquals(
            SessionRecoveryState.INTERRUPTED_RECOVERABLE,
            report.state,
        )
        assertTrue(report.ignoredTornWalTailBytes > 0)
        assertTrue(report.warnings.any { it.contains("ignored") })
    }

    @Test
    fun `tampered WAL is corrupt even when artifacts are readable`() {
        val root = Files.createTempDirectory("firsttake-scan-corrupt").toFile()
        val session = File(root, "session-d").apply { mkdirs() }
        val wal = File(session, "session.wal")
        DurableSessionJournal.open(wal).use { journal ->
            journal.append("SESSION_OPENED", 10)
            journal.append("VIDEO_STARTED", 20)
        }
        val lines = wal.readLines().toMutableList()
        lines[1] = lines[1].replace("VIDEO_STARTED", "VIDEO_TAMPERED")
        wal.writeText(lines.joinToString("\n", postfix = "\n"))
        File(session, "capture.mp4").writeBytes(byteArrayOf(1))
        File(session, "imu.jsonl").writeText("sample")

        val report = SessionRecoveryScanner(
            sessionsRoot = root,
            videoInspector = readableVideoInspector,
        ).scan().single()

        assertEquals(SessionRecoveryState.CORRUPT, report.state)
        assertFalse(report.walErrors.isEmpty())
    }

    @Test
    fun `committed session with unreadable video is corrupt`() {
        val root = Files.createTempDirectory("firsttake-scan-bad-video").toFile()
        val session = File(root, "session-e").apply { mkdirs() }
        DurableSessionJournal.open(File(session, "session.wal")).use { journal ->
            journal.append("SESSION_OPENED", 10)
            journal.append("SESSION_COMMITTED", 20)
        }
        File(session, "capture.mp4").writeBytes(byteArrayOf(1))
        File(session, "imu.jsonl").writeText("sample")
        val scanner = SessionRecoveryScanner(
            sessionsRoot = root,
            videoInspector = RecoveryVideoInspector {
                VideoArtifactInspection(false, null, null, "invalid MP4")
            },
        )

        val report = scanner.scan().single()

        assertEquals(SessionRecoveryState.CORRUPT, report.state)
        assertTrue(report.warnings.any { it.contains("not readable") })
    }

    @Test
    fun `video-only interrupted session is partial`() {
        val root = Files.createTempDirectory("firsttake-scan-partial").toFile()
        val session = File(root, "session-f").apply { mkdirs() }
        DurableSessionJournal.open(File(session, "session.wal")).use { journal ->
            journal.append("SESSION_OPENED", 10)
        }
        File(session, "capture.mp4").writeBytes(byteArrayOf(1))

        val report = SessionRecoveryScanner(
            sessionsRoot = root,
            videoInspector = readableVideoInspector,
        ).scan().single()

        assertEquals(SessionRecoveryState.INTERRUPTED_PARTIAL, report.state)
        assertFalse(report.imu.present)
    }

    @Test
    fun `interrupted live MCAP prefix is recognized as recoverable`() {
        val root = Files.createTempDirectory("firsttake-scan-live-mcap").toFile()
        val session = File(root, "session-mcap").apply { mkdirs() }
        DurableSessionJournal.open(File(session, "session.wal")).use { journal ->
            journal.append("SESSION_OPENED", 10)
            journal.append("VIDEO_STARTED", 20)
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
        val killedSnapshot = imu.readBytes()
        writer.finish()
        imu.writeBytes(killedSnapshot)

        val report = SessionRecoveryScanner(
            sessionsRoot = root,
            videoInspector = readableVideoInspector,
        ).scan().single()

        assertEquals(
            SessionRecoveryState.INTERRUPTED_RECOVERABLE,
            report.state,
        )
        assertEquals(
            McapFileState.RECOVERABLE_PREFIX,
            report.imuMcapReport?.state,
        )
        assertTrue(report.warnings.any { it.contains("needs recovery") })
    }

    @Test
    fun `committed session cannot hide an unfinished MCAP`() {
        val root = Files.createTempDirectory("firsttake-scan-commit-mcap").toFile()
        val session = File(root, "session-mcap-commit").apply { mkdirs() }
        DurableSessionJournal.open(File(session, "session.wal")).use { journal ->
            journal.append("SESSION_OPENED", 10)
            journal.append("SESSION_COMMITTED", 20)
        }
        File(session, "capture.mp4").writeBytes(byteArrayOf(1, 2, 3))
        val imu = File(session, "imu.mcap")
        val writer = FirstTakeMcapWriter.create(imu)
        writer.writeMessage(
            channelId = FirstTakeMcapLayout.CHANNEL_ACCELEROMETER,
            sequence = 0,
            logTimeNs = 100,
            data = byteArrayOf(1),
        )
        writer.sync()
        val killedSnapshot = imu.readBytes()
        writer.finish()
        imu.writeBytes(killedSnapshot)

        val report = SessionRecoveryScanner(
            sessionsRoot = root,
            videoInspector = readableVideoInspector,
        ).scan().single()

        assertEquals(SessionRecoveryState.CORRUPT, report.state)
        assertEquals(
            McapFileState.RECOVERABLE_PREFIX,
            report.imuMcapReport?.state,
        )
    }

    @Test
    fun `empty session directory is reported as empty`() {
        val root = Files.createTempDirectory("firsttake-scan-empty").toFile()
        File(root, "session-g").mkdirs()

        val report = SessionRecoveryScanner(
            sessionsRoot = root,
            videoInspector = readableVideoInspector,
        ).scan().single()

        assertEquals(SessionRecoveryState.EMPTY, report.state)
        assertNull(report.videoInspection)
        assertTrue(report.warnings.isEmpty())
    }

    @Test
    fun `missing root is a valid empty scan`() {
        val parent = Files.createTempDirectory("firsttake-scan-missing").toFile()
        val missingRoot = File(parent, "not-created")

        val reports = SessionRecoveryScanner(
            sessionsRoot = missingRoot,
            videoInspector = readableVideoInspector,
        ).scan()

        assertTrue(reports.isEmpty())
    }

    @Test
    fun `invalid session root is explicitly not assessable`() {
        val parent = Files.createTempDirectory("firsttake-scan-file-root").toFile()
        val fileRoot = File(parent, "sessions").apply { writeText("not a dir") }

        val report = SessionRecoveryScanner(
            sessionsRoot = fileRoot,
            videoInspector = readableVideoInspector,
        ).scan().single()

        assertEquals(SessionRecoveryState.NOT_ASSESSABLE, report.state)
        assertNotNull(report.warnings.single())
    }

    private fun snapshot(directory: File): Map<String, Pair<Long, Long>> =
        directory.listFiles()
            .orEmpty()
            .associate { it.name to (it.length() to it.lastModified()) }
}
