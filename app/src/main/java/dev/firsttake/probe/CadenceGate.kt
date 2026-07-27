package dev.firsttake.probe

class CadenceGate {
    private var lastAcceptedNs = Long.MIN_VALUE

    fun shouldRun(timestampNs: Long, frequencyHz: Double): Boolean {
        if (frequencyHz <= 0.0) {
            return false
        }
        val intervalNs = (1_000_000_000.0 / frequencyHz).toLong()
        if (
            lastAcceptedNs != Long.MIN_VALUE &&
            timestampNs - lastAcceptedNs < intervalNs
        ) {
            return false
        }
        lastAcceptedNs = timestampNs
        return true
    }

    fun reset() {
        lastAcceptedNs = Long.MIN_VALUE
    }
}
