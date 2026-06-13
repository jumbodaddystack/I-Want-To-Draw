package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import com.aichat.sandbox.data.notes.EditOp
import com.aichat.sandbox.data.notes.EditOpsDoc
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Sub-phase 7.4 — applier simulation correctness, defensive validation,
 * and the "one undo entry" CompositeEdit round-trip.
 */
class EditPreviewControllerTest {

    @Test
    fun deleteOpDropsMatchedItemsOnly() {
        val a = strokeItem(); val b = strokeItem(); val c = strokeItem()
        val idMap = mapOf("s_001" to a.id, "s_002" to b.id, "s_003" to c.id)
        val doc = EditOpsDoc(1, "x", listOf(EditOp.Delete(listOf("s_002"))))
        val sim = EditPreviewController.simulate(
            currentItems = listOf(a, b, c),
            doc = doc,
            idMap = idMap,
            layerMap = emptyMap(),
            layers = emptyList(),
        )
        assertEquals(listOf(b), sim.removed)
        assertTrue(sim.added.isEmpty())
        assertTrue(sim.modified.isEmpty())
    }

    @Test
    fun recolorOnlyEmitsModifiedWhenColourActuallyChanges() {
        val item = strokeItem(colorArgb = 0xFF000000.toInt())
        val doc = EditOpsDoc(1, "", listOf(
            EditOp.Recolor(listOf("s_001"), 0xFF000000.toInt()),   // no-op
            EditOp.Recolor(listOf("s_001"), 0xFFFF0000.toInt()),   // real change
        ))
        val sim = EditPreviewController.simulate(
            currentItems = listOf(item),
            doc = doc,
            idMap = mapOf("s_001" to item.id),
            layerMap = emptyMap(),
            layers = emptyList(),
        )
        assertEquals(1, sim.modified.size)
        assertEquals(0xFFFF0000.toInt(), sim.modified[0].second.colorArgb)
    }

    @Test
    fun replaceWithShapeRemovesSourceAndAddsShape() {
        val source = strokeItem()
        val doc = EditOpsDoc(1, "", listOf(EditOp.ReplaceWithShape(
            sourceId = "s_001",
            shape = EditOp.ShapeSpec.Ellipse(10f, 10f, 5f, 5f),
        )))
        val sim = EditPreviewController.simulate(
            currentItems = listOf(source),
            doc = doc,
            idMap = mapOf("s_001" to source.id),
            layerMap = emptyMap(),
            layers = emptyList(),
        )
        assertEquals(listOf(source), sim.removed)
        assertEquals(1, sim.added.size)
        assertEquals(Shape.KIND, sim.added[0].kind)
        // Shape inherits the source colour/width and layer.
        assertEquals(source.colorArgb, sim.added[0].colorArgb)
    }

    @Test
    fun lockedLayerItemsAreSilentlyDropped() {
        val locked = NoteLayer(
            id = "L_LOCK", noteId = "n1", name = "L",
            opacityPercent = 100, visible = true, locked = true, ordinal = 0,
        )
        val item = strokeItem().copy(layerId = "L_LOCK")
        val doc = EditOpsDoc(1, "", listOf(EditOp.Delete(listOf("s_001"))))
        val sim = EditPreviewController.simulate(
            currentItems = listOf(item),
            doc = doc,
            idMap = mapOf("s_001" to item.id),
            layerMap = emptyMap(),
            layers = listOf(locked),
        )
        assertTrue(sim.removed.isEmpty())
        assertFalse(sim.skipped.isEmpty())
    }

    @Test
    fun compositeEditRoundTripsThroughUndo() {
        val items = mutableListOf(strokeItem(), strokeItem(), strokeItem())
        val before = items.toList()
        val firstItem = items[0]
        val action = EditorAction.CompositeEdit(
            description = "test",
            added = listOf(strokeItem()),
            removed = listOf(items[1]),
            modified = listOf(firstItem to firstItem.copy(colorArgb = 0xFFFF0000.toInt())),
        )
        action.applyTo(items)
        assertEquals(3, items.size) // -1 removed + 1 added
        assertEquals(0xFFFF0000.toInt(), items.first { it.id == firstItem.id }.colorArgb)

        // Invert restores the original state byte-identical.
        action.invert().applyTo(items)
        assertEquals(before.size, items.size)
        assertEquals(before.map { it.id }.toSet(), items.map { it.id }.toSet())
        assertEquals(firstItem.colorArgb, items.first { it.id == firstItem.id }.colorArgb)
    }

    @Test
    fun smoothChainComposesAcrossOpsOnSameId() {
        // Recolor then smooth on the same id should produce one modified pair
        // (the final state) rather than two.
        val item = strokeItem(colorArgb = 0xFF000000.toInt())
        val doc = EditOpsDoc(1, "", listOf(
            EditOp.Recolor(listOf("s_001"), 0xFFFF0000.toInt()),
            EditOp.Smooth(listOf("s_001"), 0.5f),
        ))
        val sim = EditPreviewController.simulate(
            currentItems = listOf(item),
            doc = doc,
            idMap = mapOf("s_001" to item.id),
            layerMap = emptyMap(),
            layers = emptyList(),
        )
        assertEquals(1, sim.modified.size)
        val after = sim.modified[0].second
        assertEquals(0xFFFF0000.toInt(), after.colorArgb)
        // payload should have changed (smoothing produces a different byte array)
        assertFalse(after.payload.contentEquals(item.payload))
    }

