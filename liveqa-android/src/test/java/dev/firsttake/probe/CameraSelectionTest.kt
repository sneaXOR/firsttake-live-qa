package dev.firsttake.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSelectionTest {
    @Test
    fun `chooses widest rear camera that really supports FHD`() {
        val chosen = CameraSelectionPolicy.chooseWidestFhd(
            listOf(
                CameraCandidate("0", null, 5.56, 8.16, true),
                CameraCandidate("0", "2", 1.64, 3.67, true),
                CameraCandidate("0", "3", 0.90, 3.67, false),
            ),
        )

        assertEquals("2", chosen?.physicalCameraId)
        assertTrue((chosen?.horizontalFovDegrees ?: 0.0) > 90.0)
    }

    @Test
    fun `never picks an unsupported lens just because it is wider`() {
        val chosen = CameraSelectionPolicy.chooseWidestFhd(
            listOf(
                CameraCandidate("0", null, 5.56, 8.16, true),
                CameraCandidate("0", "2", 1.00, 3.67, false),
            ),
        )

        assertEquals(null, chosen?.physicalCameraId)
    }
}
