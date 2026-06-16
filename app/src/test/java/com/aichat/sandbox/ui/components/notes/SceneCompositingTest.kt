package com.aichat.sandbox.ui.components.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure helpers behind [DrawingSurface]'s incremental scene compositing — the
 * canvas-stutter fix. Verifies the blit math against [ViewportController]'s
 * world→screen mapping, and the signature / on-top predicates that gate the
 * incremental fast path.
 */
class SceneCompositingTest {

    private fun layer(id: String, ordinal: Int, visible: Boolean = true) = NoteLayer(
        id = id,
        noteId = "n",
        name = id,
        opacityPercent = 100,
        visible = visible,
        locked = false,
        ordinal = ordinal,
    )

    private fun stroke(
        id: String,
        layerId: String? = null,
        zIndex: Int = 0,
        payload: ByteArray = ByteArray(4),
    ) = NoteItem(
        id = id,
        noteId = "n",
        zIndex = zIndex,
        kind = "stroke",
        tool = "pen",
        colorArgb = 0,
        baseWidthPx = 1f,
        payload = payload,
        layerId = layerId,
    )

    // ── sceneBlitParams ──────────────────────────────────────────────────

    @Test
    fun blitParamsAreIdentityWhenViewportUnchanged() {
        val p = sceneBlitParams(2f, 30f, -40f, 2f, 30f, -40f)
        assertEquals(1f, p[0], 1e-5f)  // scale
        assertEquals(0f, p[1], 1e-5f)  // tx
        assertEquals(0f, p[2], 1e-5f)  // ty
    }

    @Test
    fun blitParamsMapCachedPixelToCurrentScreen() {
        // Bitmap rasterized at the render viewport; gesture moved to current.
        val render = ViewportController(offsetX = 30f, offsetY = -40f, scale = 1.5f)
        val current = ViewportController(offsetX = 12f, offsetY = 8f, scale = 2.25f)
        val p = sceneBlitParams(
            render.scale, render.offsetX, render.offsetY,
            current.scale, current.offsetX, current.offsetY,
        )

        // A committed world point: its cached pixel is render.worldToScreen, and
        // applying the blit transform to that pixel must land at the point's
        // current.worldToScreen position.
        for (w in listOf(0f, 17.5f, -123.4f, 999f)) {
            val pixelX = render.worldToScreenX(w)
            val pixelY = render.worldToScreenY(w)
            val mappedX = pixelX * p[0] + p[1]
            val mappedY = pixelY * p[0] + p[2]
            assertEquals(current.worldToScreenX(w), mappedX, 1e-2f)
            assertEquals(current.worldToScreenY(w), mappedY, 1e-2f)
        }
    }

    @Test
    fun blitParamsPanOnlyIsPureTranslate() {
        val p = sceneBlitParams(2f, 10f, 10f, 2f, 25f, -5f)
        assertEquals(1f, p[0], 1e-5f)
        assertEquals(15f, p[1], 1e-5f)   // 25 - 10*1
        assertEquals(-15f, p[2], 1e-5f)  // -5 - 10*1
    }

    // ── itemsSignature ───────────────────────────────────────────────────

    @Test
    fun signatureIsStableForEqualSets() {
        val a = listOf(stroke("x"), stroke("y", zIndex = 3))
        val b = listOf(stroke("x"), stroke("y", zIndex = 3))
        assertEquals(itemsSignature(a), itemsSignature(b))
    }

    @Test
    fun signatureIsOrderSensitive() {
        val a = listOf(stroke("x"), stroke("y"))
        val b = listOf(stroke("y"), stroke("x"))
        assertNotEquals(itemsSignature(a), itemsSignature(b))
    }

    @Test
    fun signatureReactsToZIndexPayloadAndLayer() {
        val base = listOf(stroke("x", zIndex = 0, payload = ByteArray(4)))
        assertNotEquals(
            itemsSignature(base),
            itemsSignature(listOf(stroke("x", zIndex = 1, payload = ByteArray(4)))),
        )
        assertNotEquals(
            itemsSignature(base),
            itemsSignature(listOf(stroke("x", zIndex = 0, payload = ByteArray(8)))),
        )
        assertNotEquals(
            itemsSignature(base),
            itemsSignature(listOf(stroke("x", layerId = "L", zIndex = 0, payload = ByteArray(4)))),
        )
    }

    @Test
    fun signatureChangesWhenItemAppended() {
        val before = listOf(stroke("x"))
        val after = before + stroke("y")
        assertNotEquals(itemsSignature(before), itemsSignature(after))
    }

    // ── sortsOnTop ───────────────────────────────────────────────────────

    @Test
    fun newItemSortsOnTopOverEarlierSameLayer() {
        val layers = LayerLookup(emptyList())  // all default ordinal MAX
        val committed = listOf(stroke("a"), stroke("b"))
        // "c" > "a","b" by id at equal ordinal/zIndex → on top.
        assertTrue(sortsOnTop(stroke("c"), committed + stroke("c"), layers))
    }

    @Test
    fun newItemDoesNotSortOnTopWhenExistingHasHigherZIndex() {
        val layers = LayerLookup(emptyList())
        val existing = stroke("a", zIndex = 5)
        val fresh = stroke("zzz", zIndex = 0)  // larger id but lower zIndex
        assertFalse(sortsOnTop(fresh, listOf(existing, fresh), layers))
    }

    @Test
    fun newItemDoesNotSortOnTopUnderHigherLayer() {
        val layers = LayerLookup(listOf(layer("bottom", 0), layer("top", 9)))
        val existing = stroke("a", layerId = "top")
        val fresh = stroke("b", layerId = "bottom")
        assertFalse(sortsOnTop(fresh, listOf(existing, fresh), layers))
    }

    @Test
    fun hiddenExistingItemsAreIgnoredForOnTopCheck() {
        val layers = LayerLookup(listOf(layer("hidden", 9, visible = false)))
        val hiddenTop = stroke("a", layerId = "hidden")  // higher ordinal but invisible
        val fresh = stroke("b")                          // default ordinal
        assertTrue(sortsOnTop(fresh, listOf(hiddenTop, fresh), layers))
    }
}
