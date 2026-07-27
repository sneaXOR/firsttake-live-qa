package dev.firsttake.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandBaselinePolicyTest {
    @Test
    fun `one interior hand is enough for a positive candidate`() {
        val result = HandBaselinePolicy.combine(
            global = HandViewEvidence(0, emptyList()),
            bottom = HandViewEvidence(1, listOf(centerBox())),
            inferenceNs = 10,
        )

        assertEquals(
            HandBaselineStatus.HAND_VISIBLE_CANDIDATE,
            result.status,
        )
    }

    @Test
    fun `zero detections remain unknown rather than negative`() {
        val result = HandBaselinePolicy.combine(
            global = HandViewEvidence(0, emptyList()),
            bottom = HandViewEvidence(0, emptyList()),
            inferenceNs = 10,
        )

        assertEquals(HandBaselineStatus.UNKNOWN, result.status)
    }

    @Test
    fun `one interior global detection is a positive candidate`() {
        val result = HandBaselinePolicy.combine(
            global = HandViewEvidence(
                1,
                listOf(centerBox(0.4)),
            ),
            bottom = null,
            inferenceNs = 10,
        )

        assertEquals(
            HandBaselineStatus.HAND_VISIBLE_CANDIDATE,
            result.status,
        )
    }

    @Test
    fun `one detection touching edge becomes an edge risk candidate`() {
        val result = HandBaselinePolicy.combine(
            global = HandViewEvidence(
                1,
                listOf(
                    HandBox(0.0, 0.2, 0.2, 0.6),
                ),
            ),
            bottom = null,
            inferenceNs = 10,
        )

        assertEquals(
            HandBaselineStatus.HAND_EDGE_RISK_CANDIDATE,
            result.status,
        )
    }

    @Test
    fun `edge evidence from either view wins over an interior view`() {
        val result = HandBaselinePolicy.combine(
            global = HandViewEvidence(
                1,
                listOf(
                    centerBox(0.6),
                ),
            ),
            bottom = HandViewEvidence(
                1,
                listOf(HandBox(0.94, 0.2, 0.99, 0.6)),
            ),
            inferenceNs = 10,
        )

        assertEquals(
            HandBaselineStatus.HAND_EDGE_RISK_CANDIDATE,
            result.status,
        )
    }

    @Test
    fun `model errors are explicit and never become hand absence`() {
        val result = HandBaselinePolicy.combine(
            global = null,
            bottom = null,
            inferenceNs = 10,
            error = "model missing",
        )

        assertEquals(HandBaselineStatus.MODEL_ERROR, result.status)
        assertEquals("model missing", result.error)
    }

    @Test
    fun `edge margin is conservative and deterministic`() {
        assertTrue(HandBox(0.07, 0.2, 0.5, 0.8).touchesFrameEdge)
        assertFalse(HandBox(0.09, 0.2, 0.5, 0.8).touchesFrameEdge)
    }

    @Test
    fun `bottom crop coordinates map back to the full frame`() {
        val cropBox = HandBox(
            minimumX = 0.2,
            minimumY = 0.0,
            maximumX = 0.5,
            maximumY = 1.0,
        )

        val fullFrameBox = cropBox.remapFromVerticalCrop(
            cropTopRatio = 0.4,
            cropHeightRatio = 0.6,
        )

        assertEquals(0.4, fullFrameBox.minimumY, 1e-9)
        assertEquals(1.0, fullFrameBox.maximumY, 1e-9)
        assertFalse(fullFrameBox.minimumY <= 0.08)
        assertTrue(fullFrameBox.touchesFrameEdge)
    }

    private fun centerBox(offsetX: Double = 0.3): HandBox =
        HandBox(
            minimumX = offsetX,
            minimumY = 0.2,
            maximumX = (offsetX + 0.2).coerceAtMost(0.9),
            maximumY = 0.8,
        )
}
