package dev.firsttake.probe

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import kotlin.math.atan

data class CameraCandidate(
    val logicalCameraId: String,
    val physicalCameraId: String?,
    val focalLengthMm: Double?,
    val sensorWidthMm: Double?,
    val supportsFhd: Boolean,
) {
    val horizontalFovDegrees: Double?
        get() = if (
            focalLengthMm != null &&
            focalLengthMm > 0.0 &&
            sensorWidthMm != null &&
            sensorWidthMm > 0.0
        ) {
            Math.toDegrees(
                2.0 * atan(sensorWidthMm / (2.0 * focalLengthMm)),
            )
        } else {
            null
        }
}

data class CameraSelectionEvidence(
    val logicalCameraId: String?,
    val physicalCameraId: String?,
    val focalLengthMm: Double?,
    val horizontalFovDegrees: Double?,
    val supportsFhd: Boolean,
    val policy: String,
    val minimumZoomRatio: Double? = null,
    val requestedZoomRatio: Double? = null,
    val appliedZoomRatio: Double? = null,
) {
    val displayId: String?
        get() = when {
            logicalCameraId == null -> null
            physicalCameraId == null -> logicalCameraId
            else -> "$logicalCameraId/$physicalCameraId"
        }

    companion object {
        val unavailable = CameraSelectionEvidence(
            logicalCameraId = null,
            physicalCameraId = null,
            focalLengthMm = null,
            horizontalFovDegrees = null,
            supportsFhd = false,
            policy = "UNAVAILABLE",
        )
    }
}

data class ResolvedCameraSelection(
    val selector: CameraSelector,
    val evidence: CameraSelectionEvidence,
)

object CameraSelectionPolicy {
    fun chooseWidestFhd(
        candidates: List<CameraCandidate>,
    ): CameraCandidate? {
        val supported = candidates.filter { it.supportsFhd }
        return supported.maxByOrNull {
            it.horizontalFovDegrees ?: Double.NEGATIVE_INFINITY
        } ?: candidates.firstOrNull()
    }
}

@ExperimentalCamera2Interop
object AndroidCameraSelectionResolver {
    fun resolve(
        context: Context,
        availableCameraInfos: List<CameraInfo>,
    ): ResolvedCameraSelection {
        val cameraManager =
            context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val logicalBackIds = availableCameraInfos.mapNotNull { info ->
            val camera2Info = Camera2CameraInfo.from(info)
            val facing = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.LENS_FACING,
            )
            camera2Info.cameraId.takeIf {
                facing == CameraCharacteristics.LENS_FACING_BACK
            }
        }
        val candidates = logicalBackIds.flatMap { logicalId ->
            val logical = cameraManager.getCameraCharacteristics(logicalId)
            buildList {
                add(candidate(logicalId, null, logical))
                logical.physicalCameraIds.sorted().forEach { physicalId ->
                    runCatching {
                        cameraManager.getCameraCharacteristics(physicalId)
                    }.getOrNull()?.let { physical ->
                        add(candidate(logicalId, physicalId, physical))
                    }
                }
            }
        }
        val chosen = CameraSelectionPolicy.chooseWidestFhd(candidates)
            ?: return ResolvedCameraSelection(
                selector = CameraSelector.DEFAULT_BACK_CAMERA,
                evidence = CameraSelectionEvidence.unavailable,
            )
        val builder = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { infos ->
                infos.filter { info ->
                    Camera2CameraInfo.from(info).cameraId ==
                        chosen.logicalCameraId
                }
            }
        chosen.physicalCameraId?.let(builder::setPhysicalCameraId)
        return ResolvedCameraSelection(
            selector = builder.build(),
            evidence = CameraSelectionEvidence(
                logicalCameraId = chosen.logicalCameraId,
                physicalCameraId = chosen.physicalCameraId,
                focalLengthMm = chosen.focalLengthMm,
                horizontalFovDegrees = chosen.horizontalFovDegrees,
                supportsFhd = chosen.supportsFhd,
                policy = if (chosen.physicalCameraId == null) {
                    "WIDEST_PUBLIC_REAR_FHD"
                } else {
                    "WIDEST_PHYSICAL_REAR_FHD"
                },
            ),
        )
    }

    fun logicalFallback(logicalCameraId: String?): ResolvedCameraSelection {
        if (logicalCameraId == null) {
            return ResolvedCameraSelection(
                CameraSelector.DEFAULT_BACK_CAMERA,
                CameraSelectionEvidence.unavailable.copy(
                    policy = "DEFAULT_BACK_FALLBACK",
                ),
            )
        }
        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { infos ->
                infos.filter { info ->
                    Camera2CameraInfo.from(info).cameraId == logicalCameraId
                }
            }
            .build()
        return ResolvedCameraSelection(
            selector,
            CameraSelectionEvidence(
                logicalCameraId = logicalCameraId,
                physicalCameraId = null,
                focalLengthMm = null,
                horizontalFovDegrees = null,
                supportsFhd = true,
                policy = "LOGICAL_BACK_FALLBACK",
            ),
        )
    }

    private fun candidate(
        logicalId: String,
        physicalId: String?,
        characteristics: CameraCharacteristics,
    ): CameraCandidate {
        val focalLength = characteristics.get(
            CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
        )?.filter { it > 0f }?.minOrNull()?.toDouble()
        val sensorWidth = characteristics.get(
            CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE,
        )?.width?.toDouble()
        val streamMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
        )
        val outputSizes = buildList {
            streamMap?.getOutputSizes(MediaRecorder::class.java)
                ?.let(::addAll)
            streamMap?.getOutputSizes(SurfaceTexture::class.java)
                ?.let(::addAll)
        }
        val supportsFhd = outputSizes.any { size ->
            size.width == 1_920 && size.height == 1_080 ||
                size.width == 1_080 && size.height == 1_920
        }
        return CameraCandidate(
            logicalCameraId = logicalId,
            physicalCameraId = physicalId,
            focalLengthMm = focalLength,
            sensorWidthMm = sensorWidth,
            supportsFhd = supportsFhd,
        )
    }
}
