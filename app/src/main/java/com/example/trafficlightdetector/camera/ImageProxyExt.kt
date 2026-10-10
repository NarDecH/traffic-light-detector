package com.example.trafficlightdetector.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import androidx.camera.core.ImageProxy
import java.nio.ByteOrder

/**
 * Converts a CameraX [ImageProxy] into an **upright ARGB_8888 [Bitmap]**.
 *
 * The colours matter here: a red / green traffic light is only distinguishable in
 * RGB, so the CameraX use case is configured with
 * `OUTPUT_IMAGE_FORMAT_RGBA_8888` (see `CameraController`). The YUV fallback is
 * kept so the analyzer still produces the right colours if the output format is
 * switched back to CameraX's default.
 *
 * The returned bitmap is a fresh instance each frame and safe to recycle by the
 * caller. The proxy itself is *not* closed here — `analyze()` owns that.
 */
fun ImageProxy.toUprightBitmap(): Bitmap {
    val source = when (format) {
        PixelFormat.RGBA_8888 -> copyRgba8888()
        else -> YuvToRgb.convert(this)
    }
    return source.rotate(imageInfo.rotationDegrees)
}

/**
 * Fast path for the RGBA pipeline. When the row stride is tight the whole plane
 * is copied with a single `copyPixelsFromBuffer`; padded strides (common on
 * devices that align rows) take the row-by-row branch.
 */
private fun ImageProxy.copyRgba8888(): Bitmap {
    val plane = planes[0]
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride.coerceAtLeast(1)
    val buffer = plane.buffer
    val paddedWidth = if (rowStride > 0) rowStride / pixelStride else width
    // `copyPixelsFromBuffer` reads from the current position, so only take the
    // memcpy path when the plane really starts at 0 with a tight stride.
    val tight = paddedWidth == width && pixelStride == 4 &&
        buffer.position() == 0 &&
        buffer.remaining() >= width * height * 4 &&
        buffer.order() == ByteOrder.nativeOrder()

    if (tight) {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(buffer)
        }
    }

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val limit = buffer.limit()
    val row = IntArray(width)
    for (y in 0 until height) {
        var offset = y * rowStride
        for (x in 0 until width) {
            row[x] = if (offset + 2 < limit) {
                val r = buffer.get(offset).toInt() and 0xFF
                val g = buffer.get(offset + 1).toInt() and 0xFF
                val b = buffer.get(offset + 2).toInt() and 0xFF
                val a = if (pixelStride >= 4 && offset + 3 < limit) {
                    buffer.get(offset + 3).toInt() and 0xFF
                } else {
                    0xFF
                }
                (a shl 24) or (r shl 16) or (g shl 8) or b
            } else {
                0xFF000000.toInt()
            }
            offset += pixelStride
        }
        bitmap.setPixels(row, 0, width, 0, y, width, 1)
    }
    return bitmap
}

/**
 * Fallback converter for `YUV_420_888` proxies (CameraX's default output
 * format). Handles both interleaved (pixelStride == 2, NV12/NV21) and planar
 * chroma, using the BT.601 full-range conversion.
 */
private object YuvToRgb {
    private var pixels = IntArray(0)
    private var row = IntArray(0)

    fun convert(image: ImageProxy): Bitmap {
        val width = image.width
        val height = image.height
        if (pixels.size < width * height) pixels = IntArray(width * height)
        if (row.size < width) row = IntArray(width)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride.coerceAtLeast(1)
        val uLimit = uBuffer.limit()
        val vLimit = vBuffer.limit()

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            val yRowStart = y * yRowStride
            val uvRowStart = (y / 2) * uvRowStride
            for (x in 0 until width) {
                val yValue = yBuffer.get(yRowStart + x).toInt() and 0xFF
                val uvOffset = uvRowStart + (x / 2) * uvPixelStride
                val uValue = if (uvOffset < uLimit) uBuffer.get(uvOffset).toInt() and 0xFF else 128
                val vValue = if (uvOffset < vLimit) vBuffer.get(uvOffset).toInt() and 0xFF else 128

                val yScaled = (yValue - 16).coerceAtLeast(0)
                val u = uValue - 128
                val v = vValue - 128
                val r = (1.164f * yScaled + 1.596f * v).toInt().coerceIn(0, 255)
                val g = (1.164f * yScaled - 0.392f * u - 0.813f * v).toInt().coerceIn(0, 255)
                val b = (1.164f * yScaled + 2.017f * u).toInt().coerceIn(0, 255)
                row[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            bitmap.setPixels(row, 0, width, 0, y, width, 1)
        }
        return bitmap
    }
}

/**
 * Rotates the frame into its upright orientation. `Bitmap.createBitmap` may
 * return the source instance when nothing changes, so recycling is guarded.
 */
private fun Bitmap.rotate(degrees: Int): Bitmap {
    val normalized = ((degrees % 360) + 360) % 360
    if (normalized == 0) return this
    val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
    val rotated = Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    if (rotated !== this) recycle()
    return rotated
}
