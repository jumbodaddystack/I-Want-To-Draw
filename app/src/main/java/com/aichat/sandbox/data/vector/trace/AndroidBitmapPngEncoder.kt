package com.aichat.sandbox.data.vector.trace

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Production [BitmapPngEncoder]. Wraps a row-major ARGB `IntArray` in an
 * `ARGB_8888` [Bitmap] and PNG-compresses it. This is the one Android-coupled
 * piece of the AI trace (so it lives apart from the JVM-pure [AiBitmapTracer]);
 * unit tests inject a fake encoder instead.
 */
object AndroidBitmapPngEncoder : BitmapPngEncoder {
    override fun encode(pixels: IntArray, width: Int, height: Int): ByteArray? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null
            out.toByteArray()
        } catch (t: Throwable) {
            null
        } finally {
            bitmap.recycle()
        }
    }
}
