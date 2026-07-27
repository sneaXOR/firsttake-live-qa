package dev.firsttake.probe

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureFeedbackTest {
    @Test
    fun parsesExplicitModesAndUsesSafeInteractiveDefault() {
        assertEquals(
            FeedbackMode.SILENT,
            FeedbackMode.parse("silent"),
        )
        assertEquals(
            FeedbackMode.HAPTIC,
            FeedbackMode.parse("HAPTIC"),
        )
        assertEquals(
            FeedbackMode.HAPTIC_AND_VOICE,
            FeedbackMode.parse(null),
        )
        assertEquals(
            FeedbackMode.HAPTIC_AND_VOICE,
            FeedbackMode.parse("not-a-mode"),
        )
    }
}
