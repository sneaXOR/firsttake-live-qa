package dev.firsttake.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DurableSessionJournalTest {
    @Test
    fun `fresh journal round trips a verified chain`() {
        val root = Files.createTempDirectory("firsttake-wal").toFile()
        val file = File(root, "session.wal")

        DurableSessionJournal.open(file).use { journal ->
            journal.append("SESSION_OPENED", 10, """{"sessionId":"abc"}""")
            journal.append("VIDEO_CHECKPOINT", 20, """{"bytes":1024}""")
            journal.append("SESSION_COMMITTED", 30)
        }

        val report = DurableSessionJournal.recover(file)
        assertTrue(report.clean)
        assertEquals(2L, report.validThroughSequence)
        assertEquals(
            listOf("SESSION_OPENED", "VIDEO_CHECKPOINT", "SESSION_COMMITTED"),
            report.records.map { it.type },
        )
        assertEquals("""{"bytes":1024}""", report.records[1].payload)
    }

    @Test
    fun `torn final line is never accepted`() {
        val root = Files.createTempDirectory("firsttake-wal-torn").toFile()
        val file = File(root, "session.wal")

        DurableSessionJournal.open(file).use { journal ->
            journal.append("SESSION_OPENED", 10)
            journal.append("VIDEO_CHECKPOINT", 20)
        }
        val validLength = file.length()
        file.appendText("firsttake.session.wal.v1|2|30|SESSION_COM")

        val report = DurableSessionJournal.recover(file)
        assertEquals(2, report.records.size)
        assertTrue(report.ignoredTornTailBytes > 0)
        assertTrue(report.errors.isEmpty())
        assertFalse(report.clean)
        assertEquals(validLength, file.length() - report.ignoredTornTailBytes)
    }

    @Test
    fun `tampering stops recovery at the previous verified record`() {
        val root = Files.createTempDirectory("firsttake-wal-tamper").toFile()
        val file = File(root, "session.wal")

        DurableSessionJournal.open(file).use { journal ->
            journal.append("SESSION_OPENED", 10)
            journal.append("VIDEO_CHECKPOINT", 20, """{"bytes":1024}""")
            journal.append("SESSION_COMMITTED", 30)
        }
        val lines = file.readLines().toMutableList()
        lines[1] = lines[1].replace("VIDEO_CHECKPOINT", "VIDEO_CORRUPTED")
        file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))

        val report = DurableSessionJournal.recover(file)
        assertEquals(1, report.records.size)
        assertEquals(0L, report.validThroughSequence)
        assertTrue(report.errors.single().contains("content hash mismatch"))
    }

    @Test
    fun `reopening continues sequence and hash chain`() {
        val root = Files.createTempDirectory("firsttake-wal-reopen").toFile()
        val file = File(root, "session.wal")

        DurableSessionJournal.open(file).use { journal ->
            journal.append("SESSION_OPENED", 10)
        }
        DurableSessionJournal.open(file).use { journal ->
            journal.append("VIDEO_CHECKPOINT", 20)
        }

        val report = DurableSessionJournal.recover(file)
        assertTrue(report.clean)
        assertEquals(listOf(0L, 1L), report.records.map { it.sequence })
        assertEquals(report.records[0].hash, report.records[1].previousHash)
    }

    @Test
    fun `invalid event types are rejected before write`() {
        val root = Files.createTempDirectory("firsttake-wal-invalid").toFile()
        val file = File(root, "session.wal")

        DurableSessionJournal.open(file).use { journal ->
            assertThrows(IllegalArgumentException::class.java) {
                journal.append("not-valid", 10)
            }
        }
        assertEquals(0L, file.length())
    }
}
