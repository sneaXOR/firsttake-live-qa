package dev.firsttake.probe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.security.MessageDigest

data class HandBox(
    val minimumX: Double,
    val minimumY: Double,
    val maximumX: Double,
    val maximumY: Double,
) {
    val touchesFrameEdge: Boolean
        get() = minimumX <= EDGE_MARGIN ||
            minimumY <= EDGE_MARGIN ||
            maximumX >= 1.0 - EDGE_MARGIN ||
            maximumY >= 1.0 - EDGE_MARGIN

    fun remapFromVerticalCrop(
        cropTopRatio: Double,
        cropHeightRatio: Double,
    ): HandBox {
        require(cropTopRatio in 0.0..<1.0)
        require(cropHeightRatio > 0.0)
        require(cropTopRatio + cropHeightRatio <= 1.0 + 1e-9)
        return copy(
            minimumY = cropTopRatio + minimumY * cropHeightRatio,
            maximumY = cropTopRatio + maximumY * cropHeightRatio,
        )
    }

    private companion object {
        // Give the operator time to correct before landmarks cross the frame.
        // Temporal persistence in HandVisibilityMonitor prevents one-frame
        // edge proximity from becoming an alert.
        const val EDGE_MARGIN = 0.08
    }
}

data class HandViewEvidence(
    val detectedHands: Int,
    val boxes: List<HandBox>,
)

enum class HandBaselineStatus {
    HAND_VISIBLE_CANDIDATE,
    HAND_EDGE_RISK_CANDIDATE,
    UNKNOWN,
    MODEL_ERROR,
}

data class HandBaselineObservation(
    val status: HandBaselineStatus,
    val global: HandViewEvidence?,
    val bottom: HandViewEvidence?,
    val inferenceNs: Long,
    val modelSha256: String?,
    val error: String?,
)

object HandBaselinePolicy {
    fun combine(
        global: HandViewEvidence?,
        bottom: HandViewEvidence?,
        inferenceNs: Long,
        error: String? = null,
    ): HandBaselineObservation {
        if (error != null) {
            return HandBaselineObservation(
                status = HandBaselineStatus.MODEL_ERROR,
                global = global,
                bottom = bottom,
                inferenceNs = inferenceNs,
                modelSha256 = null,
                error = error,
            )
        }
        val handViews = listOfNotNull(global, bottom)
            .filter { it.detectedHands >= 1 }
        val status = when {
            handViews.any { view ->
                view.boxes.any { it.touchesFrameEdge }
            } ->
                HandBaselineStatus.HAND_EDGE_RISK_CANDIDATE
            handViews.isNotEmpty() ->
                HandBaselineStatus.HAND_VISIBLE_CANDIDATE
            else -> HandBaselineStatus.UNKNOWN
        }
        return HandBaselineObservation(
            status = status,
            global = global,
            bottom = bottom,
            inferenceNs = inferenceNs,
            modelSha256 = null,
            error = null,
        )
    }
}

/**
 * Official MediaPipe baseline. Zero detections map to UNKNOWN. UNKNOWN is
 * non-actionable until the temporal monitor has armed tracking from repeated
 * positive observations; it can then mean that a tracked hand was lost. One
 * safely framed hand is sufficient positive evidence for this contract.
 */
class OnDeviceHandBaseline(context: Context) : AutoCloseable {
    private val landmarker: HandLandmarker
    val modelSha256: String

