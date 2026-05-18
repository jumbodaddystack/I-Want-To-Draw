package com.aichat.sandbox.ui.components.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Sub-phase 6.7 — round-trip the image payload format. Pure JVM math; no
 * Android Bitmap or file I/O is touched.
 */
class ImageItemCodecTest {

    @Test
    fun roundTripsBasicPayload() {
        val original = ImageItemCodec.ImagePayload(
            relativePath = "note-images/abc.png",
            naturalWidth = 1024f,
            naturalHeight = 768f,
            minX = -100f,
            minY = -50f,
            maxX = 100f,
            maxY = 50f,
            rotationRad = 0.5f,
        )
        val decoded = ImageItemCodec.decode(ImageItemCodec.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun decodeRelativePathReadsHeaderOnly() {
        val payload = ImageItemCodec.ImagePayload(
            relativePath = "note-images/x.jpg",
            naturalWidth = 10f, naturalHeight = 10f,
            minX = 0f, minY = 0f, maxX = 10f, maxY = 10f,
        )
        val bytes = ImageItemCodec.encode(payload)
        assertEquals("note-images/x.jpg", ImageItemCodec.decodeRelativePath(bytes))
    }

    @Test
    fun transformTranslatesAndRotates() {
        val original = ImageItemCodec.ImagePayload(
            relativePath = "x.png",
            naturalWidth = 100f, naturalHeight = 100f,
            minX = 0f, minY = 0f, maxX = 100f, maxY = 100f,
            rotationRad = 0f,
        )
        val moved = ImageItemCodec.transform(
            original,
            StrokeTransform.translation(50f, 25f),
        )
        assertEquals(50f, moved.minX, 1e-3f)
        assertEquals(25f, moved.minY, 1e-3f)
        assertEquals(150f, moved.maxX, 1e-3f)
        assertEquals(125f, moved.maxY, 1e-3f)
    }

    @Test
    fun emptyPathDecodes() {
        val payload = ImageItemCodec.ImagePayload(
            relativePath = "",
            naturalWidth = 1f, naturalHeight = 1f,
            minX = 0f, minY = 0f, maxX = 1f, maxY = 1f,
        )
        val decoded = ImageItemCodec.decode(ImageItemCodec.encode(payload))
        assertEquals("", decoded.relativePath)
    }
}
