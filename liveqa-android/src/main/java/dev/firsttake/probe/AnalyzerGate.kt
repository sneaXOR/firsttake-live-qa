package dev.firsttake.probe

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class AnalyzerGate(initialProfile: AnalysisProfile) {
    private val profile = AtomicReference(initialProfile)
    private val lastAcceptedNs = AtomicLong(Long.MIN_VALUE)

    fun currentProfile(): AnalysisProfile = profile.get()

    fun reset(targetProfile: AnalysisProfile) {
        profile.set(targetProfile)
        lastAcceptedNs.set(Long.MIN_VALUE)
    }

    fun degradeTo(candidate: AnalysisProfile): Boolean {
        while (true) {
            val current = profile.get()
            if (candidate.ordinal < current.ordinal) {
                return false
            }
            if (candidate == current) {
                return true
            }
            if (profile.compareAndSet(current, candidate)) {
                return true
            }
        }
    }

    fun shouldAnalyze(timestampNs: Long): Boolean {
        val current = profile.get()
        if (current == AnalysisProfile.WRITERS_ONLY) {
            return false
        }
        val intervalNs = (1_000_000_000.0 / current.sampleHz).toLong()
        while (true) {
            val previous = lastAcceptedNs.get()
            if (
                previous != Long.MIN_VALUE &&
                timestampNs - previous < intervalNs
            ) {
                return false
            }
            if (lastAcceptedNs.compareAndSet(previous, timestampNs)) {
                return true
            }
        }
    }
}
