package com.aichat.sandbox.ui.screens.vector.edit

import com.aichat.sandbox.data.vector.PathDataParser
import com.aichat.sandbox.data.vector.VectorDocument
import com.aichat.sandbox.data.vector.VectorGroup
import com.aichat.sandbox.data.vector.VectorNode
import com.aichat.sandbox.data.vector.VectorPath
import com.aichat.sandbox.data.vector.VectorStyle
import com.aichat.sandbox.data.vector.VectorViewport
import com.aichat.sandbox.ui.components.notes.Snap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 (step 1c) — drives the `StateFlow` host and its gesture→action mapping.
 *
 * The reducer/hit-test cores are proven elsewhere; this focuses on the shell: that
 * a screen touch is mapped through the [com.aichat.sandbox.ui.components.notes.ViewportController]
 * and [EditHitTest] to the right reducer action, and that a continuous drag lands
 * as a single undo step. No Robolectric — the ViewModel does no coroutine work and
 * the viewport is Android-free.
 */
class VectorEditViewModelTest {

    private fun path(data: String, id: String = "p") = VectorPath(
        id = id,
        pathData = data,
        commands = PathDataParser.parse(data).commands,
        style = VectorStyle(),
    )

    private fun docWith(vararg paths: VectorPath) = VectorDocument(
        viewport = VectorViewport(100f, 100f, 100f, 100f),
        root = VectorGroup(id = "root", children = paths.map { VectorNode.PathNode(it) }),
    )

    private fun vmEditing(vararg paths: VectorPath): VectorEditViewModel {
        val vm = VectorEditViewModel()
        vm.open(docWith(*paths), pathId = "p")
        return vm
    }

    /** Screen px for a world point at the live viewport mapping. */
    private fun VectorEditViewModel.screenOf(wx: Float, wy: Float): Pair<Float, Float> =
        viewport.worldToScreenX(wx) to viewport.worldToScreenY(wy)

    @Test
    fun openEntersEditOnRequestedPath() {
        val vm = vmEditing(path("M0,0 L10,0 L10,10 Z"))
        val editing = vm.state.value.editing
        assertNotNull(editing)
        assertEquals("p", editing!!.pathId)
        assertEquals(3, editing.subpaths.single().anchors.size)
    }

    @Test
    fun tapOnAnchorSelectsIt() {
        val vm = vmEditing(path("M0,0 L100,0 L100,100 Z"))
        // Zoom in so the world tolerance (~22px/scale) doesn't swallow the artboard.
        vm.zoom(0f, 0f, 8f)
        val anchor0 = vm.state.value.editing!!.subpaths.single().anchors[0]
        val (sx, sy) = vm.screenOf(anchor0.x, anchor0.y)
        vm.onTap(sx, sy)
        assertEquals(setOf(anchor0.id), vm.state.value.selection.anchorIds)
    }

    @Test
    fun tapOnSegmentInsertsAnAnchor() {
        val vm = vmEditing(path("M0,0 L100,0 L100,100 Z"))
        vm.zoom(0f, 0f, 8f) // tolerance ~2.75 world units, far from the anchors
        val before = vm.state.value.editing!!.subpaths.single().anchors.size
        val (sx, sy) = vm.screenOf(50f, 0f) // midway along the first segment
        vm.onTap(sx, sy)
        val anchors = vm.state.value.editing!!.subpaths.single().anchors
        assertEquals(before + 1, anchors.size)
        assertEquals(50f, anchors[1].x, 0.5f)
        assertEquals(0f, anchors[1].y, 0.5f)
    }

