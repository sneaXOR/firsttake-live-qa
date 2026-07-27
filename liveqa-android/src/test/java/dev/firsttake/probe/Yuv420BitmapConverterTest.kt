package dev.firsttake.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class Yuv420BitmapConverterTest {
    @Test
    fun `neutral chroma produces grayscale ARGB`() {
        val frame = frame(
            width = 4,
            height = 4,
            yBytes = ByteArray(16) { 128.toByte() },
            uvBytes = ByteArray(4) { 128.toByte() },
        )

        val pixels = Yuv420BitmapConverter.convertToArgb(
            frame = frame,
            targetWidth = 2,
            targetHeight = 2,
        )

        pixels.forEach { pixel ->
            val red = pixel ushr 16 and 0xff
            val green = pixel ushr 8 and 0xff
            val blue = pixel and 0xff
            assertEquals(red, green)
            assertEquals(green, blue)
            assertTrue(red in 120..140)
        }
    }

    @Test
    fun `crop top samples only lower source rows`() {
        val y = ByteArray(16) { index ->
            val row = index / 4
            if (row < 2) 16 else 235.toByte()
        }
        val frame = frame(
            width = 4,
            height = 4,
            yBytes = y,
            uvBytes = ByteArray(4) { 128.toByte() },
        )

        val pixels = Yuv420BitmapConverter.convertToArgb(
            frame = frame,
            targetWidth = 2,
            targetHeight = 1,
            cropTop = 2,
        )

        pixels.forEach { pixel ->
            assertTrue((pixel and 0xff) > 240)
        }
    }

    @Test
    fun `pixel and row strides are honored for chroma`() {
        val yPlane = YuvPlaneView(
            buffer = ByteBuffer.wrap(ByteArray(12) { 128.toByte() }),
            rowStride = 6,
            pixelStride = 1,
        )
        val uBytes = byteArrayOf(255.toByte(), 7, 255.toByte(), 7)
        val vBytes = byteArrayOf(128.toByte(), 7, 128.toByte(), 7)
        val frame = Yuv420FrameView(
            width = 4,
            height = 2,
            y = yPlane,
            u = YuvPlaneView(ByteBuffer.wrap(uBytes), 4, 2),
            v = YuvPlaneView(ByteBuffer.wrap(vBytes), 4, 2),
        )

        val pixels = Yuv420BitmapConverter.convertToArgb(
            frame = frame,
            targetWidth = 4,
            targetHeight = 2,
        )

        pixels.forEach { pixel ->
            val blue = pixel and 0xff
            val red = pixel ushr 16 and 0xff
            assertTrue(blue > red)
        }
    }

    private fun frame(
        width: Int,
        height: Int,
        yBytes: ByteArray,
        uvBytes: ByteArray,
    ): Yuv420FrameView = Yuv420FrameView(
        width = width,
        height = height,
        y = YuvPlaneView(
            buffer = ByteBuffer.wrap(yBytes),
            rowStride = width,
            pixelStride = 1,
        ),
        u = YuvPlaneView(
            buffer = ByteBuffer.wrap(uvBytes),
            rowStride = width / 2,
            pixelStride = 1,
        ),
        v = YuvPlaneView(
            buffer = ByteBuffer.wrap(uvBytes),
            rowStride = width / 2,
            pixelStride = 1,
        ),
    )
}
