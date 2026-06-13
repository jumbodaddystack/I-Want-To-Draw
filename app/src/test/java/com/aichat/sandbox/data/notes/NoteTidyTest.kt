package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.PathCodec
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Phase 17.5 follow-on — the bundled tidy pass (simplify + snap + merge).
 */
class NoteTidyTest {

    @Test
    fun snapsPathAnchorsToGrid() {
        val item = pathItem(
            anchors = listOf(
                PathCodec.Anchor(3f, 4f),
                PathCodec.Anchor(33f, 29f),
                PathCodec.Anchor(31f, 5f),
            ),
            closed = true,
        )
        val result = NoteTidy.tidy(
            items = listOf(item),
            gridStep = 16f,
            bounds = null,
        )
        // One path, off-grid → modified to the nearest 16-multiples.
        assertEquals(1, result.modified.size)
        val payload = PathCodec.decode(result.modified[0].second.payload)
        val a = payload.subpaths[0].anchors
        assertEquals(0f, a[0].x, 0f); assertEquals(0f, a[0].y, 0f)
        assertEquals(32f, a[1].x, 0f); assertEquals(32f, a[1].y, 0f)
        assertEquals(32f, a[2].x, 0f); assertEquals(0f, a[2].y, 0f)
    }

    @Test
    fun alreadyOnGridPathIsLeftUntouched() {
        val item = pathItem(
            anchors = listOf(PathCodec.Anchor(0f, 0f), PathCodec.Anchor(16f, 0f), PathCodec.Anchor(16f, 16f)),
            closed = true,
        )
        val result = NoteTidy.tidy(listOf(item), gridStep = 16f, bounds = null)
        assertTrue(result.isEmpty)
    }

    @Test
    fun clampsSnappedAnchorsToBounds() {
        // An anchor outside the artboard snaps then clamps back inside.
        val item = pathItem(
            anchors = listOf(PathCodec.Anchor(0f, 0f), PathCodec.Anchor(95f, 0f), PathCodec.Anchor(0f, 95f)),
            closed = true,
        )
        val result = NoteTidy.tidy(
            items = listOf(item),
            gridStep = 16f,
            bounds = floatArrayOf(0f, 0f, 64f, 64f),
        )
        val a = PathCodec.decode(result.modified[0].second.payload).subpaths[0].anchors
        assertTrue("x clamped into bounds", a[1].x <= 64f)
        assertTrue("y clamped into bounds", a[2].y <= 64f)
    }

    @Test
    fun mergesStyleCompatiblePathsAfterSnapping() {
        val a = pathItem(listOf(PathCodec.Anchor(1f, 1f), PathCodec.Anchor(15f, 1f), PathCodec.Anchor(15f, 15f)), true)
        val b = pathItem(listOf(PathCodec.Anchor(33f, 1f), PathCodec.Anchor(47f, 1f), PathCodec.Anchor(47f, 15f)), true)
        val result = NoteTidy.tidy(
            items = listOf(a, b),
            gridStep = 16f,
            bounds = null,
            newItemNoteId = "icon-1",
        )
        // Both sources removed, one merged 2-subpath path added.
        assertEquals(setOf(a.id, b.id), result.removed.mapTo(HashSet()) { it.id })
        assertEquals(1, result.added.size)
        assertEquals("icon-1", result.added[0].noteId)
        assertEquals(2, PathCodec.decode(result.added[0].payload).subpaths.size)
        // Merged paths aren't double-counted as modifications.
        assertTrue(result.modified.none { it.first.id == a.id || it.first.id == b.id })
    }

    @Test
    fun incompatibleColoursAreNotMerged() {
        val a = pathItem(listOf(PathCodec.Anchor(0f, 0f), PathCodec.Anchor(16f, 0f)), false, colorArgb = 0xFF000000.toInt())
        val b = pathItem(listOf(PathCodec.Anchor(32f, 0f), PathCodec.Anchor(48f, 0f)), false, colorArgb = 0xFFFF0000.toInt())
        val result = NoteTidy.tidy(listOf(a, b), gridStep = 16f, bounds = null)
        assertTrue(result.added.isEmpty())
        assertTrue(result.removed.isEmpty())
    }

    @Test
    fun simplifyDropsRedundantCollinearStrokePoints() {
        // Collinear points → RDP keeps only the endpoints.
        val samples = FloatArray(5 * StrokeCodec.FLOATS_PER_SAMPLE)
        for (i in 0 until 5) {
            val o = i * StrokeCodec.FLOATS_PER_SAMPLE
            samples[o] = i * 10f; samples[o + 1] = 0f; samples[o + 2] = 1f; samples[o + 3] = 0f
        }
        val stroke = NoteItem(
            id = UUID.randomUUID().toString(), noteId = "n1", zIndex = 0,
            kind = NoteItem.KIND_STROKE, tool = "pen", colorArgb = 0xFF000000.toInt(),
            baseWidthPx = 2f, payload = StrokeCodec.encode(samples),
        )
        val result = NoteTidy.tidy(listOf(stroke), gridStep = 0f, bounds = null)
        assertEquals(1, result.modified.size)
        val out = StrokeCodec.decode(result.modified[0].second.payload)
        assertEquals(2, out.size / StrokeCodec.FLOATS_PER_SAMPLE)
    }

    @Test
    fun nonIconGridStepZeroSkipsSnapButStillMerges() {
        val a = pathItem(listOf(PathCodec.Anchor(1f, 1f), PathCodec.Anchor(15f, 1f), PathCodec.Anchor(15f, 9f)), true)
        val b = pathItem(listOf(PathCodec.Anchor(20f, 1f), PathCodec.Anchor(30f, 1f), PathCodec.Anchor(30f, 9f)), true)
        val result = NoteTidy.tidy(listOf(a, b), gridStep = 0f, bounds = null)
        assertEquals(1, result.added.size)
        // No snapping happened, so the merged subpaths keep their exact coords.
        val merged = PathCodec.decode(result.added[0].payload)
        assertEquals(1f, merged.subpaths[0].anchors[0].x, 0f)
    }

    @Test
    fun emptySelectionIsNoOp() {
        assertTrue(NoteTidy.tidy(emptyList(), gridStep = 16f, bounds = null).isEmpty)
    }

    private fun pathItem(
        anchors: List<PathCodec.Anchor>,
        closed: Boolean,
        colorArgb: Int = 0xFF000000.toInt(),
    ): NoteItem = NoteItem(
        id = UUID.randomUUID().toString(),
        noteId = "n1",
        zIndex = 0,
        kind = PathCodec.KIND,
        tool = null,
        colorArgb = colorArgb,
        baseWidthPx = 2f,
        payload = PathCodec.encode(PathCodec.PathPayload(anchors = anchors, closed = closed)),
    )
}