    @Test
    fun tapOnEmptyClearsSelection() {
        val vm = vmEditing(path("M0,0 L100,0 L100,100 Z"))
        vm.zoom(0f, 0f, 8f)
        val anchor0 = vm.state.value.editing!!.subpaths.single().anchors[0]
        val (ax, ay) = vm.screenOf(anchor0.x, anchor0.y)
        vm.onTap(ax, ay)
        assertTrue(vm.state.value.selection.anchorIds.isNotEmpty())
        // A tap inside the triangle, well clear of every anchor and edge, clears it.
        val (ex, ey) = vm.screenOf(60f, 20f)
        vm.onTap(ex, ey)
        assertTrue(vm.state.value.selection.isEmpty)
    }

    @Test
    fun penTapPlacesAnchorInWorldSpace() {
        val vm = VectorEditViewModel()
        vm.open(docWith()) // no path under edit; drawing fresh
        vm.setTool(EditTool.PEN)
        vm.startPath()
        val (sx, sy) = vm.screenOf(5f, 7f)
        vm.onTap(sx, sy)
        val draft = vm.state.value.pendingPen!!
        assertEquals(1, draft.anchors.size)
        assertEquals(5f, draft.anchors[0].x, 1e-4f)
        assertEquals(7f, draft.anchors[0].y, 1e-4f)
    }

    @Test
    fun dragHandleMovesItAsOneUndoStep() {
        val vm = vmEditing(path("M0,0 C10,10 20,10 30,0"))
        val anchor0 = vm.state.value.editing!!.subpaths.single().anchors[0]
        // Handles are only grabbable on a selected anchor.
        vm.dispatch(VectorEditAction.SelectAnchor(anchor0.id))
        val out = anchor0.outHandle!!
        val (sx, sy) = vm.screenOf(out.x, out.y)
        vm.onDragStart(sx, sy)
        vm.onDrag(5f, 5f) // scale 1 → +5,+5 in world
        vm.onDrag(5f, 5f)
        vm.onDragEnd()
        val a = vm.state.value.editing!!.subpaths.single().anchors[0]
        assertEquals(out.x + 10f, a.outHandle!!.x, 1e-3f)
        assertEquals(out.y + 10f, a.outHandle!!.y, 1e-3f)
        // The whole drag collapses to a single undo entry.
        assertEquals(1, vm.state.value.undoStack.size)
        vm.undo()
        val reverted = vm.state.value.editing!!.subpaths.single().anchors[0]
        assertEquals(out.x, reverted.outHandle!!.x, 1e-3f)
        assertEquals(out.y, reverted.outHandle!!.y, 1e-3f)
    }

    @Test
    fun dragSelectionTranslatesAndCoalescesUndo() {
        val vm = vmEditing(path("M0,0 L100,0 L100,100 Z"))
        val anchor1 = vm.state.value.editing!!.subpaths.single().anchors[1]
        vm.dispatch(VectorEditAction.SelectAnchor(anchor1.id))
        val (sx, sy) = vm.screenOf(anchor1.x, anchor1.y)
        vm.onDragStart(sx, sy)
        vm.onDrag(3f, 4f)
        vm.onDrag(3f, 4f)
        vm.onDragEnd()
        val moved = vm.state.value.editing!!.subpaths.single().anchors[1]
        assertEquals(anchor1.x + 6f, moved.x, 1e-3f)
        assertEquals(anchor1.y + 8f, moved.y, 1e-3f)
        assertEquals(1, vm.state.value.undoStack.size)
    }

    @Test
    fun snapMaskFlowsThroughToPlacement() {
        val vm = VectorEditViewModel()
        vm.open(docWith())
        vm.setTool(EditTool.PEN)
        vm.setSnapMask(Snap.MASK_GRID)
        vm.startPath()
        val spacing = Snap.DEFAULT_GRID_SPACING_WORLD
        val (sx, sy) = vm.screenOf(spacing + 2f, spacing - 2f)
        vm.onTap(sx, sy)
        val a = vm.state.value.pendingPen!!.anchors.single()
        assertEquals(spacing, a.x, 1e-4f)
        assertEquals(spacing, a.y, 1e-4f)
        assertNull(a.inHandle)
    }
}
