package dev.firsttake.probe

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

private const val JOURNAL_SCHEMA = "firsttake.session.wal.v1"
private const val GENESIS_HASH = "GENESIS"
private val EVENT_TYPE_PATTERN = Regex("[A-Z][A-Z0-9_]*")
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

data class JournalRecord(
    val sequence: Long,
    val elapsedRealtimeNs: Long,
    val type: String,
    val payload: String,
    val previousHash: String,
    val hash: String,
)

data class JournalRecoveryReport(
    val records: List<JournalRecord>,
    val ignoredTornTailBytes: Int,
    val errors: List<String>,
) {
    val clean: Boolean
        get() = ignoredTornTailBytes == 0 && errors.isEmpty()

    val validThroughSequence: Long?
        get() = records.lastOrNull()?.sequence

    val validThroughHash: String
        get() = records.lastOrNull()?.hash ?: GENESIS_HASH
}

/**
 * Low-frequency, fsync-backed session truth.
 *
 * This journal is deliberately not used for per-frame or per-IMU-sample data.
 * It records state transitions and durable checkpoints only. Each complete
 * line is hash chained; recovery accepts a verified prefix and nothing else.
 */
class DurableSessionJournal private constructor(
    private val file: File,
    private var nextSequence: Long,
    private var previousHash: String,
) : AutoCloseable {
    private val output = FileOutputStream(file, true)
    private var closed = false

    @Synchronized
    fun append(
        type: String,
        elapsedRealtimeNs: Long,
        payload: String = "{}",
    ): JournalRecord {
        check(!closed) { "Journal is closed" }
        require(type.matches(EVENT_TYPE_PATTERN)) {
            "Invalid event type: $type"
        }
        require(elapsedRealtimeNs >= 0) {
            "elapsedRealtimeNs must be non-negative"
        }

        val payloadBase64 = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(payload.toByteArray(StandardCharsets.UTF_8))
        val canonical = canonicalPrefix(
            sequence = nextSequence,
            elapsedRealtimeNs = elapsedRealtimeNs,
            type = type,
            payloadBase64 = payloadBase64,
            previousHash = previousHash,
        )
        val hash = sha256(canonical)
        val line = "$canonical|$hash\n".toByteArray(StandardCharsets.UTF_8)

        output.write(line)
        output.flush()
        output.fd.sync()

        val record = JournalRecord(
            sequence = nextSequence,
            elapsedRealtimeNs = elapsedRealtimeNs,
            type = type,
            payload = payload,
            previousHash = previousHash,
            hash = hash,
        )
        nextSequence += 1
        previousHash = hash
        return record
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            output.flush()
            output.fd.sync()
            output.close()
            closed = true
        }
    }

    companion object {
        fun open(file: File): DurableSessionJournal {
            file.parentFile?.mkdirs()
            val recovery = recover(file)
            require(recovery.errors.isEmpty()) {
                "Journal has an invalid committed prefix: ${recovery.errors.joinToString()}"
            }
            require(recovery.ignoredTornTailBytes == 0) {
                "Journal has a torn tail; recover it before appending"
            }
            return DurableSessionJournal(
                file = file,
                nextSequence = (recovery.validThroughSequence ?: -1L) + 1L,
                previousHash = recovery.validThroughHash,
            )
        }

        fun recover(file: File): JournalRecoveryReport {
            if (!file.exists()) {
                return JournalRecoveryReport(
                    records = emptyList(),
                    ignoredTornTailBytes = 0,
                    errors = emptyList(),
                )
            }

            val bytes = file.readBytes()
            val lastNewline = bytes.indexOfLast { it == '\n'.code.toByte() }
            val completeLength = if (lastNewline >= 0) lastNewline + 1 else 0
            val ignoredTailBytes = bytes.size - completeLength
            val completeText = String(
                bytes,
                0,
                completeLength,
                StandardCharsets.UTF_8,
            )
            val lines = completeText.lineSequence()
                .map { it.removeSuffix("\r") }
                .filter { it.isNotEmpty() }

            val records = mutableListOf<JournalRecord>()
            val errors = mutableListOf<String>()
            var expectedSequence = 0L
            var expectedPreviousHash = GENESIS_HASH

            for ((lineIndex, line) in lines.withIndex()) {
                val fields = line.split('|')
                if (fields.size != 7) {
                    errors += "line ${lineIndex + 1}: expected 7 fields"
                    break
                }
                val schema = fields[0]
                val sequenceText = fields[1]
                val elapsedText = fields[2]
                val type = fields[3]
                val payloadBase64 = fields[4]
                val previousHash = fields[5]
                val hash = fields[6]
                if (schema != JOURNAL_SCHEMA) {
                    errors += "line ${lineIndex + 1}: unsupported schema"
                    break
                }
                val sequence = sequenceText.toLongOrNull()
                val elapsedRealtimeNs = elapsedText.toLongOrNull()
                if (sequence == null || elapsedRealtimeNs == null) {
                    errors += "line ${lineIndex + 1}: invalid numeric field"
                    break
                }
                if (sequence != expectedSequence) {
                    errors += "line ${lineIndex + 1}: non-contiguous sequence"
                    break
                }
                if (elapsedRealtimeNs < 0) {
                    errors += "line ${lineIndex + 1}: negative timestamp"
                    break
                }
                if (!type.matches(EVENT_TYPE_PATTERN)) {
                    errors += "line ${lineIndex + 1}: invalid event type"
                    break
                }
                if (previousHash != expectedPreviousHash) {
                    errors += "line ${lineIndex + 1}: previous hash mismatch"
                    break
                }
                if (!hash.matches(SHA256_PATTERN)) {
                    errors += "line ${lineIndex + 1}: invalid hash"
                    break
                }
                val canonical = canonicalPrefix(
                    sequence = sequence,
                    elapsedRealtimeNs = elapsedRealtimeNs,
                    type = type,
                    payloadBase64 = payloadBase64,
                    previousHash = previousHash,
                )
                if (sha256(canonical) != hash) {
                    errors += "line ${lineIndex + 1}: content hash mismatch"
                    break
                }
                val payload = try {
                    String(
                        Base64.getUrlDecoder().decode(payloadBase64),
                        StandardCharsets.UTF_8,
                    )
                } catch (_: IllegalArgumentException) {
                    errors += "line ${lineIndex + 1}: invalid payload encoding"
                    break
                }
                records += JournalRecord(
                    sequence = sequence,
                    elapsedRealtimeNs = elapsedRealtimeNs,
                    type = type,
                    payload = payload,
                    previousHash = previousHash,
                    hash = hash,
                )
                expectedSequence += 1
                expectedPreviousHash = hash
            }

            return JournalRecoveryReport(
                records = records,
                ignoredTornTailBytes = ignoredTailBytes,
                errors = errors,
            )
        }

        private fun canonicalPrefix(
            sequence: Long,
            elapsedRealtimeNs: Long,
            type: String,
            payloadBase64: String,
            previousHash: String,
        ): String = listOf(
            JOURNAL_SCHEMA,
            sequence.toString(),
            elapsedRealtimeNs.toString(),
            type,
            payloadBase64,
            previousHash,
        ).joinToString("|")

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}
