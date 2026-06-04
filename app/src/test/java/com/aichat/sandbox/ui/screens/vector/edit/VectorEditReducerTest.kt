package com.aichat.sandbox.ui.screens.vector.edit

import com.aichat.sandbox.data.vector.PathCommand
import com.aichat.sandbox.data.vector.PathDataParser
import com.aichat.sandbox.data.vector.VectorDocument
import com.aichat.sandbox.data.vector.VectorGroup
import com.aichat.sandbox.data.vector.VectorNode
import com.aichat.sandbox.data.vector.VectorPath
import com.aichat.sandbox.data.vector.VectorStyle
import com.aichat.sandbox.data.vector.VectorViewport
import com.aichat.sandbox.data.vector.allPaths
import com.aichat.sandbox.data.vector.edit.AnchorType
import com.aichat.sandbox.data.vector.edit.EditablePathSerializer
import com.aichat.sandbox.ui.components.notes.Snap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Phase 1 (step 1a) — exercises the pure node-editor algebra: pen drawing,
 * direct-selection edits, curve-preserving insert, corner↔smooth conversion,
 * close/open, and exact undo/redo. No Compose/Android on the classpath.
 */
class VectorEditReducerTest {

    private val reducer = VectorEditReducer()

    private fun path(data: String, id: String = "p", style: VectorStyle = VectorStyle()) =
        VectorPath(
            id = id,
            pathData = data,
            commands = PathDataParser.parse(data).commands,
            style = style,
        )

    private fun docWith(vararg paths: VectorPath): VectorDocument = VectorDocument(
        viewport = VectorViewport(24f, 24f, 24f, 24f),
        root = VectorGroup(id = "root", children = paths.map { VectorNode.PathNode(it) }),
    )

    private fun stateOn(vararg paths: VectorPath) = VectorEditState(document = docWith(*paths))

    private fun reduce(state: VectorEditState, vararg actions: VectorEditAction): VectorEditState =
        actions.fold(state) { s, a -> reducer.reduce(s, a) }

    // ---- mode entry ----

    @Test
    fun beginEditDerivesEditableFromDocument() {
        val state = reduce(stateOn(path("M0,0 L10,0 L10,10 Z")), VectorEditAction.BeginEdit("p"))
        assertNotNull("editing should be derived", state.editing)
        val editing = state.editing!!
        assertEquals("p", editing.pathId)
        assertEquals(1, editing.subpaths.size)
        assertEquals(3, editing.subpaths.single().anchors.size)
        assertTrue(editing.subpaths.single().closed)
    }

    @Test
    fun beginEditUnknownPathIsIgnored() {
        val state = reduce(stateOn(path("M0,0 L1,0")), VectorEditAction.BeginEdit("missing"))
        assertNull(state.editing)
    }

    // ---- pen tool: draw a triangle ----

    @Test
    fun penDrawsAndCommitsATriangle() {
        val state = reduce(
            stateOn(),
            VectorEditAction.StartPath,
            VectorEditAction.PlaceAnchor(0f, 0f),
            VectorEditAction.PlaceAnchor(10f, 0f),
            VectorEditAction.PlaceAnchor(10f, 10f),
            VectorEditAction.CommitPath,
        )
        assertNull("draft cleared after commit", state.pendingPen)
        assertEquals(EditTool.DIRECT_SELECT, state.activeTool)
        val sp = state.editing!!.subpaths.single()
        assertEquals(3, sp.anchors.size)
        assertFalse(sp.closed)
        // Open polyline serializes without a Close.
        assertEquals(
            listOf(
                PathCommand.MoveTo(0f, 0f),
                PathCommand.LineTo(10f, 0f),
                PathCommand.LineTo(10f, 10f),
            ),
            EditablePathSerializer.toCommands(state.editing!!),
        )
    }

