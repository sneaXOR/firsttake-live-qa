package dev.firsttake.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImuStreamMonitorTest {
    @Test
    fun learnsCadenceBeforeCallingAGap() {
        val monitor = ImuStreamMonitor(
            learningIntervals = 3,
            historyCapacity = 4,
            gapMultiplier = 5,
            absoluteGapFloorNs = 20,
        )
        listOf(0L, 10L, 20L, 30L).forEach {
            assertNull(monitor.observe(1, it))
        }

        val gap = monitor.observe(1, 100L)
        assertEquals(ImuStreamIssue.LARGE_GAP, gap?.issue)
        assertEquals(70L, gap?.observedDeltaNs)
        assertEquals(10L, gap?.learnedMedianDeltaNs)
    }

    @Test
    fun reportsDuplicateAndBackwardTimestamps() {
        val monitor = ImuStreamMonitor()
        assertNull(monitor.observe(2, 100))
        assertEquals(
            ImuStreamIssue.NON_MONOTONIC_TIMESTAMP,
            monitor.observe(2, 100)?.issue,
        )
        assertEquals(
            ImuStreamIssue.NON_MONOTONIC_TIMESTAMP,
            monitor.observe(2, 90)?.issue,
        )
    }

    @Test
    fun channelsLearnIndependentCadences() {
        val monitor = ImuStreamMonitor(
            learningIntervals = 2,
            historyCapacity = 4,
            gapMultiplier = 3,
            absoluteGapFloorNs = 1,
        )
        listOf(0L, 10L, 20L).forEach {
            assertNull(monitor.observe(1, it))
        }
        listOf(0L, 100L, 200L).forEach {
            assertNull(monitor.observe(2, it))
        }
        assertEquals(
            ImuStreamIssue.LARGE_GAP,
            monitor.observe(1, 60)?.issue,
        )
        assertNull(monitor.observe(2, 300))
    }

    @Test
    fun aGapDoesNotPoisonTheLearnedBaseline() {
        val monitor = ImuStreamMonitor(
            learningIntervals = 2,
            historyCapacity = 4,
            gapMultiplier = 3,
            absoluteGapFloorNs = 1,
        )
        listOf(0L, 10L, 20L).forEach { monitor.observe(1, it) }
        monitor.observe(1, 100L)

        assertEquals(
            ImuStreamIssue.LARGE_GAP,
            monitor.observe(1, 140L)?.issue,
        )
    }
}
