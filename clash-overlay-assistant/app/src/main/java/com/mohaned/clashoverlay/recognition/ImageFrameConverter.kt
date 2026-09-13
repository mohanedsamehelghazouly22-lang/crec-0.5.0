package com.mohaned.clashoverlay.recognition

import android.graphics.Bitmap
import android.graphics.Bitmap.Config
import android.media.Image
import java.nio.ByteBuffer

object ImageFrameConverter {
    fun rgbaToBitmap(image: Image): Bitmap {
        require(image.planes.isNotEmpty()) { "Image has no planes" }
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        require(width > 0 && height > 0) { "Invalid image size" }

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        require(pixelStride >= 4 && rowStride >= pixelStride * width) {
            "Unexpected RGBA plane layout"
        }

        val rowPadding = rowStride - pixelStride * width
        val paddedWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Config.ARGB_8888)
        val buffer = plane.buffer.duplicate()
        copyPlane(buffer, padded, width, height, rowStride, pixelStride)

        return if (paddedWidth == width) {
            padded
        } else {
            Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
        }
    }

    private fun copyPlane(
        buffer: ByteBuffer,
        bitmap: Bitmap,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ) {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowStart = y * rowStride
            if (rowStart >= buffer.limit()) break
            val available = minOf(rowStride, buffer.limit() - rowStart)
            for (x in 0 until width) {
                val i = x * pixelStride
                if (i + 3 >= available) continue
                val r = buffer.get(rowStart + i).toInt() and 0xff
                val g = buffer.get(rowStart + i + 1).toInt() and 0xff
                val b = buffer.get(rowStart + i + 2).toInt() and 0xff
                val a = buffer.get(rowStart + i + 3).toInt() and 0xff
                pixels[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }
}
