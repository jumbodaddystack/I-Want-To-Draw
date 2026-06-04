package com.aichat.sandbox.ui.screens.vector.edit

import com.aichat.sandbox.data.vector.VectorPath
import com.aichat.sandbox.data.vector.VectorStyle
import com.aichat.sandbox.data.vector.allPaths
import com.aichat.sandbox.data.vector.edit.AnchorType
import com.aichat.sandbox.data.vector.edit.ControlPoint
import com.aichat.sandbox.data.vector.edit.EditAnchor
import com.aichat.sandbox.data.vector.edit.EditSubpath
import com.aichat.sandbox.data.vector.edit.EditablePath
import com.aichat.sandbox.data.vector.edit.EditablePathFactory
import com.aichat.sandbox.data.vector.edit.EditablePathSerializer
import com.aichat.sandbox.data.vector.upsertPath
import com.aichat.sandbox.ui.components.notes.Snap
import kotlin.math.hypot

/**
 * Phase 1 (step 1a) — the pure node-editor state machine.
 *
 * `reduce(state, action)` is a total `(VectorEditState, VectorEditAction) ->
 * VectorEditState` function with no Compose/Android imports, so the entire edit
 * algebra is unit-tested on the JVM exactly like [com.aichat.sandbox.ui.screens.vector.VectorTuneupReducer].
 * It reuses the Phase 0 editable model (`EditablePathFactory`/`EditablePathSerializer`)
 * and the pure `Snap` primitives; the canvas/ViewModel (steps 1c–1e) are thin
 * shells that translate gestures into these actions and render the result.
 *
 * **Undo model:** every geometry-mutating action records a snapshot of the editing
 * slice before changing it (see [EditSnapshot]). Undo/redo restore snapshots, so
 * "undo returns to the exact prior state" holds by construction. Mode and selection
 * actions are deliberately non-undoable.
 */
class VectorEditReducer {

    fun reduce(state: VectorEditState, action: VectorEditAction): VectorEditState =
        when (action) {
            is VectorEditAction.BeginEdit -> beginEdit(state, action.pathId)
            is VectorEditAction.SetTool -> state.copy(activeTool = action.tool)
            is VectorEditAction.SetSnapMask -> state.copy(snapMask = action.mask)

            is VectorEditAction.StartPath ->
                state.pushingUndo().copy(activeTool = EditTool.PEN, pendingPen = PenDraft())
            is VectorEditAction.PlaceAnchor -> placeAnchor(state, action.x, action.y)
            is VectorEditAction.DragHandle -> dragHandle(state, action.x, action.y)
            is VectorEditAction.CommitPath -> commitPath(state)

            is VectorEditAction.SelectAnchor -> selectAnchor(state, action.id, action.additive)
            is VectorEditAction.ClearSelection -> state.copy(selection = Selection())
            is VectorEditAction.MoveSelection -> moveSelection(state, action.dx, action.dy)
            is VectorEditAction.MoveHandle -> moveHandle(state, action.id, action.side, action.x, action.y)
            is VectorEditAction.InsertAnchorOnSegment ->
                insertAnchor(state, action.subpathId, action.segmentIndex, action.t)
            is VectorEditAction.DeleteSelected -> deleteSelected(state)
            is VectorEditAction.SetAnchorType -> setAnchorType(state, action.id, action.type)
            is VectorEditAction.ToggleSubpathClosed -> toggleClosed(state, action.subpathId)

            is VectorEditAction.Undo -> undo(state)
            is VectorEditAction.Redo -> redo(state)
            is VectorEditAction.ApplyToDocument -> applyToDocument(state)
        }

    // ---- mode entry ----

    private fun beginEdit(state: VectorEditState, pathId: String): VectorEditState {
        val path = state.document.allPaths().firstOrNull { it.id == pathId } ?: return state
        return state.copy(
            editing = EditablePathFactory.fromPath(path),
            selection = Selection(),
            pendingPen = null,
            undoStack = emptyList(),
            redoStack = emptyList(),
        )
    }

    // ---- pen tool ----

    private fun placeAnchor(state: VectorEditState, x: Float, y: Float): VectorEditState {
        val draft = state.pendingPen ?: PenDraft()
        val previous = draft.anchors.lastOrNull()?.let { it.x to it.y }
        val (sx, sy) = snap(state, x, y, previous)
        val anchor = EditAnchor(id = "draft.a${draft.anchors.size}", x = sx, y = sy)
        return state.pushingUndo().copy(
            activeTool = EditTool.PEN,
            pendingPen = draft.copy(anchors = draft.anchors + anchor),
        )
    }

