package dev.firsttake.probe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorFeedbackStateTest {
    @Test
    fun recoveryOfOneSignalDoesNotHideAnotherActiveWarning() {
        val state = OperatorFeedbackState()
        state.apply(quality(FrameDefect.DARK_OR_COVERED, true))
        state.apply(quality(FrameDefect.POSSIBLE_BLUR, true))
        state.apply(quality(FrameDefect.DARK_OR_COVERED, false))

        val rendered = state.render(AnalysisProfile.FULL)
        assertFalse(rendered.contains("add light"))
        assertTrue(rendered.contains("hold the camera steady"))
        assertTrue(state.hasActionableProblems())
    }

    @Test
    fun recorderRiskIsShownBeforeVisualWarnings() {
        val state = OperatorFeedbackState()
        state.apply(quality(FrameDefect.DARK_OR_COVERED, true))
        state.apply(
            RecorderHealthEvent(
                previousState = RecorderHealthState.HEALTHY,
                newState = RecorderHealthState.STORAGE_CRITICAL,
                action = RecorderHealthAction.GRACEFUL_STOP,
                reason = "STORAGE_CRITICAL",
                estimatedStorageSecondsRemaining = 10.0,
                usableStorageBytes = 10,
            ),
        )

        val rendered = state.render(AnalysisProfile.BALANCED)
        assertTrue(
            rendered.indexOf("Storage critical") <
                rendered.indexOf("add light"),
        )
    }

    @Test
    fun unknownHandEvidenceNeverEntersFeedbackState() {
        val state = OperatorFeedbackState()
        assertFalse(state.hasActionableProblems())
        assertFalse(
            state.render(AnalysisProfile.FULL).contains("hands"),
        )
    }

    private fun quality(
        defect: FrameDefect,
        alert: Boolean,
    ) = QualityTransition(
        defect = defect,
        kind = if (alert) {
            QualityTransitionKind.ALERT
        } else {
            QualityTransitionKind.RECOVERED
        },
        observedAtElapsedRealtimeNs = 1,
        reason = "test",
    )
}