    @Test
    fun toggleClosedClosesTheDrawnSubpath() {
        val drawn = reduce(
            stateOn(),
            VectorEditAction.StartPath,
            VectorEditAction.PlaceAnchor(0f, 0f),
            VectorEditAction.PlaceAnchor(10f, 0f),
            VectorEditAction.PlaceAnchor(10f, 10f),
            VectorEditAction.CommitPath,
        )
        val subpathId = drawn.editing!!.subpaths.single().id
        val closed = reducer.reduce(drawn, VectorEditAction.ToggleSubpathClosed(subpathId))
        assertTrue(closed.editing!!.subpaths.single().closed)
        assertEquals(
            PathCommand.Close(),
            EditablePathSerializer.toCommands(closed.editing!!).last(),
        )
        // ...and toggling again re-opens it.
        val reopened = reducer.reduce(closed, VectorEditAction.ToggleSubpathClosed(subpathId))
        assertFalse(reopened.editing!!.subpaths.single().closed)
    }

    @Test
    fun penCommitNeedsAtLeastTwoAnchors() {
        val state = reduce(
            stateOn(),
            VectorEditAction.StartPath,
            VectorEditAction.PlaceAnchor(5f, 5f),
            VectorEditAction.CommitPath,
        )
        assertNull(state.pendingPen)
        assertNull("a lone anchor commits nothing", state.editing)
    }

    @Test
    fun dragHandlePullsSymmetricHandles() {
        val state = reduce(
            stateOn(),
            VectorEditAction.StartPath,
            VectorEditAction.PlaceAnchor(0f, 0f),
            VectorEditAction.DragHandle(3f, 0f),
        )
        val a = state.pendingPen!!.anchors.single()
        assertEquals(AnchorType.SYMMETRIC, a.type)
        assertEquals(3f, a.outHandle!!.x, 0f)
        assertEquals(0f, a.outHandle!!.y, 0f)
        // Incoming handle mirrors the outgoing one through the anchor.
        assertEquals(-3f, a.inHandle!!.x, 0f)
        assertEquals(0f, a.inHandle!!.y, 0f)
    }

    // ---- snapping ----

    @Test
    fun gridSnapLatchesPlacedAnchor() {
        val spacing = Snap.DEFAULT_GRID_SPACING_WORLD // 32
        val state = reduce(
            stateOn().copy(snapMask = Snap.MASK_GRID),
            VectorEditAction.StartPath,
            VectorEditAction.PlaceAnchor(spacing + 2f, spacing - 2f), // within tolerance of (32,32)
        )
        val a = state.pendingPen!!.anchors.single()
        assertEquals(spacing, a.x, 0f)
        assertEquals(spacing, a.y, 0f)
    }

    @Test
    fun noSnapMaskLeavesPointUntouched() {
        val state = reduce(
            stateOn(),
            VectorEditAction.StartPath,
            VectorEditAction.PlaceAnchor(33f, 31f),
        )
        val a = state.pendingPen!!.anchors.single()
        assertEquals(33f, a.x, 0f)
        assertEquals(31f, a.y, 0f)
    }

    // ---- selection + move ----

    @Test
    fun selectThenMoveTranslatesOnlySelectedAnchor() {
        val begun = reduce(stateOn(path("M0,0 L10,0 L10,10 Z")), VectorEditAction.BeginEdit("p"))
        val midId = begun.editing!!.subpaths.single().anchors[1].id
        val moved = reduce(
            begun,
            VectorEditAction.SelectAnchor(midId),
            VectorEditAction.MoveSelection(5f, 5f),
        )
        val anchors = moved.editing!!.subpaths.single().anchors
        assertEquals(0f, anchors[0].x, 0f) // untouched
        assertEquals(15f, anchors[1].x, 0f) // moved
        assertEquals(5f, anchors[1].y, 0f)
        assertEquals(10f, anchors[2].x, 0f) // untouched
    }

    @Test
    fun selectAnchorAdditiveToggles() {
        val begun = reduce(stateOn(path("M0,0 L10,0 L10,10 Z")), VectorEditAction.BeginEdit("p"))
        val ids = begun.editing!!.subpaths.single().anchors.map { it.id }
        val two = reduce(
            begun,
            VectorEditAction.SelectAnchor(ids[0]),
            VectorEditAction.SelectAnchor(ids[1], additive = true),
        )
        assertEquals(setOf(ids[0], ids[1]), two.selection.anchorIds)
        // Toggling an already-selected id removes it.
        val one = reducer.reduce(two, VectorEditAction.SelectAnchor(ids[0], additive = true))
        assertEquals(setOf(ids[1]), one.selection.anchorIds)
    }