    private fun dragHandle(state: VectorEditState, x: Float, y: Float): VectorEditState {
        val draft = state.pendingPen ?: return state
        val last = draft.anchors.lastOrNull() ?: return state
        // Symmetric pull: the outgoing handle follows the cursor, the incoming one
        // mirrors it through the anchor.
        val out = ControlPoint(x, y)
        val inH = ControlPoint(2f * last.x - x, 2f * last.y - y)
        val updated = last.copy(inHandle = inH, outHandle = out, type = AnchorType.SYMMETRIC)
        return state.pushingUndo().copy(
            pendingPen = draft.copy(anchors = draft.anchors.dropLast(1) + updated),
        )
    }

    private fun commitPath(state: VectorEditState): VectorEditState {
        val draft = state.pendingPen ?: return state
        if (draft.anchors.size < 2) {
            // Not enough to be a subpath; just discard the draft (still undoable).
            return state.pushingUndo().copy(pendingPen = null)
        }
        val editing = state.editing
        val pathId = editing?.pathId ?: NEW_PATH_ID
        val index = editing?.subpaths?.size ?: 0
        val subpathId = "$pathId.s$index"
        val anchors = draft.anchors.mapIndexed { j, a -> a.copy(id = "$subpathId.a$j") }
        val subpath = EditSubpath(id = subpathId, anchors = anchors, closed = false)
        // A brand-new path (drawn with no path under edit) needs a visible default
        // style — an all-null VectorStyle renders nothing once written back/exported.
        val newEditing = editing?.copy(subpaths = editing.subpaths + subpath)
            ?: EditablePath(
                pathId = pathId,
                subpaths = listOf(subpath),
                style = VectorStyle(fillColor = NEW_PATH_FILL_COLOR),
            )
        return state.pushingUndo().copy(
            editing = newEditing,
            pendingPen = null,
            activeTool = EditTool.DIRECT_SELECT,
            selection = Selection(),
        )
    }

    // ---- direct selection ----

    private fun selectAnchor(state: VectorEditState, id: String, additive: Boolean): VectorEditState {
        val editing = state.editing ?: return state
        if (editing.subpaths.none { sp -> sp.anchors.any { it.id == id } }) return state
        val ids = if (additive) {
            if (id in state.selection.anchorIds) state.selection.anchorIds - id
            else state.selection.anchorIds + id
        } else {
            setOf(id)
        }
        return state.copy(selection = Selection(ids))
    }

    private fun moveSelection(state: VectorEditState, dx: Float, dy: Float): VectorEditState {
        val editing = state.editing ?: return state
        if (state.selection.isEmpty) return state
        var adjDx = dx
        var adjDy = dy
        // When a single anchor is dragged onto the grid, latch it (the multi-select
        // case is ambiguous, so it translates verbatim).
        if (state.snapMask and Snap.MASK_GRID != 0 && state.selection.size == 1) {
            val a = editing.subpaths.firstNotNullOfOrNull { sp ->
                sp.anchors.firstOrNull { it.id in state.selection.anchorIds }
            }
            if (a != null) {
                val r = Snap.snapToGrid(a.x + dx, a.y + dy)
                if (r.snapped) {
                    adjDx = r.x - a.x
                    adjDy = r.y - a.y
                }
            }
        }
        val moved = editing.mapAnchors { a ->
            if (a.id in state.selection.anchorIds) a.translated(adjDx, adjDy) else a
        }
        return state.pushingUndo().copy(editing = moved)
    }

    /**
     * Drag one control handle of a committed anchor. The dragged side lands on
     * ([x], [y]); the opposite side is reconciled to honor the anchor's
     * [AnchorType] (see [reconcileOpposite]). A [AnchorType.CORNER] (or an anchor
     * not under edit) leaves the other handle untouched.
     */
    private fun moveHandle(
        state: VectorEditState,
        id: String,
        side: EditHitTest.HandleSide,
        x: Float,
        y: Float,
    ): VectorEditState {
        val editing = state.editing ?: return state
        if (editing.subpaths.none { sp -> sp.anchors.any { it.id == id } }) return state
        val updated = editing.mapAnchors { a -> if (a.id == id) dragHandleOn(a, side, x, y) else a }
        return state.pushingUndo().copy(editing = updated)
    }

