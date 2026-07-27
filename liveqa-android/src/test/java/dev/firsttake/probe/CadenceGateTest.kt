package dev.firsttake.probe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CadenceGateTest {
    @Test
    fun independentlyControlsSparseWork() {
        val gate = CadenceGate()
        assertTrue(gate.shouldRun(0, 1.0))
        assertFalse(gate.shouldRun(500_000_000, 1.0))
        assertTrue(gate.shouldRun(1_000_000_000, 1.0))
        assertFalse(gate.shouldRun(2_000_000_000, 0.0))
    }

    @Test
    fun resetDoesNotCarryCadenceAcrossSessions() {
        val gate = CadenceGate()
        assertTrue(gate.shouldRun(5_000_000_000, 0.25))
        assertFalse(gate.shouldRun(5_500_000_000, 0.25))
        gate.reset()
        assertTrue(gate.shouldRun(5_500_000_000, 0.25))
    }
}