    @Test
    fun moveSingleSelectionSnapsToGrid() {
        val begun = reduce(
            stateOn(path("M3,3 L10,0 L10,10 Z")).copy(snapMask = Snap.MASK_GRID),
            VectorEditAction.BeginEdit("p"),
        )
        val firstId = begun.editing!!.subpaths.single().anchors[0].id
        // Anchor at (3,3); a small drag lands it within grid tolerance of (0,0).
        val moved = reduce(
            begun,
            VectorEditAction.SelectAnchor(firstId),
            VectorEditAction.MoveSelection(-1f, -1f),
        )
        val a = moved.editing!!.subpaths.single().anchors[0]
        assertEquals(0f, a.x, 0f)
        assertEquals(0f, a.y, 0f)
    }

    // ---- delete ----

    @Test
    fun deleteSelectedRemovesAnchorsAndClearsSelection() {
        val begun = reduce(stateOn(path("M0,0 L10,0 L10,10 Z")), VectorEditAction.BeginEdit("p"))
        val midId = begun.editing!!.subpaths.single().anchors[1].id
        val after = reduce(
            begun,
            VectorEditAction.SelectAnchor(midId),
            VectorEditAction.DeleteSelected,
        )
        val anchors = after.editing!!.subpaths.single().anchors
        assertEquals(2, anchors.size)
        assertTrue(anchors.none { it.id == midId })
        assertTrue(after.selection.isEmpty)
    }

    @Test
    fun deletingAllAnchorsDropsTheSubpath() {
        val begun = reduce(stateOn(path("M0,0 L10,0")), VectorEditAction.BeginEdit("p"))
        val ids = begun.editing!!.subpaths.single().anchors.map { it.id }.toSet()
        val after = reduce(
            begun,
            VectorEditAction.SelectAnchor(ids.first()),
            VectorEditAction.SelectAnchor(ids.last(), additive = true),
            VectorEditAction.DeleteSelected,
        )
        assertTrue(after.editing!!.subpaths.isEmpty())
    }

    // ---- curve-preserving insert ----

    @Test
    fun insertOnLineSplitsAtMidpointWithoutHandles() {
        val begun = reduce(stateOn(path("M0,0 L10,0")), VectorEditAction.BeginEdit("p"))
        val spId = begun.editing!!.subpaths.single().id
        val after = reducer.reduce(begun, VectorEditAction.InsertAnchorOnSegment(spId, 0, 0.5f))
        val anchors = after.editing!!.subpaths.single().anchors
        assertEquals(3, anchors.size)
        assertEquals(5f, anchors[1].x, 1e-4f)
        assertEquals(0f, anchors[1].y, 1e-4f)
        assertNull(anchors[1].inHandle)
        assertNull(anchors[1].outHandle)
    }

    @Test
    fun insertOnCubicPreservesTheCurve() {
        val data = "M0,0 C2,0 8,10 10,10"
        val begun = reduce(stateOn(path(data)), VectorEditAction.BeginEdit("p"))
        val spId = begun.editing!!.subpaths.single().id
        val after = reducer.reduce(begun, VectorEditAction.InsertAnchorOnSegment(spId, 0, 0.5f))

        val anchors = after.editing!!.subpaths.single().anchors
        assertEquals(3, anchors.size)

        // The two resulting cubics must trace the original curve exactly.
        val orig = { t: Float -> cubicAt(0f, 0f, 2f, 0f, 8f, 10f, 10f, 10f, t) }
        val a0 = anchors[0]; val a1 = anchors[1]; val a2 = anchors[2]
        for (i in 0..20) {
            val t = i / 20f
            val expected = orig(t)
            val actual = if (t <= 0.5f) {
                cubicAt(
                    a0.x, a0.y, a0.outHandle!!.x, a0.outHandle!!.y,
                    a1.inHandle!!.x, a1.inHandle!!.y, a1.x, a1.y, t / 0.5f,
                )
            } else {
                cubicAt(
                    a1.x, a1.y, a1.outHandle!!.x, a1.outHandle!!.y,
                    a2.inHandle!!.x, a2.inHandle!!.y, a2.x, a2.y, (t - 0.5f) / 0.5f,
                )
            }
            assertEquals("x@$t", expected.first, actual.first, 1e-3f)
            assertEquals("y@$t", expected.second, actual.second, 1e-3f)
        }
    }