    /** Place [side]'s handle at ([x], [y]) and reconcile the opposite side. */
    private fun dragHandleOn(
        anchor: EditAnchor,
        side: EditHitTest.HandleSide,
        x: Float,
        y: Float,
    ): EditAnchor {
        val dragged = ControlPoint(x, y)
        val withDragged = when (side) {
            EditHitTest.HandleSide.IN -> anchor.copy(inHandle = dragged)
            EditHitTest.HandleSide.OUT -> anchor.copy(outHandle = dragged)
        }
        return when (anchor.type) {
            AnchorType.CORNER -> withDragged
            AnchorType.SMOOTH -> reconcileOpposite(withDragged, side, equalLength = false)
            AnchorType.SYMMETRIC -> reconcileOpposite(withDragged, side, equalLength = true)
        }
    }

    /**
     * Keep the handle opposite the just-dragged [draggedSide] colinear through the
     * anchor (continuous tangent). [equalLength] mirrors it to the dragged length
     * (symmetric); otherwise it retains its own length (smooth).
     */
    private fun reconcileOpposite(
        anchor: EditAnchor,
        draggedSide: EditHitTest.HandleSide,
        equalLength: Boolean,
    ): EditAnchor {
        val dragged = when (draggedSide) {
            EditHitTest.HandleSide.IN -> anchor.inHandle
            EditHitTest.HandleSide.OUT -> anchor.outHandle
        } ?: return anchor
        val dirX = dragged.x - anchor.x
        val dirY = dragged.y - anchor.y
        val len = hypot(dirX, dirY)
        if (len < EPS) return anchor
        val ux = dirX / len
        val uy = dirY / len
        val opposite = when (draggedSide) {
            EditHitTest.HandleSide.IN -> anchor.outHandle
            EditHitTest.HandleSide.OUT -> anchor.inHandle
        }
        val oppLen = if (equalLength) len
        else opposite?.let { hypot(it.x - anchor.x, it.y - anchor.y) } ?: len
        // Opposite handle sits on the ray pointing away from the dragged one.
        val oppPoint = ControlPoint(anchor.x - ux * oppLen, anchor.y - uy * oppLen)
        return when (draggedSide) {
            EditHitTest.HandleSide.IN -> anchor.copy(outHandle = oppPoint)
            EditHitTest.HandleSide.OUT -> anchor.copy(inHandle = oppPoint)
        }
    }

    private fun insertAnchor(
        state: VectorEditState,
        subpathId: String,
        segmentIndex: Int,
        t: Float,
    ): VectorEditState {
        val editing = state.editing ?: return state
        val spIndex = editing.subpaths.indexOfFirst { it.id == subpathId }
        if (spIndex < 0) return state
        val sp = editing.subpaths[spIndex]
        val n = sp.anchors.size
        if (segmentIndex < 0 || segmentIndex >= n) return state
        val isClosing = segmentIndex == n - 1 && sp.closed
        val aIndex = segmentIndex
        val bIndex = if (isClosing) 0 else segmentIndex + 1
        if (bIndex >= n || aIndex == bIndex) return state

        val a = sp.anchors[aIndex]
        val b = sp.anchors[bIndex]
        val (newA, mid, newB) = splitSegment(a, b, t.coerceIn(0f, 1f), "$subpathId.m$n")

        val anchors = sp.anchors.toMutableList()
        anchors[aIndex] = newA
        anchors[bIndex] = newB
        if (isClosing) anchors.add(mid) else anchors.add(aIndex + 1, mid)

        val newSubpaths = editing.subpaths.toMutableList()
        newSubpaths[spIndex] = sp.copy(anchors = anchors)
        return state.pushingUndo().copy(editing = editing.copy(subpaths = newSubpaths))
    }

    private fun deleteSelected(state: VectorEditState): VectorEditState {
        val editing = state.editing ?: return state
        if (state.selection.isEmpty) return state
        val sel = state.selection.anchorIds
        val newSubpaths = editing.subpaths
            .map { sp -> sp.copy(anchors = sp.anchors.filterNot { it.id in sel }) }
            .filter { it.anchors.isNotEmpty() }
        return state.pushingUndo().copy(
            editing = editing.copy(subpaths = newSubpaths),
            selection = Selection(),
        )
    }

    private fun setAnchorType(state: VectorEditState, id: String, type: AnchorType): VectorEditState {
        val editing = state.editing ?: return state
        if (editing.subpaths.none { sp -> sp.anchors.any { it.id == id } }) return state
        val updated = editing.mapAnchors { a -> if (a.id == id) applyType(a, type) else a }
        return state.pushingUndo().copy(editing = updated)
    }

