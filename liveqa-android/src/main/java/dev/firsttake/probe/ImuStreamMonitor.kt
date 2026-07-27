package dev.firsttake.probe

enum class ImuStreamIssue {
    NON_MONOTONIC_TIMESTAMP,
    LARGE_GAP,
}

data class ImuStreamHealthEvent(
    val channelId: Int,
    val issue: ImuStreamIssue,
    val previousTimestampNs: Long,
    val currentTimestampNs: Long,
    val observedDeltaNs: Long,
    val learnedMedianDeltaNs: Long?,
)

/**
 * Learns each sensor cadence independently and reports evidence, not a
 * universal pass/fail verdict. Sensor batching and device-specific rates are
 * therefore not mislabeled as defects.
 */
class ImuStreamMonitor(
    private val learningIntervals: Int = 20,
    private val historyCapacity: Int = 64,
    private val gapMultiplier: Long = 5,
    private val absoluteGapFloorNs: Long = 100_000_000L,
) {
    private data class ChannelState(
        var previousTimestampNs: Long? = null,
        val positiveDeltasNs: ArrayDeque<Long> = ArrayDeque(),
    )

    private val channels = mutableMapOf<Int, ChannelState>()

    init {
        require(learningIntervals > 0)
        require(historyCapacity >= learningIntervals)
        require(gapMultiplier >= 2)
        require(absoluteGapFloorNs > 0)
    }

    fun observe(
        channelId: Int,
        timestampNs: Long,
    ): ImuStreamHealthEvent? {
        val state = channels.getOrPut(channelId) { ChannelState() }
        val previous = state.previousTimestampNs
        state.previousTimestampNs = timestampNs
        if (previous == null) {
            return null
        }
        val delta = timestampNs - previous
        if (delta <= 0) {
            return ImuStreamHealthEvent(
                channelId = channelId,
                issue = ImuStreamIssue.NON_MONOTONIC_TIMESTAMP,
                previousTimestampNs = previous,
                currentTimestampNs = timestampNs,
                observedDeltaNs = delta,
                learnedMedianDeltaNs = median(state.positiveDeltasNs),
            )
        }

        val learnedMedian = if (
            state.positiveDeltasNs.size >= learningIntervals
        ) {
            median(state.positiveDeltasNs)
        } else {
            null
        }
        val gapThreshold = learnedMedian?.let {
            maxOf(absoluteGapFloorNs, it * gapMultiplier)
        }
        val event = if (gapThreshold != null && delta > gapThreshold) {
            ImuStreamHealthEvent(
                channelId = channelId,
                issue = ImuStreamIssue.LARGE_GAP,
                previousTimestampNs = previous,
                currentTimestampNs = timestampNs,
                observedDeltaNs = delta,
                learnedMedianDeltaNs = learnedMedian,
            )
        } else {
            null
        }

        // Do not teach a detected outlier back into the baseline.
        if (event == null) {
            state.positiveDeltasNs.addLast(delta)
            while (state.positiveDeltasNs.size > historyCapacity) {
                state.positiveDeltasNs.removeFirst()
            }
        }
        return event
    }

    private fun median(values: Collection<Long>): Long? {
        if (values.isEmpty()) {
            return null
        }
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            sorted[middle - 1] / 2L + sorted[middle] / 2L
        } else {
            sorted[middle]
        }
    }
}