    @Test
    fun insertOnClosingSegmentWrapsToStart() {
        // A closed triangle: segment index 2 is the closing edge (anchor2 -> anchor0).
        val begun = reduce(stateOn(path("M0,0 L10,0 L10,10 Z")), VectorEditAction.BeginEdit("p"))
        val spId = begun.editing!!.subpaths.single().id
        val after = reducer.reduce(begun, VectorEditAction.InsertAnchorOnSegment(spId, 2, 0.5f))
        val anchors = after.editing!!.subpaths.single().anchors
        assertEquals(4, anchors.size)
        // New anchor sits midway between (10,10) and (0,0).
        assertEquals(5f, anchors.last().x, 1e-4f)
        assertEquals(5f, anchors.last().y, 1e-4f)
    }

    // ---- corner / smooth / symmetric ----

    @Test
    fun anchorTypeConversionsAdjustHandles() {
        // Mid node at (4,0): in-tangent points +x, out-tangent points +y — a corner.
        val begun = reduce(
            stateOn(path("M0,0 C2,-2 2,0 4,0 C4,3 8,3 8,0")),
            VectorEditAction.BeginEdit("p"),
        )
        val mid = begun.editing!!.subpaths.single().anchors[1]
        assertEquals(AnchorType.CORNER, mid.type)

        val smooth = reducer.reduce(begun, VectorEditAction.SetAnchorType(mid.id, AnchorType.SMOOTH))
        val s = smooth.editing!!.subpaths.single().anchors[1]
        assertEquals(AnchorType.SMOOTH, s.type)
        assertColinearThrough(s)

        val sym = reducer.reduce(smooth, VectorEditAction.SetAnchorType(mid.id, AnchorType.SYMMETRIC))
        val y = sym.editing!!.subpaths.single().anchors[1]
        assertEquals(AnchorType.SYMMETRIC, y.type)
        assertColinearThrough(y)
        val inLen = hypot(y.x - y.inHandle!!.x, y.y - y.inHandle!!.y)
        val outLen = hypot(y.outHandle!!.x - y.x, y.outHandle!!.y - y.y)
        assertEquals(outLen, inLen, 1e-3f)

        val corner = reducer.reduce(sym, VectorEditAction.SetAnchorType(mid.id, AnchorType.CORNER))
        assertEquals(AnchorType.CORNER, corner.editing!!.subpaths.single().anchors[1].type)
    }

    // ---- handle dragging ----

    @Test
    fun moveHandleCornerLeavesOppositeUntouched() {
        // Mid anchor[1] at (4,0): in=(2,0), out=(4,3), classified CORNER.
        val begun = reduce(
            stateOn(path("M0,0 C2,-2 2,0 4,0 C4,3 8,3 8,0")),
            VectorEditAction.BeginEdit("p"),
        )
        val mid = begun.editing!!.subpaths.single().anchors[1]
        assertEquals(AnchorType.CORNER, mid.type)
        val after = reducer.reduce(
            begun,
            VectorEditAction.MoveHandle(mid.id, EditHitTest.HandleSide.OUT, 6f, 1f),
        )
        val m = after.editing!!.subpaths.single().anchors[1]
        assertEquals(6f, m.outHandle!!.x, 1e-4f)
        assertEquals(1f, m.outHandle!!.y, 1e-4f)
        // The incoming handle is independent for a corner.
        assertEquals(2f, m.inHandle!!.x, 1e-4f)
        assertEquals(0f, m.inHandle!!.y, 1e-4f)
    }