    init {
        modelSha256 = context.assets.open(MODEL_ASSET).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1_024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) {
                    break
                }
                digest.update(buffer, 0, count)
            }
            digest.digest()
                .joinToString("") { byte -> "%02X".format(byte) }
        }
        check(modelSha256 == EXPECTED_MODEL_SHA256) {
            "hand model SHA-256 mismatch: $modelSha256"
        }
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.IMAGE)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.35f)
            .setMinHandPresenceConfidence(0.35f)
            .setMinTrackingConfidence(0.35f)
            .build()
        landmarker = HandLandmarker.createFromOptions(context, options)
    }

    fun analyze(image: ImageProxy): HandBaselineObservation {
        val startedNs = SystemClock.elapsedRealtimeNanos()
        return try {
            val frame = Yuv420BitmapConverter.fromImageProxy(image)
            val sensorBitmap = Yuv420BitmapConverter.convert(
                frame = frame,
                targetWidth = INPUT_WIDTH,
            )
            analyzeOwnedBitmap(
                sensorBitmap = sensorBitmap,
                rotationDegrees = image.imageInfo.rotationDegrees,
                startedNs = startedNs,
            )
        } catch (error: Exception) {
            errorObservation(startedNs, error)
        }
    }

    /**
     * Runs exactly the same model and crop policy as the live CameraX path.
     * This is used by the device fixture probe, not by the operator UI.
     */
    fun analyze(bitmap: Bitmap): HandBaselineObservation {
        val startedNs = SystemClock.elapsedRealtimeNanos()
        return try {
            val targetHeight = (
                INPUT_WIDTH.toDouble() * bitmap.height / bitmap.width
                ).toInt().coerceAtLeast(1)
            val ownedBitmap = Bitmap.createScaledBitmap(
                bitmap,
                INPUT_WIDTH,
                targetHeight,
                true,
            ).let { scaled ->
                if (scaled === bitmap) {
                    bitmap.copy(
                        bitmap.config ?: Bitmap.Config.ARGB_8888,
                        false,
                    )
                } else {
                    scaled
                }
            }
            analyzeOwnedBitmap(
                sensorBitmap = ownedBitmap,
                rotationDegrees = 0,
                startedNs = startedNs,
            )
        } catch (error: Exception) {
            errorObservation(startedNs, error)
        }
    }

    override fun close() {
        landmarker.close()
    }

    private fun analyzeOwnedBitmap(
        sensorBitmap: Bitmap,
        rotationDegrees: Int,
        startedNs: Long,
    ): HandBaselineObservation {
        val rotated = rotate(
            bitmap = sensorBitmap,
            rotationDegrees = rotationDegrees,
        )
        val bottom = bottomCrop(rotated)
        return try {
            val globalEvidence = detect(rotated)
            val bottomEvidence = detect(bottom) { box ->
                box.remapFromVerticalCrop(
                    cropTopRatio = BOTTOM_CROP_TOP_RATIO,
                    cropHeightRatio = 1.0 - BOTTOM_CROP_TOP_RATIO,
                )
            }
            HandBaselinePolicy.combine(
                global = globalEvidence,
                bottom = bottomEvidence,
                inferenceNs = SystemClock.elapsedRealtimeNanos() - startedNs,
            ).copy(modelSha256 = modelSha256)
        } finally {
            bottom.recycle()
            if (rotated !== sensorBitmap) {
                rotated.recycle()
            }
            sensorBitmap.recycle()
        }
    }

    private fun errorObservation(
        startedNs: Long,
        error: Exception,
    ): HandBaselineObservation = HandBaselinePolicy.combine(
        global = null,
        bottom = null,
        inferenceNs = SystemClock.elapsedRealtimeNanos() - startedNs,
        error = error.message ?: error.javaClass.simpleName,
    )

    private fun detect(
        bitmap: Bitmap,
        transformBox: (HandBox) -> HandBox = { it },
    ): HandViewEvidence {
        val image = BitmapImageBuilder(bitmap).build()
        return try {
            evidence(landmarker.detect(image), transformBox)
        } finally {
            image.close()
        }
    }

    private fun evidence(
        result: HandLandmarkerResult,
        transformBox: (HandBox) -> HandBox,
    ): HandViewEvidence {
        val boxes = result.landmarks().mapNotNull { landmarks ->
            if (landmarks.isEmpty()) {
                return@mapNotNull null
            }
            transformBox(
                HandBox(
                    minimumX = landmarks.minOf { it.x().toDouble() },
                    minimumY = landmarks.minOf { it.y().toDouble() },
                    maximumX = landmarks.maxOf { it.x().toDouble() },
                    maximumY = landmarks.maxOf { it.y().toDouble() },
                ),
            )
        }
        return HandViewEvidence(
            detectedHands = boxes.size,
            boxes = boxes,
        )
    }

    private fun rotate(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees % 360 == 0) {
            return bitmap
        }
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )
    }

    private fun bottomCrop(bitmap: Bitmap): Bitmap {
        val top = (bitmap.height * BOTTOM_CROP_TOP_RATIO).toInt()
            .coerceIn(0, bitmap.height - 1)
        val crop = Bitmap.createBitmap(
            bitmap,
            0,
            top,
            bitmap.width,
            bitmap.height - top,
        )
        val targetHeight = (
            INPUT_WIDTH.toDouble() * crop.height / crop.width
            ).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(
            crop,
            INPUT_WIDTH,
            targetHeight,
            true,
        )
        if (scaled !== crop) {
            crop.recycle()
        }
        return scaled
    }

    private companion object {
        const val MODEL_ASSET = "hand_landmarker.task"
        const val EXPECTED_MODEL_SHA256 =
            "FBC2A30080C3C557093B5DDFC334698132EB341044CCEE322CCF8BCF3607CDE1"
        const val INPUT_WIDTH = 320
        const val BOTTOM_CROP_TOP_RATIO = 0.40
    }
}
