package dev.firsttake.probe

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraClockContractTest {
    @Test
    fun `realtime camera timestamps are comparable to IMU monotonic time`() {
        val contract = CameraClockContract.from(
            cameraId = "0",
            rawTimestampSource =
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
        )

        assertEquals("REALTIME", contract.timestampSourceName)
        assertTrue(contract.comparableToElapsedRealtime)
    }

    @Test
    fun `unknown camera timestamps force RGB IMU abstention`() {
        val contract = CameraClockContract.from(
            cameraId = "1",
            rawTimestampSource =
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN,
        )

        assertEquals("UNKNOWN", contract.timestampSourceName)
        assertFalse(contract.comparableToElapsedRealtime)
    }

    @Test
    fun `missing or vendor values never become comparable by assumption`() {
        assertFalse(
            CameraClockContract.unavailable.comparableToElapsedRealtime,
        )
        assertFalse(
            CameraClockContract.from("2", 99)
                .comparableToElapsedRealtime,
        )
    }
}
