package dev.firsttake.probe

import android.hardware.camera2.CameraMetadata

data class CameraClockContract(
    val cameraId: String?,
    val rawTimestampSource: Int?,
    val timestampSourceName: String,
    val comparableToElapsedRealtime: Boolean,
) {
    companion object {
        fun from(cameraId: String?, rawTimestampSource: Int?): CameraClockContract =
            when (rawTimestampSource) {
                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME ->
                    CameraClockContract(
                        cameraId = cameraId,
                        rawTimestampSource = rawTimestampSource,
                        timestampSourceName = "REALTIME",
                        comparableToElapsedRealtime = true,
                    )

                CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN ->
                    CameraClockContract(
                        cameraId = cameraId,
                        rawTimestampSource = rawTimestampSource,
                        timestampSourceName = "UNKNOWN",
                        comparableToElapsedRealtime = false,
                    )

                else -> CameraClockContract(
                    cameraId = cameraId,
                    rawTimestampSource = rawTimestampSource,
                    timestampSourceName = "UNAVAILABLE_OR_VENDOR_VALUE",
                    comparableToElapsedRealtime = false,
                )
            }

        val unavailable: CameraClockContract =
            from(cameraId = null, rawTimestampSource = null)
    }
}