    @Test
    fun moveHandleSymmetricMirrorsTheOpposite() {
        val begun = reduce(
            stateOn(path("M0,0 C2,-2 2,0 4,0 C4,3 8,3 8,0")),
            VectorEditAction.BeginEdit("p"),
        )
        val mid = begun.editing!!.subpaths.single().anchors[1]
        val symed = reducer.reduce(begun, VectorEditAction.SetAnchorType(mid.id, AnchorType.SYMMETRIC))
        // Drag the outgoing handle to (4,4): anchor (4,0), so the incoming handle
        // must mirror to (4,-4).
        val after = reducer.reduce(
            symed,
            VectorEditAction.MoveHandle(mid.id, EditHitTest.HandleSide.OUT, 4f, 4f),
        )
        val m = after.editing!!.subpaths.single().anchors[1]
        assertEquals(4f, m.outHandle!!.x, 1e-4f)
        assertEquals(4f, m.outHandle!!.y, 1e-4f)
        assertEquals(4f, m.inHandle!!.x, 1e-4f)
        assertEquals(-4f, m.inHandle!!.y, 1e-4f)
    }

    @Test
    fun moveHandleSmoothKeepsOppositeLengthButRealigns() {
        val begun = reduce(
            stateOn(path("M0,0 C2,-2 2,0 4,0 C4,3 8,3 8,0")),
            VectorEditAction.BeginEdit("p"),
        )
        val mid = begun.editing!!.subpaths.single().anchors[1]
        val smoothed = reducer.reduce(begun, VectorEditAction.SetAnchorType(mid.id, AnchorType.SMOOTH))
        val inLenBefore = smoothed.editing!!.subpaths.single().anchors[1].let {
            hypot(it.x - it.inHandle!!.x, it.y - it.inHandle!!.y)
        }
        // Drag the outgoing handle to (7,4): the incoming handle stays colinear
        // through the anchor and retains its own length.
        val after = reducer.reduce(
            smoothed,
            VectorEditAction.MoveHandle(mid.id, EditHitTest.HandleSide.OUT, 7f, 4f),
        )
        val m = after.editing!!.subpaths.single().anchors[1]
        assertColinearThrough(m)
        val inLenAfter = hypot(m.x - m.inHandle!!.x, m.y - m.inHandle!!.y)
        assertEquals(inLenBefore, inLenAfter, 1e-3f)
    }

    @Test
    fun moveHandleUnknownAnchorIsIgnored() {
        val begun = reduce(stateOn(path("M0,0 L10,0")), VectorEditAction.BeginEdit("p"))
        val after = reducer.reduce(
            begun,
            VectorEditAction.MoveHandle("nope", EditHitTest.HandleSide.OUT, 5f, 5f),
        )
        assertEquals(begun.editing, after.editing)
        assertFalse(after.canUndo)
    }

    // ---- write-back ----

    @Test
    fun applyToDocumentWritesEditedGeometryBack() {
        val begun = reduce(stateOn(path("M0,0 L10,0 L10,10 Z")), VectorEditAction.BeginEdit("p"))
        val firstId = begun.editing!!.subpaths.single().anchors[0].id
        val applied = reduce(
            begun,
            VectorEditAction.SelectAnchor(firstId),
            VectorEditAction.MoveSelection(2f, 3f),
            VectorEditAction.ApplyToDocument,
        )
        val written = applied.document.allPaths().single { it.id == "p" }
        assertEquals(PathCommand.MoveTo(2f, 3f), written.commands!!.first())
    }

    @Test
    fun applyToDocumentAppendsABrandNewPenPath() {
        // No path under edit: draw a fresh triangle with the pen and write it back.
        // It must be *appended* (upsert), not dropped, with a visible default fill.
        val drawnFresh = reduce(
            stateOn(path("M0,0 L1,0")),
            VectorEditAction.StartPath,
            VectorEditAction.PlaceAnchor(0f, 0f),
            VectorEditAction.PlaceAnchor(10f, 0f),
            VectorEditAction.PlaceAnchor(10f, 10f),
            VectorEditAction.CommitPath,
            VectorEditAction.ApplyToDocument,
        )
        assertEquals(
            listOf("p", VectorEditReducer.NEW_PATH_ID),
            drawnFresh.document.allPaths().map { it.id },
        )
        val newPath = drawnFresh.document.allPaths().single { it.id == VectorEditReducer.NEW_PATH_ID }
        assertEquals(VectorEditReducer.NEW_PATH_FILL_COLOR, newPath.style.fillColor)
    }

    // ---- undo / redo ----

