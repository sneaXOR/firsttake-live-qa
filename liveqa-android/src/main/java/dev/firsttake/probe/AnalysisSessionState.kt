package dev.firsttake.probe

import java.util.concurrent.atomic.AtomicLong

/**
 * Separates preview/prewarm work from evidence-bearing recording sessions.
 *
 * The analyzer thread resets its temporal state whenever the generation
 * changes. A generation of zero means that no recording session is active.
 */
class AnalysisSessionState {
    private val nextGeneration = AtomicLong(0)
    private val activeGeneration = AtomicLong(0)

    fun begin(): Long {
        val generation = nextGeneration.incrementAndGet()
        activeGeneration.set(generation)
        return generation
    }

    fun end(generation: Long) {
        activeGeneration.compareAndSet(generation, 0)
    }

    fun activeGeneration(): Long = activeGeneration.get()
}
