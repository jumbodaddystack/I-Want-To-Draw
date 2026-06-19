package com.aichat.sandbox.data.vector.trace

/** Encodes row-major ARGB pixels into a PNG byte array. */
fun interface BitmapPngEncoder {
    fun encode(pixels: IntArray, width: Int, height: Int): ByteArray?
}
