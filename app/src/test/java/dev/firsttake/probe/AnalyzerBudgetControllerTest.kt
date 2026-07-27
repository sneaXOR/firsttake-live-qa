package dev.firsttake.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerBudgetControllerTest {
    @Test
    fun staysAtFullWhenP95FitsDutyCycle() {
        val gate = AnalyzerGate(AnalysisProfile.FULL)
        val controller = AnalyzerBudgetController(gate)

        repeat(12) {
            assertNull(
                controller.observe(
                    analysisNs = 40_000_000,
                    thermalStatus = ThermalStatus.NONE,
                    thermalSignalAvailable = true,
                ),
            )
        }

        assertEquals(AnalysisProfile.FULL, gate.currentProfile())
    }

    @Test
    fun degradesOnlyAfterPersistentBudgetBreaches() {
        val gate = AnalyzerGate(AnalysisProfile.FULL)
        val controller = AnalyzerBudgetController(
            gate = gate,
            windowCapacity = 3,
            minimumSamples = 3,
            consecutiveBreachesRequired = 3,
            budgetWarmupSamples = 3,
        )

        repeat(4) {
            assertNull(
                controller.observe(
                    analysisNs = 80_000_000,
                    thermalStatus = ThermalStatus.NONE,
                    thermalSignalAvailable = true,
                ),
            )
        }
        val transition = controller.observe(
            analysisNs = 80_000_000,
            thermalStatus = ThermalStatus.NONE,
            thermalSignalAvailable = true,
        )

        assertEquals(AnalysisProfile.BALANCED, transition?.newProfile)
        assertEquals(AnalysisProfile.BALANCED, gate.currentProfile())
        assertTrue(
            transition?.reasons?.contains("ANALYSIS_DUTY_CYCLE_EXCEEDED") == true,
        )
    }

    @Test
    fun aHealthySampleBreaksTheBreachStreak() {
        val gate = AnalyzerGate(AnalysisProfile.FULL)
        val controller = AnalyzerBudgetController(
            gate = gate,
            windowCapacity = 3,
            minimumSamples = 3,
            consecutiveBreachesRequired = 2,
            budgetWarmupSamples = 3,
        )

        repeat(3) {
            controller.observe(
                80_000_000,
                ThermalStatus.NONE,
                true,
            )
        }
        controller.observe(5_000_000, ThermalStatus.NONE, true)
        controller.observe(5_000_000, ThermalStatus.NONE, true)
        assertEquals(AnalysisProfile.FULL, gate.currentProfile())
    }

    @Test
    fun `single cold inference does not degrade the session`() {
        val gate = AnalyzerGate(AnalysisProfile.FULL)
        val controller = AnalyzerBudgetController(gate)

        repeat(2) {
            assertNull(
                controller.observe(
                    analysisNs = 160_000_000,
                    thermalStatus = ThermalStatus.NONE,
                    thermalSignalAvailable = true,
                    estimatedDutyCycle = 0.17,
                ),
            )
        }
        repeat(10) {
            assertNull(
                controller.observe(
                    analysisNs = 45_000_000,
                    thermalStatus = ThermalStatus.NONE,
                    thermalSignalAvailable = true,
                    estimatedDutyCycle = 0.09,
                ),
            )
        }

        assertEquals(AnalysisProfile.FULL, gate.currentProfile())
    }

    @Test
    fun severeThermalPressureImmediatelyKeepsOnlyCheapSafetyChecks() {
        val gate = AnalyzerGate(AnalysisProfile.FULL)
        val controller = AnalyzerBudgetController(gate)

        val transition = controller.observe(
            analysisNs = 10_000_000,
            thermalStatus = ThermalStatus.SEVERE,
            thermalSignalAvailable = true,
        )

        assertEquals(AnalysisProfile.SAFETY_ONLY, transition?.newProfile)
        assertEquals(AnalysisProfile.SAFETY_ONLY, gate.currentProfile())
    }

    @Test
    fun criticalThermalPressureDisablesEvenSafetyAnalysis() {
        val gate = AnalyzerGate(AnalysisProfile.SAFETY_ONLY)
        val controller = AnalyzerBudgetController(gate)

        val transition = controller.observe(
            analysisNs = 10_000_000,
            thermalStatus = ThermalStatus.CRITICAL,
            thermalSignalAvailable = true,
        )

        assertEquals(AnalysisProfile.WRITERS_ONLY, transition?.newProfile)
        assertEquals(AnalysisProfile.WRITERS_ONLY, gate.currentProfile())
        assertTrue(
            transition?.reasons?.contains(
                "THERMAL_CRITICAL_WRITERS_ONLY",
            ) == true,
        )
    }

    @Test
    fun unknownThermalSignalDoesNotCreateAFalseThermalAlarm() {
        val gate = AnalyzerGate(AnalysisProfile.FULL)
        val controller = AnalyzerBudgetController(gate)

        assertNull(
            controller.observe(
                analysisNs = 10_000_000,
                thermalStatus = ThermalStatus.UNKNOWN,
                thermalSignalAvailable = false,
            ),
        )
        assertEquals(AnalysisProfile.FULL, gate.currentProfile())
    }
}