    private fun toggleClosed(state: VectorEditState, subpathId: String): VectorEditState {
        val editing = state.editing ?: return state
        if (editing.subpaths.none { it.id == subpathId }) return state
        val updated = editing.copy(
            subpaths = editing.subpaths.map {
                if (it.id == subpathId) it.copy(closed = !it.closed) else it
            },
        )
        return state.pushingUndo().copy(editing = updated)
    }

    // ---- history ----

    private fun undo(state: VectorEditState): VectorEditState {
        val prev = state.undoStack.lastOrNull() ?: return state
        return state.copy(
            editing = prev.editing,
            selection = prev.selection,
            pendingPen = prev.pendingPen,
            undoStack = state.undoStack.dropLast(1),
            redoStack = (state.redoStack + state.snapshot()).takeLast(MAX_UNDO),
        )
    }

    private fun redo(state: VectorEditState): VectorEditState {
        val next = state.redoStack.lastOrNull() ?: return state
        return state.copy(
            editing = next.editing,
            selection = next.selection,
            pendingPen = next.pendingPen,
            redoStack = state.redoStack.dropLast(1),
            undoStack = (state.undoStack + state.snapshot()).takeLast(MAX_UNDO),
        )
    }

    // ---- write-back ----

    private fun applyToDocument(state: VectorEditState): VectorEditState {
        val editing = state.editing ?: return state
        val newPath = EditablePathSerializer.toVectorPath(editing)
        // upsert (not replace): a path drawn from scratch isn't in the document yet,
        // so it must be appended rather than dropped.
        return state.copy(document = state.document.upsertPath(editing.pathId, newPath))
    }

    // ---- snapping ----

    /**
     * Snap a placed point through the active [VectorEditState.snapMask]. Priority:
     * endpoint (land exactly on an existing/draft vertex) wins outright; otherwise
     * angle snap (relative to [previous]) then grid snap refine the position.
     */
    private fun snap(
        state: VectorEditState,
        x: Float,
        y: Float,
        previous: Pair<Float, Float>?,
    ): Pair<Float, Float> {
        var sx = x
        var sy = y
        val mask = state.snapMask
        if (mask and Snap.MASK_ENDPOINT != 0) {
            val candidates = endpointCandidates(state)
            val r = Snap.snapToEndpoints(sx, sy, candidates, ENDPOINT_RADIUS_WORLD)
            if (r.snapped) return r.x to r.y
        }
        if (mask and Snap.MASK_ANGLE != 0 && previous != null) {
            val r = Snap.snapAngleTo(previous.first, previous.second, sx, sy)
            if (r.snapped) {
                sx = r.x
                sy = r.y
            }
        }
        if (mask and Snap.MASK_GRID != 0) {
            val r = Snap.snapToGrid(sx, sy)
            if (r.snapped) {
                sx = r.x
                sy = r.y
            }
        }
        return sx to sy
    }

    /** Existing anchor termini (committed + draft) as `[x0, y0, x1, y1, …]` snap targets. */
    private fun endpointCandidates(state: VectorEditState): FloatArray {
        val pts = ArrayList<Float>()
        state.editing?.subpaths?.forEach { sp ->
            sp.anchors.forEach { pts += it.x; pts += it.y }
        }
        state.pendingPen?.anchors?.forEach { pts += it.x; pts += it.y }
        return pts.toFloatArray()
    }

    // ---- geometry helpers ----

