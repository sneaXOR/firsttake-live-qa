package dev.firsttake.probe

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.roundToInt

data class YuvPlaneView(
    val buffer: ByteBuffer,
    val rowStride: Int,
    val pixelStride: Int,
)

data class Yuv420FrameView(
    val width: Int,
    val height: Int,
    val y: YuvPlaneView,
    val u: YuvPlaneView,
    val v: YuvPlaneView,
)

object Yuv420BitmapConverter {
    fun fromImageProxy(image: ImageProxy): Yuv420FrameView {
        require(image.planes.size >= 3) {
            "YUV_420_888 requires three planes"
        }
        return Yuv420FrameView(
            width = image.width,
            height = image.height,
            y = image.planes[0].asView(),
            u = image.planes[1].asView(),
            v = image.planes[2].asView(),
        )
    }

    fun convert(
        frame: Yuv420FrameView,
        targetWidth: Int,
        cropTopRatio: Double = 0.0,
    ): Bitmap {
        require(targetWidth > 0)
        require(cropTopRatio in 0.0..<1.0)
        val cropTop = (frame.height * cropTopRatio).roundToInt()
            .coerceIn(0, frame.height - 1)
        val cropHeight = frame.height - cropTop
        val targetHeight = (
            targetWidth.toDouble() * cropHeight / frame.width
            ).roundToInt().coerceAtLeast(1)
        val pixels = convertToArgb(
            frame = frame,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            cropTop = cropTop,
        )
        return Bitmap.createBitmap(
            pixels,
            targetWidth,
            targetHeight,
            Bitmap.Config.ARGB_8888,
        )
    }

    internal fun convertToArgb(
        frame: Yuv420FrameView,
        targetWidth: Int,
        targetHeight: Int,
        cropTop: Int = 0,
    ): IntArray {
        require(frame.width > 0 && frame.height > 0)
        require(targetWidth > 0 && targetHeight > 0)
        require(cropTop in 0 until frame.height)
        val cropHeight = frame.height - cropTop
        return IntArray(targetWidth * targetHeight) { outputIndex ->
            val outputX = outputIndex % targetWidth
            val outputY = outputIndex / targetWidth
            val sourceX = outputX * frame.width / targetWidth
            val sourceY = cropTop + outputY * cropHeight / targetHeight
            val yValue = sample(frame.y, sourceX, sourceY)
            val uValue = sample(frame.u, sourceX / 2, sourceY / 2)
            val vValue = sample(frame.v, sourceX / 2, sourceY / 2)
            yuvToArgb(yValue, uValue, vValue)
        }
    }

    private fun sample(plane: YuvPlaneView, x: Int, y: Int): Int {
        val index = plane.buffer.position() +
            y * plane.rowStride +
            x * plane.pixelStride
        require(index in 0 until plane.buffer.limit()) {
            "YUV plane layout exceeds buffer bounds"
        }
        return plane.buffer.get(index).toInt() and 0xff
    }

    private fun yuvToArgb(y: Int, u: Int, v: Int): Int {
        val adjustedY = (y - 16).coerceAtLeast(0)
        val adjustedU = u - 128
        val adjustedV = v - 128
        val red = ((298 * adjustedY + 409 * adjustedV + 128) shr 8)
            .coerceIn(0, 255)
        val green = (
            (298 * adjustedY - 100 * adjustedU - 208 * adjustedV + 128) shr 8
            ).coerceIn(0, 255)
        val blue = ((298 * adjustedY + 516 * adjustedU + 128) shr 8)
            .coerceIn(0, 255)
        return (0xff shl 24) or
            (red shl 16) or
            (green shl 8) or
            blue
    }

    private fun ImageProxy.PlaneProxy.asView(): YuvPlaneView =
        YuvPlaneView(
            buffer = buffer.duplicate(),
            rowStride = rowStride,
            pixelStride = pixelStride,
        )
}
