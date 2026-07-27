package dev.firsttake.probe

import org.json.JSONObject
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class TelemetryChainReport(
    val writtenRecords: Long,
    val droppedRecords: Long,
    val lastHash: String,
    val complete: Boolean,
    val error: String?,
)

class ProbeTelemetryWriter(
    output: File,
    capacity: Int = 4_096,
) : Closeable {
    private val queue = ArrayBlockingQueue<String>(capacity)
    private val accepting = AtomicBoolean(true)
    private val dropped = AtomicLong(0)
    private val written = AtomicLong(0)
    private val lastHash = AtomicReference(TelemetryChain.GENESIS_HASH)
    private val failure = AtomicReference<String?>(null)
    private val worker = Thread(
        {
            try {
                BufferedWriter(
                    OutputStreamWriter(
                        FileOutputStream(output),
                        StandardCharsets.UTF_8,
                    ),
                    64 * 1_024,
                ).use { writer ->
                    while (accepting.get() || queue.isNotEmpty()) {
                        val payload =
                            queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        val sequence = written.get()
                        val previousHash = lastHash.get()
                        val hash = TelemetryChain.recordHash(
                            sequence = sequence,
                            previousHash = previousHash,
                            payload = payload,
                        )
                        writer.write(
                            JSONObject()
                                .put(
                                    "schemaVersion",
                                    "firsttake.telemetry-envelope.v1",
                                )
                                .put("sequence", sequence)
                                .put("previousHash", previousHash)
                                .put("payloadJson", payload)
                                .put("hash", hash)
                                .toString(),
                        )
                        writer.newLine()
                        lastHash.set(hash)
                        written.incrementAndGet()
                    }
                    writer.flush()
                }
            } catch (error: Exception) {
                failure.compareAndSet(
                    null,
                    error.message ?: error.javaClass.simpleName,
                )
            }
        },
        "firsttake-probe-telemetry",
    )

    init {
        output.parentFile?.mkdirs()
        worker.start()
    }

    fun offerJson(jsonLine: String): Boolean {
        if (!accepting.get()) {
            return false
        }
        val accepted = queue.offer(jsonLine)
        if (!accepted) {
            dropped.incrementAndGet()
        }
        return accepted
    }

    fun droppedCount(): Long = dropped.get()

    fun report(): TelemetryChainReport = TelemetryChainReport(
        writtenRecords = written.get(),
        droppedRecords = dropped.get(),
        lastHash = lastHash.get(),
        complete = !worker.isAlive && failure.get() == null,
        error = failure.get(),
    )

    override fun close() {
        if (accepting.compareAndSet(true, false)) {
            worker.join(CLOSE_TIMEOUT_MS)
            if (worker.isAlive) {
                failure.compareAndSet(
                    null,
                    "telemetry writer did not drain within ${CLOSE_TIMEOUT_MS}ms",
                )
                worker.interrupt()
            }
        }
    }

    private companion object {
        const val CLOSE_TIMEOUT_MS = 5_000L
    }
}
