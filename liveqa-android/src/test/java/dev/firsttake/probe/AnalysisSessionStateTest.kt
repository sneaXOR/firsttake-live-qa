package dev.firsttake.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AnalysisSessionStateTest {
    @Test
    fun previewIsInactiveAndEachSessionGetsAFreshGeneration() {
        val state = AnalysisSessionState()
        assertEquals(0L, state.activeGeneration())

        val first = state.begin()
        assertEquals(first, state.activeGeneration())
        state.end(first)
        assertEquals(0L, state.activeGeneration())

        val second = state.begin()
        assertNotEquals(first, second)
        assertEquals(second, state.activeGeneration())
    }

    @Test
    fun staleSessionCannotEndANewerSession() {
        val state = AnalysisSessionState()
        val first = state.begin()
        val second = state.begin()

        state.end(first)

        assertEquals(second, state.activeGeneration())
    }
}
