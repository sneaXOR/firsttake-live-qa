package dev.firsttake.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStartPolicyTest {
    @Test
    fun criticalHeatBlocksARecordingThatTheCameraMayAbort() {
        val decision = CaptureStartPolicy.decide(
            requestedProfile = AnalysisProfile.FULL,
            thermalStatus = ThermalStatus.CRITICAL,
            thermalSignalAvailable = true,
        )

        assertFalse(decision.allowed)
        assertEquals(
            AnalysisProfile.WRITERS_ONLY,
            decision.analysisProfile,
        )
        assertEquals("THERMAL_CRITICAL_CAPTURE_BLOCKED", decision.reason)
    }

    @Test
    fun severeHeatStartsWithCheapChecksInsteadOfOneHeavyInference() {
        val decision = CaptureStartPolicy.decide(
            requestedProfile = AnalysisProfile.FULL,
            thermalStatus = ThermalStatus.SEVERE,
            thermalSignalAvailable = true,
        )

        assertTrue(decision.allowed)
        assertEquals(
            AnalysisProfile.SAFETY_ONLY,
            decision.analysisProfile,
        )
    }

    @Test
    fun writersOnlyControlIsNeverPromotedByThermalPolicy() {
        val decision = CaptureStartPolicy.decide(
            requestedProfile = AnalysisProfile.WRITERS_ONLY,
            thermalStatus = ThermalStatus.SEVERE,
            thermalSignalAvailable = true,
        )

        assertTrue(decision.allowed)
        assertEquals(
            AnalysisProfile.WRITERS_ONLY,
            decision.analysisProfile,
        )
    }

    @Test
    fun unavailableThermalSignalDoesNotInventABlock() {
        val decision = CaptureStartPolicy.decide(
            requestedProfile = AnalysisProfile.FULL,
            thermalStatus = ThermalStatus.UNKNOWN,
            thermalSignalAvailable = false,
        )

        assertTrue(decision.allowed)
        assertEquals(AnalysisProfile.FULL, decision.analysisProfile)
    }
}