    @Test
    fun groupOpIsSkippedUntilPhase8() {
        val item = strokeItem()
        val doc = EditOpsDoc(1, "", listOf(EditOp.Group(listOf("s_001"))))
        val sim = EditPreviewController.simulate(
            currentItems = listOf(item),
            doc = doc,
            idMap = mapOf("s_001" to item.id),
            layerMap = emptyMap(),
            layers = emptyList(),
        )
        assertTrue(sim.isEmpty)
        assertNotNull(sim.skipped.firstOrNull { it.contains("group") })
    }

    @Test
    fun setLayerToLockedTargetIsRejected() {
        val locked = NoteLayer(
            id = "L_LOCK", noteId = "n1", name = "L",
            opacityPercent = 100, visible = true, locked = true, ordinal = 0,
        )
        val open = locked.copy(id = "L_OPEN", name = "Ink", locked = false, ordinal = 1)
        val item = strokeItem().copy(layerId = "L_OPEN")
        val doc = EditOpsDoc(1, "", listOf(EditOp.SetLayer(listOf("s_001"), "Llocked")))
        val sim = EditPreviewController.simulate(
            currentItems = listOf(item),
            doc = doc,
            idMap = mapOf("s_001" to item.id),
            layerMap = mapOf("Llocked" to "L_LOCK", "Lopen" to "L_OPEN"),
            layers = listOf(locked, open),
        )
        assertTrue(sim.isEmpty)
        assertNotNull(sim.skipped.firstOrNull { it.contains("locked") || it.contains("set_layer") })
    }

    @Test
    fun mergePathsFoldsCompatiblePathsIntoOne() {
        val a = pathItem(0f)
        val b = pathItem(20f)
        val doc = EditOpsDoc(1, "", listOf(EditOp.MergePaths(listOf("p_001", "p_002"))))
        val sim = EditPreviewController.simulate(
            currentItems = listOf(a, b),
            doc = doc,
            idMap = mapOf("p_001" to a.id, "p_002" to b.id),
            layerMap = emptyMap(),
            layers = emptyList(),
        )
        // Both sources removed, one merged path added with two subpaths.
        assertEquals(setOf(a.id, b.id), sim.removed.mapTo(HashSet()) { it.id })
        assertEquals(1, sim.added.size)
        val merged = com.aichat.sandbox.ui.components.notes.PathCodec.decode(sim.added[0].payload)
        assertEquals(2, merged.subpaths.size)
    }

    @Test
    fun mergePathsRefusesIncompatibleColours() {
        val a = pathItem(0f, colorArgb = 0xFF000000.toInt())
        val b = pathItem(20f, colorArgb = 0xFFFF0000.toInt())
        val doc = EditOpsDoc(1, "", listOf(EditOp.MergePaths(listOf("p_001", "p_002"))))
        val sim = EditPreviewController.simulate(
            currentItems = listOf(a, b),
            doc = doc,
            idMap = mapOf("p_001" to a.id, "p_002" to b.id),
            layerMap = emptyMap(),
            layers = emptyList(),
        )
        assertTrue(sim.isEmpty)
        assertNotNull(sim.skipped.firstOrNull { it.contains("merge_paths") })
    }

    private fun pathItem(x: Float, colorArgb: Int = 0xFF000000.toInt()): NoteItem {
        val payload = com.aichat.sandbox.ui.components.notes.PathCodec.PathPayload(
            anchors = listOf(
                com.aichat.sandbox.ui.components.notes.PathCodec.Anchor(x, 0f),
                com.aichat.sandbox.ui.components.notes.PathCodec.Anchor(x + 10f, 0f),
                com.aichat.sandbox.ui.components.notes.PathCodec.Anchor(x + 10f, 10f),
            ),
            closed = true,
        )
        return NoteItem(
            id = UUID.randomUUID().toString(),
            noteId = "n1",
            zIndex = 0,
            kind = com.aichat.sandbox.ui.components.notes.PathCodec.KIND,
            tool = null,
            colorArgb = colorArgb,
            baseWidthPx = 2f,
            payload = com.aichat.sandbox.ui.components.notes.PathCodec.encode(payload),
        )
    }

    private fun strokeItem(colorArgb: Int = 0xFF000000.toInt()): NoteItem {
        val samples = floatArrayOf(
            0f, 0f, 1f, 0f,
            5f, 5f, 1f, 0f,
            10f, 10f, 1f, 0f,
            15f, 15f, 1f, 0f,
        )
        return NoteItem(
            id = UUID.randomUUID().toString(),
            noteId = "n1",
            zIndex = 0,
            kind = NoteItem.KIND_STROKE,
            tool = "pen",
            colorArgb = colorArgb,
            baseWidthPx = 2f,
            payload = StrokeCodec.encode(samples),
        )
    }

    @Suppress("unused")
    private fun shapeItem(): NoteItem = NoteItem(
        id = UUID.randomUUID().toString(),
        noteId = "n1",
        zIndex = 0,
        kind = Shape.KIND,
        tool = null,
        colorArgb = 0xFF000000.toInt(),
        baseWidthPx = 2f,
        payload = ShapeCodec.encode(Shape.Line(0f, 0f, 10f, 10f), fillArgb = 0),
    )
}
