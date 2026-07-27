package dev.firsttake.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePolicyTest {
    private fun observation(
        inferenceP95Ms: Double,
        drops: Int = 0,
        gapMs: Double = 0.0,
        thermal: ThermalStatus = ThermalStatus.NONE,
        thermalAvailable: Boolean = true,
    ) = PreflightObservation(
        inferenceP95Ms = inferenceP95Ms,
        writerDroppedFrames = drops,
        writerGapRegressionMs = gapMs,
        thermalStatus = thermal,
        thermalSignalAvailable = thermalAvailable,
    )

    @Test
    fun selectsFullWhenSafe() {
        val decision = RuntimePolicy.selectPreflight(
            mapOf(AnalysisProfile.FULL to observation(35.0)),
        )
        assertEquals(AnalysisProfile.FULL, decision.profile)
    }

    @Test
    fun fallsBackWhenFullExceedsDutyBudget() {
        val decision = RuntimePolicy.selectPreflight(
            mapOf(
                AnalysisProfile.FULL to observation(80.0),
                AnalysisProfile.BALANCED to observation(75.0),
            ),
        )
        assertEquals(AnalysisProfile.BALANCED, decision.profile)
    }

    @Test
    fun safetyOnlyKeepsCheapChecksButDisablesHands() {
        val profile = AnalysisProfile.SAFETY_ONLY
        val gate = AnalyzerGate(profile)

        assertEquals(1.5, profile.sampleHz, 0.0)
        assertEquals(0.0, profile.handSampleHz, 0.0)
        assertTrue(gate.shouldAnalyze(0))
        assertFalse(gate.shouldAnalyze(500_000_000))
        assertTrue(gate.shouldAnalyze(667_000_000))
    }

    @Test
    fun writersOnlyIsTheTrueNoAnalysisControl() {
        val gate = AnalyzerGate(AnalysisProfile.WRITERS_ONLY)

        assertFalse(gate.shouldAnalyze(0))
        assertFalse(gate.shouldAnalyze(Long.MAX_VALUE))
    }

    @Test
    fun writerDropsDisableMlIfTheyAffectEveryProfile() {
        val observations = listOf(
            AnalysisProfile.FULL,
            AnalysisProfile.BALANCED,
            AnalysisProfile.LOW_POWER,
        ).associateWith { observation(20.0, drops = 1) }
        val decision = RuntimePolicy.selectPreflight(observations)
        assertEquals(AnalysisProfile.SAFETY_ONLY, decision.profile)
    }

    @Test
    fun runtimeNeverUpgrades() {
        val decision = RuntimePolicy.degrade(
            AnalysisProfile.BALANCED,
            RuntimeObservation(
                writerDroppedFrames = 0,
                writerGapRegressionMs = 0.0,
                analyzerQueueDepth = 0,
                thermalStatus = ThermalStatus.NONE,
                thermalSignalAvailable = true,
            ),
        )
        assertEquals(AnalysisProfile.BALANCED, decision.profile)
    }

    @Test
    fun analyzerGateIsMonotonicAndSparse() {
        val gate = AnalyzerGate(AnalysisProfile.FULL)
        assertTrue(gate.shouldAnalyze(0))
        assertFalse(gate.shouldAnalyze(250_000_000))
        assertTrue(gate.shouldAnalyze(500_000_000))
        assertTrue(gate.degradeTo(AnalysisProfile.LOW_POWER))
        assertFalse(gate.degradeTo(AnalysisProfile.FULL))
        assertFalse(gate.shouldAnalyze(2_000_000_000))
        assertTrue(gate.shouldAnalyze(5_000_000_000))
    }

    @Test
    fun analyzerGateResetStartsANewSessionWithoutOldCadenceOrProfile() {
        val gate = AnalyzerGate(AnalysisProfile.FULL)
        assertTrue(gate.shouldAnalyze(1_000_000_000L))
        assertTrue(gate.degradeTo(AnalysisProfile.LOW_POWER))
        assertFalse(gate.shouldAnalyze(1_100_000_000L))

        gate.reset(AnalysisProfile.FULL)

        assertEquals(AnalysisProfile.FULL, gate.currentProfile())
        assertTrue(gate.shouldAnalyze(1_100_000_000L))
    }
}
