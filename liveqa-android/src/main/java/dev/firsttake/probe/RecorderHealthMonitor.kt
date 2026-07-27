package dev.firsttake.probe

enum class RecorderHealthState {
    HEALTHY,
    STORAGE_WARNING,
    STORAGE_CRITICAL,
    WRITER_STALLED,
}

enum class RecorderHealthAction {
    NONE,
    DISABLE_ANALYSIS,
    GRACEFUL_STOP,
}

data class RecorderHealthObservation(
    val observedAtElapsedRealtimeNs: Long,
    val recordedDurationNs: Long,
    val bytesRecorded: Long,
    val usableStorageBytes: Long?,
)

data class RecorderHealthEvent(
    val previousState: RecorderHealthState,
    val newState: RecorderHealthState,
    val action: RecorderHealthAction,
    val reason: String,
    val estimatedStorageSecondsRemaining: Double?,
    val usableStorageBytes: Long?,
)

/**
 * Uses only CameraX writer counters and filesystem capacity. Thresholds are
 * conservative engineering hypotheses until the physical device campaign.
 */
class RecorderHealthMonitor(
    private val storageWarningBytes: Long = 512L * 1_024 * 1_024,
    private val storageCriticalBytes: Long = 256L * 1_024 * 1_024,
    private val storageWarningSeconds: Double = 120.0,
    private val storageCriticalSeconds: Double = 30.0,
    private val consecutiveStallsRequired: Int = 2,
) {
    private var previous: RecorderHealthObservation? = null
    private var state = RecorderHealthState.HEALTHY
    private var consecutiveStalls = 0

    fun observe(observation: RecorderHealthObservation): RecorderHealthEvent? {
        val prior = previous
        previous = observation
        val bytesPerSecond = if (
            observation.recordedDurationNs > 0 &&
            observation.bytesRecorded > 0
        ) {
            observation.bytesRecorded.toDouble() /
                (observation.recordedDurationNs / 1_000_000_000.0)
        } else {
            null
        }
        val secondsRemaining = if (
            observation.usableStorageBytes != null &&
            bytesPerSecond != null &&
            bytesPerSecond > 0
        ) {
            observation.usableStorageBytes / bytesPerSecond
        } else {
            null
        }

        val usable = observation.usableStorageBytes
        val criticalStorage =
            usable != null && usable <= storageCriticalBytes ||
                secondsRemaining != null &&
                secondsRemaining <= storageCriticalSeconds
        if (criticalStorage) {
            return transition(
                newState = RecorderHealthState.STORAGE_CRITICAL,
                action = RecorderHealthAction.GRACEFUL_STOP,
                reason = "STORAGE_CRITICAL",
                secondsRemaining = secondsRemaining,
                usableStorageBytes = usable,
            )
        }

        val eligibleDelta = if (prior != null) {
            observation.observedAtElapsedRealtimeNs -
                prior.observedAtElapsedRealtimeNs >= MIN_STATUS_INTERVAL_NS
        } else {
            false
        }
        if (prior != null && eligibleDelta) {
            val durationProgress =
                observation.recordedDurationNs - prior.recordedDurationNs
            val byteProgress =
                observation.bytesRecorded - prior.bytesRecorded
            if (
                durationProgress <= MIN_DURATION_PROGRESS_NS &&
                byteProgress <= 0
            ) {
                consecutiveStalls += 1
            } else {
                consecutiveStalls = 0
            }
        }
        if (consecutiveStalls >= consecutiveStallsRequired) {
            return transition(
                newState = RecorderHealthState.WRITER_STALLED,
                action = RecorderHealthAction.DISABLE_ANALYSIS,
                reason = "CAMERAX_COUNTERS_NOT_PROGRESSING",
                secondsRemaining = secondsRemaining,
                usableStorageBytes = usable,
            )
        }

        val warningStorage =
            usable != null && usable <= storageWarningBytes ||
                secondsRemaining != null &&
                secondsRemaining <= storageWarningSeconds
        if (warningStorage) {
            return transition(
                newState = RecorderHealthState.STORAGE_WARNING,
                action = RecorderHealthAction.NONE,
                reason = "STORAGE_LOW",
                secondsRemaining = secondsRemaining,
                usableStorageBytes = usable,
            )
        }

        return transition(
            newState = RecorderHealthState.HEALTHY,
            action = RecorderHealthAction.NONE,
            reason = "RECORDER_PROGRESS_RECOVERED",
            secondsRemaining = secondsRemaining,
            usableStorageBytes = usable,
        )
    }

    private fun transition(
        newState: RecorderHealthState,
        action: RecorderHealthAction,
        reason: String,
        secondsRemaining: Double?,
        usableStorageBytes: Long?,
    ): RecorderHealthEvent? {
        if (state == newState) {
            return null
        }
        val event = RecorderHealthEvent(
            previousState = state,
            newState = newState,
            action = action,
            reason = reason,
            estimatedStorageSecondsRemaining = secondsRemaining,
            usableStorageBytes = usableStorageBytes,
        )
        state = newState
        if (newState != RecorderHealthState.WRITER_STALLED) {
            consecutiveStalls = 0
        }
        return event
    }

    private companion object {
        const val MIN_STATUS_INTERVAL_NS = 500_000_000L
        const val MIN_DURATION_PROGRESS_NS = 100_000_000L
    }
}