    @Test
    fun undoRedoInvertsEveryActionExactly() {
        val start = reduce(stateOn(path("M0,0 L10,0 L10,10 Z")), VectorEditAction.BeginEdit("p"))
        val ids = start.editing!!.subpaths.single().anchors.map { it.id }
        val spId = start.editing!!.subpaths.single().id

        // A representative mix of every mutating action.
        val actions = listOf(
            VectorEditAction.MoveSelection(1f, 1f), // no-op (empty selection) but pushes undo
            VectorEditAction.ToggleSubpathClosed(spId),
            VectorEditAction.InsertAnchorOnSegment(spId, 0, 0.5f),
            VectorEditAction.SetAnchorType(ids[1], AnchorType.SMOOTH),
        )

        // Record the state before each action so we can assert exact inversion.
        val history = ArrayList<VectorEditState>()
        var cur = start
        // Give MoveSelection something to move so it actually mutates.
        cur = reducer.reduce(cur, VectorEditAction.SelectAnchor(ids[0]))
        for (a in actions) {
            history += cur
            cur = reducer.reduce(cur, a)
        }

        // Undo back to the start, checking each step lands on the prior geometry.
        for (i in actions.indices.reversed()) {
            cur = reducer.reduce(cur, VectorEditAction.Undo)
            assertEquals("undo[$i] editing", history[i].editing, cur.editing)
            assertEquals("undo[$i] pen", history[i].pendingPen, cur.pendingPen)
        }
        assertFalse(cur.canUndo)

        // Redo forward, checking each step reproduces the post-action geometry.
        var replay = cur
        for (i in actions.indices) {
            replay = reducer.reduce(replay, VectorEditAction.Redo)
            val expected = if (i + 1 < history.size) history[i + 1].editing else null
            if (expected != null) assertEquals("redo[$i] editing", expected, replay.editing)
        }
    }

    @Test
    fun redoIsClearedByANewEdit() {
        val begun = reduce(stateOn(path("M0,0 L10,0 L10,10 Z")), VectorEditAction.BeginEdit("p"))
        val spId = begun.editing!!.subpaths.single().id
        val toggled = reducer.reduce(begun, VectorEditAction.ToggleSubpathClosed(spId))
        val undone = reducer.reduce(toggled, VectorEditAction.Undo)
        assertTrue(undone.canRedo)
        // A fresh mutation drops the redo branch.
        val branched = reducer.reduce(undone, VectorEditAction.ToggleSubpathClosed(spId))
        assertFalse(branched.canRedo)
    }

    @Test
    fun undoHistoryIsCapped() {
        var state = reduce(stateOn(path("M0,0 L10,0 L10,10 Z")), VectorEditAction.BeginEdit("p"))
        val spId = state.editing!!.subpaths.single().id
        repeat(VectorEditReducer.MAX_UNDO + 50) {
            state = reducer.reduce(state, VectorEditAction.ToggleSubpathClosed(spId))
        }
        assertEquals(VectorEditReducer.MAX_UNDO, state.undoStack.size)
    }

    // ---- helpers ----

    private fun assertColinearThrough(a: com.aichat.sandbox.data.vector.edit.EditAnchor) {
        val inH = a.inHandle!!
        val outH = a.outHandle!!
        // Vectors anchor->out and in->anchor must be parallel and same-direction.
        val ox = outH.x - a.x; val oy = outH.y - a.y
        val ix = a.x - inH.x; val iy = a.y - inH.y
        val cross = ox * iy - oy * ix
        val dot = ox * ix + oy * iy
        assertTrue("handles should be colinear (cross=$cross)", abs(cross) < 1e-2f)
        assertTrue("handles should point the same way (dot=$dot)", dot > 0f)
    }

    private fun cubicAt(
        p0x: Float, p0y: Float, c1x: Float, c1y: Float,
        c2x: Float, c2y: Float, p1x: Float, p1y: Float, t: Float,
    ): Pair<Float, Float> {
        val u = 1 - t
        val x = u * u * u * p0x + 3 * u * u * t * c1x + 3 * u * t * t * c2x + t * t * t * p1x
        val y = u * u * u * p0y + 3 * u * u * t * c1y + 3 * u * t * t * c2y + t * t * t * p1y
        return x to y
    }
}
