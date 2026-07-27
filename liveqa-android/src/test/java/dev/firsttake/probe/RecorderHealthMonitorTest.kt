package dev.firsttake.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecorderHealthMonitorTest {
    private fun observation(
        elapsedSeconds: Long,
        recordedSeconds: Long,
        bytes: Long,
        usableBytes: Long? = 2L * 1_024 * 1_024 * 1_024,
    ) = RecorderHealthObservation(
        observedAtElapsedRealtimeNs = elapsedSeconds * 1_000_000_000,
        recordedDurationNs = recordedSeconds * 1_000_000_000,
        bytesRecorded = bytes,
        usableStorageBytes = usableBytes,
    )

    @Test
    fun healthyProgressDoesNotEmitNoise() {
        val monitor = RecorderHealthMonitor()
        assertNull(monitor.observe(observation(1, 1, 5_000_000)))
        assertNull(monitor.observe(observation(2, 2, 10_000_000)))
    }

    @Test
    fun lowStorageWarnsBeforeCriticalStop() {
        val monitor = RecorderHealthMonitor(
            storageWarningBytes = 500,
            storageCriticalBytes = 100,
            storageWarningSeconds = 0.0,
            storageCriticalSeconds = 0.0,
        )

        val warning = monitor.observe(
            observation(1, 1, 10, usableBytes = 400),
        )
        assertEquals(RecorderHealthState.STORAGE_WARNING, warning?.newState)
        assertEquals(RecorderHealthAction.NONE, warning?.action)

        val critical = monitor.observe(
            observation(2, 2, 20, usableBytes = 90),
        )
        assertEquals(RecorderHealthState.STORAGE_CRITICAL, critical?.newState)
        assertEquals(RecorderHealthAction.GRACEFUL_STOP, critical?.action)
    }

    @Test
    fun rateBasedForecastCanTriggerCriticalStorage() {
        val monitor = RecorderHealthMonitor(
            storageWarningBytes = 1,
            storageCriticalBytes = 1,
            storageWarningSeconds = 120.0,
            storageCriticalSeconds = 30.0,
        )
        val event = monitor.observe(
            observation(
                elapsedSeconds = 10,
                recordedSeconds = 10,
                bytes = 100_000_000,
                usableBytes = 200_000_000,
            ),
        )
        assertEquals(RecorderHealthState.STORAGE_CRITICAL, event?.newState)
    }

    @Test
    fun persistentWriterStallDisablesOnlyTheSidecar() {
        val monitor = RecorderHealthMonitor(
            storageWarningBytes = 1,
            storageCriticalBytes = 1,
        )
        monitor.observe(observation(1, 1, 5_000_000))
        assertNull(monitor.observe(observation(2, 1, 5_000_000)))
        val stalled = monitor.observe(observation(3, 1, 5_000_000))
        assertEquals(RecorderHealthState.WRITER_STALLED, stalled?.newState)
        assertEquals(
            RecorderHealthAction.DISABLE_ANALYSIS,
            stalled?.action,
        )
    }

    @Test
    fun aStalledWriterCanReportRecovery() {
        val monitor = RecorderHealthMonitor(
            storageWarningBytes = 1,
            storageCriticalBytes = 1,
        )
        monitor.observe(observation(1, 1, 5_000_000))
        monitor.observe(observation(2, 1, 5_000_000))
        monitor.observe(observation(3, 1, 5_000_000))

        val recovered = monitor.observe(observation(4, 2, 10_000_000))
        assertEquals(RecorderHealthState.HEALTHY, recovered?.newState)
        assertEquals(RecorderHealthAction.NONE, recovered?.action)
    }
}
