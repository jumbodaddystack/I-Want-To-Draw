package com.aichat.sandbox.ui.components.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sub-phase 6.4 — render order, visibility, lock, opacity resolution.
 */
class LayerLookupTest {

    private fun layer(
        id: String,
        ordinal: Int,
        visible: Boolean = true,
        locked: Boolean = false,
        opacity: Int = 100,
    ) = NoteLayer(
        id = id,
        noteId = "n",
        name = id,
        opacityPercent = opacity,
        visible = visible,
        locked = locked,
        ordinal = ordinal,
    )

    private fun stroke(id: String, layerId: String?, zIndex: Int = 0) = NoteItem(
        id = id,
        noteId = "n",
        zIndex = zIndex,
        kind = "stroke",
        tool = "pen",
        colorArgb = 0,
        baseWidthPx = 1f,
        payload = ByteArray(0),
        layerId = layerId,
    )

    @Test
    fun unparentedItemsRenderOnTopAndAreVisibleUnlocked() {
        val lookup = LayerLookup(listOf(layer("a", 0)))
        val orphan = stroke("orphan", layerId = null)
        assertTrue(lookup.isVisible(orphan))
        assertFalse(lookup.isLocked(orphan))
        assertEquals(1f, lookup.opacity(orphan), 1e-3f)
        assertEquals(Int.MAX_VALUE, lookup.renderOrdinal(orphan))
    }

    @Test
    fun hiddenLayerItemsDropOutOfRenderOrder() {
        val lookup = LayerLookup(listOf(layer("hl", 0, visible = false)))
        val hidden = stroke("hi", "hl")
        val visible = stroke("vis", null)
        val order = lookup.renderOrder(listOf(hidden, visible))
        assertEquals(listOf("vis"), order.map { it.id })
    }

    @Test
    fun lockedLayerItemsStillRenderButAreReportedLocked() {
        val lookup = LayerLookup(listOf(layer("lk", 0, locked = true)))
        val locked = stroke("li", "lk")
        assertTrue(lookup.isLocked(locked))
        assertTrue(lookup.isVisible(locked))
        assertEquals(listOf("li"), lookup.renderOrder(listOf(locked)).map { it.id })
    }

    @Test
    fun renderOrderSortsByLayerOrdinalThenZIndexThenId() {
        val lookup = LayerLookup(listOf(layer("bottom", 0), layer("top", 5)))
        val items = listOf(
            stroke("a", "top", zIndex = 1),
            stroke("b", "bottom", zIndex = 2),
            stroke("c", "bottom", zIndex = 2),  // tie-break by id
        )
        val order = lookup.renderOrder(items).map { it.id }
        assertEquals(listOf("b", "c", "a"), order)
    }

    @Test
    fun opacityClampsAndResolves() {
        val lookup = LayerLookup(listOf(layer("o", 0, opacity = 50)))
        val item = stroke("i", "o")
        assertEquals(0.5f, lookup.opacity(item), 1e-3f)
    }
}
