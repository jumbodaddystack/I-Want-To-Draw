package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase I8 — pure-JVM coverage of the tutor "draw with me" plumbing (b): the
 * ghosted, editable guide layer, canonical-item reparenting, the low-clutter
 * step planner, and the step / skip / back / redo state machine.
 */
class TutorGuideTest {

    private fun shape(id: String, z: Int) = NoteItem(
        id = id, noteId = "n", zIndex = z, kind = NoteItem.KIND_SHAPE,
        tool = null, colorArgb = 0xFF000000.toInt(), baseWidthPx = 2f, payload = ByteArray(0),
    )

    private fun layer(name: String, ordinal: Int) =
        NoteLayer(noteId = "n", name = name, opacityPercent = 100, visible = true, locked = false, ordinal = ordinal)

    @Test
    fun guideLayerIsGhostedEditableAndOnTop() {
        val existing = listOf(layer("Base", 0), layer("Ink", 3))
        val guide = TutorGuide.buildGuideLayer("n", existing)
        assertEquals(TutorGuide.GUIDE_LAYER_NAME, guide.name)
        assertEquals(TutorGuide.GUIDE_OPACITY_PERCENT, guide.opacityPercent)
        assertTrue("editable: unlocked", !guide.locked)
        assertTrue("visible", guide.visible)
        assertTrue("on top", guide.ordinal > 3)
    }

    @Test
    fun findGuideLayerReusesByName() {
        val layers = listOf(layer("Base", 0), layer(TutorGuide.GUIDE_LAYER_NAME, 1))
        assertEquals(TutorGuide.GUIDE_LAYER_NAME, TutorGuide.findGuideLayer(layers)?.name)
        assertNull(TutorGuide.findGuideLayer(listOf(layer("Base", 0))))
    }

    @Test
    fun assignToGuideReparentsWithoutTouchingPayload() {
        val items = listOf(shape("a", 0), shape("b", 1))
        val reparented = TutorGuide.assignToGuide(items, "guide-id")
        assertTrue(reparented.all { it.layerId == "guide-id" })
        // Geometry untouched — canonical payload preserved.
        items.zip(reparented).forEach { (before, after) ->
            assertTrue(before.payload.contentEquals(after.payload))
            assertEquals(before.id, after.id)
        }
    }

    @Test
    fun planStepsOneItemPerStepWhenSmall() {
        val items = (0 until 5).map { shape("s$it", it) }
        val steps = TutorGuide.planSteps(items)
        assertEquals(5, steps.size)
        steps.forEachIndexed { i, step ->
            assertEquals(i, step.index)
            assertEquals(1, step.items.size)
            assertEquals("s$i", step.items[0].id)
        }
    }

    @Test
    fun planStepsCapsToMaxStepsForLowClutter() {
        val items = (0 until 50).map { shape("s$it", it) }
        val steps = TutorGuide.planSteps(items)
        assertTrue("never more than MAX_STEPS", steps.size <= TutorGuide.MAX_STEPS)
        // Every item lands in exactly one step, draw order preserved.
        val flat = steps.flatMap { it.items }.map { it.id }
        assertEquals(items.map { it.id }, flat)
    }

    @Test
    fun planStepsOrdersByZIndex() {
        val items = listOf(shape("c", 2), shape("a", 0), shape("b", 1))
        val steps = TutorGuide.planSteps(items)
        assertEquals(listOf("a", "b", "c"), steps.flatMap { it.items }.map { it.id })
    }

    // ── TutorSession state machine ───────────────────────────────────────────

    private fun session(n: Int): TutorSession =
        TutorSession(TutorGuide.planSteps((0 until n).map { shape("s$it", it) }))

    @Test
    fun freshSessionRevealsNothing() {
        val s = session(3)
        assertEquals(-1, s.cursor)
        assertTrue(s.revealedItems().isEmpty())
        assertEquals(3, s.hiddenItemIds().size)
        assertNull(s.currentStep)
        assertFalse(s.isComplete)
    }

    @Test
    fun nextRevealsStepsInOrder() {
        var s = session(3)
        s = s.next()
        assertEquals(0, s.cursor)
        assertEquals(listOf("s0"), s.revealedItems().map { it.id })
        s = s.next()
        assertEquals(listOf("s0", "s1"), s.revealedItems().map { it.id })
        s = s.next()
        assertTrue(s.isComplete)
        assertEquals(3, s.revealedItems().size)
        assertTrue(s.hiddenItemIds().isEmpty())
        // next past the end is a no-op.
        assertEquals(s, s.next())
    }

    @Test
    fun skipAdvancesButLeavesStepHidden() {
        var s = session(3).next() // reveal s0
        s = s.skip()              // advance over s1 without revealing it
        assertEquals(1, s.cursor)
        assertEquals(listOf("s0"), s.revealedItems().map { it.id })
        assertTrue("skipped step stays hidden", "s1" in s.hiddenItemIds())
        s = s.next()              // reveal s2
        assertEquals(listOf("s0", "s2"), s.revealedItems().map { it.id })
    }

    @Test
    fun backUnhidesTheStepLeftBehind() {
        var s = session(3).next().next() // reveal s0, s1
        s = s.back()
        assertEquals(0, s.cursor)
        assertEquals(listOf("s0"), s.revealedItems().map { it.id })
        assertTrue("s1" in s.hiddenItemIds())
        // back at the start clamps.
        s = s.back()
        assertEquals(-1, s.back().cursor)
    }

    @Test
    fun redoReturnsCurrentStepWithoutMoving() {
        val s = session(2).next()
        assertEquals(s.currentStep, s.redo())
        // redo doesn't mutate the session.
        assertEquals(0, s.cursor)
    }

    @Test
    fun resetReturnsToStart() {
        val s = session(3).next().next().reset()
        assertEquals(-1, s.cursor)
        assertTrue(s.skipped.isEmpty())
        assertTrue(s.revealedItems().isEmpty())
    }

    @Test
    fun progressTracksCursor() {
        var s = session(4)
        assertEquals(0f, s.progress, 1e-4f)
        s = s.next().next()
        assertEquals(0.5f, s.progress, 1e-4f)
        assertEquals(1f, TutorSession(emptyList()).progress, 1e-4f)
        assertTrue(TutorSession(emptyList()).isComplete)
    }
}