    /**
     * Curve-preserving split of the segment `from → to` at [t] via de Casteljau.
     * A handleless segment splits as a straight line; a cubic splits into two
     * cubics that trace the original curve exactly. Returns the (possibly updated)
     * endpoints plus the new midpoint anchor.
     */
    private fun splitSegment(
        from: EditAnchor,
        to: EditAnchor,
        t: Float,
        midId: String,
    ): Triple<EditAnchor, EditAnchor, EditAnchor> {
        val out = from.outHandle
        val inc = to.inHandle
        if (out == null && inc == null) {
            val mid = EditAnchor(
                id = midId,
                x = from.x + (to.x - from.x) * t,
                y = from.y + (to.y - from.y) * t,
            )
            return Triple(from, mid, to)
        }
        // Degenerate handles fall back to their own anchor (matches the serializer).
        val p1x = out?.x ?: from.x; val p1y = out?.y ?: from.y
        val p2x = inc?.x ?: to.x; val p2y = inc?.y ?: to.y

        val ax = lerp(from.x, p1x, t); val ay = lerp(from.y, p1y, t)
        val bx = lerp(p1x, p2x, t); val by = lerp(p1y, p2y, t)
        val cx = lerp(p2x, to.x, t); val cy = lerp(p2y, to.y, t)
        val dx = lerp(ax, bx, t); val dy = lerp(ay, by, t)
        val ex = lerp(bx, cx, t); val ey = lerp(by, cy, t)
        val fx = lerp(dx, ex, t); val fy = lerp(dy, ey, t)

        val newFrom = from.copy(outHandle = ControlPoint(ax, ay))
        val mid = EditAnchor(
            id = midId,
            x = fx, y = fy,
            inHandle = ControlPoint(dx, dy),
            outHandle = ControlPoint(ex, ey),
            type = AnchorType.SMOOTH,
        )
        val newTo = to.copy(inHandle = ControlPoint(cx, cy))
        return Triple(newFrom, mid, newTo)
    }

    /** Reclassify a node, adjusting handles to satisfy the target [type]. */
    private fun applyType(anchor: EditAnchor, type: AnchorType): EditAnchor = when (type) {
        AnchorType.CORNER -> anchor.copy(type = AnchorType.CORNER) // relabel; handles untouched
        AnchorType.SMOOTH -> smooth(anchor, equalLength = false)
        AnchorType.SYMMETRIC -> smooth(anchor, equalLength = true)
    }

    /**
     * Align an anchor's handles colinearly through it. The outgoing handle's
     * direction is taken as the tangent (falling back to the incoming one); the
     * incoming handle is placed on the opposite ray. [equalLength] forces both
     * handles to the outgoing length (a symmetric node); otherwise each side keeps
     * its own length (a smooth node).
     */
    private fun smooth(anchor: EditAnchor, equalLength: Boolean): EditAnchor {
        val out = anchor.outHandle
        val inH = anchor.inHandle
        val (dirX, dirY) = when {
            out != null -> (out.x - anchor.x) to (out.y - anchor.y)
            inH != null -> (anchor.x - inH.x) to (anchor.y - inH.y)
            else -> return anchor.copy(type = AnchorType.CORNER) // nothing to align
        }
        val len = hypot(dirX, dirY)
        if (len < EPS) return anchor.copy(type = AnchorType.CORNER)
        val ux = dirX / len
        val uy = dirY / len
        val outLen = out?.let { hypot(it.x - anchor.x, it.y - anchor.y) } ?: len
        val inLen = if (equalLength) outLen else inH?.let { hypot(anchor.x - it.x, anchor.y - it.y) } ?: len
        return anchor.copy(
            outHandle = ControlPoint(anchor.x + ux * outLen, anchor.y + uy * outLen),
            inHandle = ControlPoint(anchor.x - ux * inLen, anchor.y - uy * inLen),
            type = if (equalLength) AnchorType.SYMMETRIC else AnchorType.SMOOTH,
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun EditAnchor.translated(dx: Float, dy: Float): EditAnchor = copy(
        x = x + dx,
        y = y + dy,
        inHandle = inHandle?.let { ControlPoint(it.x + dx, it.y + dy) },
        outHandle = outHandle?.let { ControlPoint(it.x + dx, it.y + dy) },
    )

    private fun EditablePath.mapAnchors(transform: (EditAnchor) -> EditAnchor): EditablePath =
        copy(subpaths = subpaths.map { sp -> sp.copy(anchors = sp.anchors.map(transform)) })

    /** Snapshot the current editing slice for undo, capping history and clearing redo. */
    private fun VectorEditState.pushingUndo(): VectorEditState = copy(
        undoStack = (undoStack + snapshot()).takeLast(MAX_UNDO),
        redoStack = emptyList(),
    )

    companion object {
        /** Undo depth cap, mirroring the notes editor's bounded history. */
        const val MAX_UNDO = 200

        /** Id given to a brand-new path created by drawing with no path under edit. */
        const val NEW_PATH_ID = "edit-path"

        /** Default fill for a brand-new pen path, so it's visible once written back. */
        const val NEW_PATH_FILL_COLOR = "#000000"

        /** World-space radius for endpoint snapping while drawing. */
        const val ENDPOINT_RADIUS_WORLD = 8f

        private const val EPS = 1e-4f
    }
}
