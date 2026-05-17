package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM-only coverage of the pure logic in [NoteRasterizer]: bounds union,
 * scale clamping, kind-aware boundsOf via [computeBounds]. The actual bitmap
 * drawing path is verified instrumented (see `HandwritingOcrTest` /
 * `NoteRasterizerInstrumentedTest`) because `android.graphics.Bitmap` isn't
 * stub-able on the host JVM.
 */
class NoteRasterizerTest {

    @Test
    fun computeBoundsReturnsNullForEmptyList() {
        assertNull(NoteRasterizer.computeBounds(emptyList()))
    }

    @Test
    fun computeBoundsReturnsNullWhenNoItemContributesGeometry() {
        // Unknown kind → boundsOf returns null; whole list contributes nothing.
        val unknown = NoteItem(
            noteId = "n",
            zIndex = 0,
            kind = "alien",
            tool = null,
            colorArgb = 0xFF000000.toInt(),
            baseWidthPx = 2f,
            payload = ByteArray(0),
        )
        assertNull(NoteRasterizer.computeBounds(listOf(unknown)))
    }

    @Test
    fun computeBoundsCoversSingleStroke() {
        val stroke = strokeItem(
            points = floatArrayOf(
                10f, 20f, 1f, 0f,
                30f, 40f, 1f, 0f,
                15f, 50f, 1f, 0f,
            ),
        )
        val bounds = NoteRasterizer.computeBounds(listOf(stroke))
        assertNotNull(bounds)
        assertEquals(10f, bounds!![0], 0f)
        assertEquals(20f, bounds[1], 0f)
        assertEquals(30f, bounds[2], 0f)
        assertEquals(50f, bounds[3], 0f)
    }

    @Test
    fun computeBoundsUnionsAcrossItems() {
        val a = strokeItem(points = floatArrayOf(-5f, -5f, 1f, 0f, 5f, 5f, 1f, 0f))
        val b = strokeItem(points = floatArrayOf(20f, -50f, 1f, 0f, 25f, 100f, 1f, 0f))
        val bounds = NoteRasterizer.computeBounds(listOf(a, b))!!
        assertEquals(-5f, bounds[0], 0f)
        assertEquals(-50f, bounds[1], 0f)
        assertEquals(25f, bounds[2], 0f)
        assertEquals(100f, bounds[3], 0f)
    }

    @Test
    fun computeBoundsSkipsItemsWithEmptyGeometryButKeepsOthers() {
        val empty = NoteItem(
            noteId = "n",
            zIndex = 0,
            kind = "stroke",
            tool = "pen",
            colorArgb = 0xFF000000.toInt(),
            baseWidthPx = 2f,
            payload = ByteArray(0),
        )
        val real = strokeItem(points = floatArrayOf(1f, 2f, 1f, 0f, 3f, 4f, 1f, 0f))
        val bounds = NoteRasterizer.computeBounds(listOf(empty, real))!!
        assertEquals(1f, bounds[0], 0f)
        assertEquals(2f, bounds[1], 0f)
        assertEquals(3f, bounds[2], 0f)
        assertEquals(4f, bounds[3], 0f)
    }

    @Test
    fun computeScaleHonorsLongestEdge() {
        assertEquals(0.5f, NoteRasterizer.computeScale(2000f, 1000f, 1000), 1e-6f)
        assertEquals(0.5f, NoteRasterizer.computeScale(1000f, 2000f, 1000), 1e-6f)
    }

    @Test
    fun computeScaleClampsAtUpperBound() {
        // Tiny note shouldn't blow up beyond MAX_SCALE.
        val scale = NoteRasterizer.computeScale(10f, 10f, 4096)
        assertEquals(NoteRasterizer.MAX_SCALE, scale, 1e-6f)
    }

    @Test
    fun computeScaleClampsAtLowerBound() {
        // Enormous note shouldn't disappear into a sub-pixel render.
        val scale = NoteRasterizer.computeScale(1_000_000f, 1_000_000f, 1024)
        assertEquals(NoteRasterizer.MIN_SCALE, scale, 1e-6f)
    }

    @Test
    fun computeScaleHandlesZeroWidthInput() {
        // Defensive: a degenerate (0×0) bounds shouldn't divide by zero.
        val scale = NoteRasterizer.computeScale(0f, 0f, 512)
        assertEquals(NoteRasterizer.MAX_SCALE, scale, 1e-6f)
    }

    private fun strokeItem(points: FloatArray): NoteItem = NoteItem(
        noteId = "n",
        zIndex = 0,
        kind = "stroke",
        tool = "pen",
        colorArgb = 0xFF000000.toInt(),
        baseWidthPx = 2f,
        payload = StrokeCodec.encode(points),
    )
}
