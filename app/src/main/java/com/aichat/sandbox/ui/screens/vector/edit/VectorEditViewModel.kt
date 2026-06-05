package com.aichat.sandbox.ui.screens.vector.edit

import androidx.lifecycle.ViewModel
import com.aichat.sandbox.data.vector.VectorDocument
import com.aichat.sandbox.data.vector.VectorGroup
import com.aichat.sandbox.data.vector.VectorViewport
import com.aichat.sandbox.data.vector.edit.AnchorType
import com.aichat.sandbox.ui.components.notes.ViewportController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * Phase 1 (step 1c) — the `StateFlow` host for the node editor.
 *
 * The whole edit algebra lives in the pure [VectorEditReducer]; this class is the
 * thin Android-coupled shell that the Compose canvas/screen (steps 1d–1e) observe.
 * It mirrors `VectorTuneupViewModel`: hold a [MutableStateFlow], expose it
 * read-only, and run every transition through `reducer.reduce`. There is no
 * coroutine work here — node edits are synchronous and deterministic — so the
 * class is JVM-testable without Robolectric.
 *
 * Beyond plumbing, it owns the [ViewportController] (pan/zoom) and turns raw
 * canvas gestures into reducer actions: a touch is mapped to world space via the
 * viewport, [EditHitTest] decides what sits under the finger, and the result is
 * dispatched (anchor → select, segment → insert, handle → handle-drag). Drags are
 * coalesced so a continuous gesture lands as a single undo step.
 */
@HiltViewModel
class VectorEditViewModel @Inject constructor() : ViewModel() {

    private val reducer = VectorEditReducer()

    /** Pan/zoom for the edit canvas; owned here, read + driven by the canvas (1d). */
    val viewport = ViewportController()

    private val _state = MutableStateFlow(VectorEditState(document = EMPTY_DOCUMENT))
    val state: StateFlow<VectorEditState> = _state.asStateFlow()

    // Transient drag bookkeeping (never part of the undoable state).
    private var dragTarget: DragTarget = DragTarget.None
    private var dragUndoBaseline = 0
    private var dragMutated = false
    private var handleWorldX = 0f
    private var handleWorldY = 0f

    /**
     * Open the editor on [document], optionally entering node-edit on [pathId].
     * Replaces any prior session (and its undo history).
     */
    fun open(document: VectorDocument, pathId: String? = null) {
        _state.value = VectorEditState(document = document)
        if (pathId != null) dispatch(VectorEditAction.BeginEdit(pathId))
    }

    /**
     * Open the editor on [document] ready to draw a **new** path: the existing paths
     * show as static context, the pen tool is armed, and a fresh draft is started so
     * the first tap places an anchor. Committing (Finish) creates the new path; Done
     * appends it to the document (see `VectorDocument.upsertPath`).
     */
    fun openForNewPath(document: VectorDocument) {
        open(document)
        setTool(EditTool.PEN)
        startPath()
    }

    /** Funnel every action through the pure reducer. The single state mutation point. */
    fun dispatch(action: VectorEditAction) {
        _state.update { reducer.reduce(it, action) }
    }

    // ---- ergonomic pass-throughs (the toolbar in 1e calls these) ----

    fun setTool(tool: EditTool) = dispatch(VectorEditAction.SetTool(tool))
    fun setSnapMask(mask: Int) = dispatch(VectorEditAction.SetSnapMask(mask))
    fun startPath() = dispatch(VectorEditAction.StartPath)
    fun commitPath() = dispatch(VectorEditAction.CommitPath)
    fun deleteSelected() = dispatch(VectorEditAction.DeleteSelected)
    fun setAnchorType(id: String, type: AnchorType) = dispatch(VectorEditAction.SetAnchorType(id, type))
    fun toggleClosed(subpathId: String) = dispatch(VectorEditAction.ToggleSubpathClosed(subpathId))
    fun booleanOp(kind: BoolOpKind) = dispatch(VectorEditAction.BooleanOp(kind))
    fun outlineStroke() = dispatch(VectorEditAction.OutlineStroke)
    fun offsetPath(delta: Float) = dispatch(VectorEditAction.OffsetPath(delta))
    fun toggleKeyline() = dispatch(VectorEditAction.ToggleKeyline)
    fun setPixelSnap(enabled: Boolean) = dispatch(VectorEditAction.SetPixelSnap(enabled))
    fun setOpticalAdjust(target: com.aichat.sandbox.data.vector.IconTarget, adjust: com.aichat.sandbox.data.vector.OpticalAdjust) =
        dispatch(VectorEditAction.SetOpticalAdjust(target, adjust))
    fun undo() = dispatch(VectorEditAction.Undo)
    fun redo() = dispatch(VectorEditAction.Redo)
    fun applyToDocument() = dispatch(VectorEditAction.ApplyToDocument)

    // ---- gesture → action mapping ----

    /**
     * A discrete tap at screen ([screenX], [screenY]). With the pen tool it places
     * an anchor; with direct-select it picks whatever [EditHitTest] reports — an
     * anchor selects (additively when [additive]), a segment inserts a node, an
     * empty hit clears the selection. Handles are dragged, not tapped.
     */
    fun onTap(screenX: Float, screenY: Float, additive: Boolean = false) {
        val st = _state.value
        val wx = viewport.screenToWorldX(screenX)
        val wy = viewport.screenToWorldY(screenY)
        if (st.activeTool == EditTool.PEN) {
            dispatch(VectorEditAction.PlaceAnchor(wx, wy))
            return
        }
        val editing = st.editing ?: return
        when (val hit = EditHitTest.hitTest(editing, wx, wy, tolerance(), st.selection)) {
            is EditHitTest.Hit.Anchor -> dispatch(VectorEditAction.SelectAnchor(hit.anchorId, additive))
            is EditHitTest.Hit.Segment ->
                dispatch(VectorEditAction.InsertAnchorOnSegment(hit.subpathId, hit.segmentIndex, hit.t))
            is EditHitTest.Hit.Handle -> Unit
            null -> if (!additive) dispatch(VectorEditAction.ClearSelection)
        }
    }

    /**
     * Begin a drag at screen ([screenX], [screenY]). Resolves what is grabbed — a
     * selected anchor's handle, or an anchor (selecting it first if needed) — and
     * remembers it for [onDrag]. The pen tool and empty hits start no drag.
     */
    fun onDragStart(screenX: Float, screenY: Float) {
        dragTarget = DragTarget.None
        dragMutated = false
        val st = _state.value
        if (st.activeTool == EditTool.PEN) return
        val editing = st.editing ?: return
        val wx = viewport.screenToWorldX(screenX)
        val wy = viewport.screenToWorldY(screenY)
        dragUndoBaseline = st.undoStack.size
        when (val hit = EditHitTest.hitTest(editing, wx, wy, tolerance(), st.selection)) {
            is EditHitTest.Hit.Handle -> {
                val anchor = editing.subpaths.firstNotNullOfOrNull { sp ->
                    sp.anchors.firstOrNull { it.id == hit.anchorId }
                }
                val h = if (hit.side == EditHitTest.HandleSide.IN) anchor?.inHandle else anchor?.outHandle
                handleWorldX = h?.x ?: wx
                handleWorldY = h?.y ?: wy
                dragTarget = DragTarget.Handle(hit.anchorId, hit.side)
            }
            is EditHitTest.Hit.Anchor -> {
                if (hit.anchorId !in st.selection) dispatch(VectorEditAction.SelectAnchor(hit.anchorId))
                dragTarget = DragTarget.MoveSelection
            }
            else -> dragTarget = DragTarget.None
        }
    }

    /**
     * Continue the active drag by screen delta ([dxScreen], [dyScreen]). Deltas are
     * converted to world units; a selection drag translates the selection, a handle
     * drag repositions that handle (tracking absolute world coords).
     */
    fun onDrag(dxScreen: Float, dyScreen: Float) {
        val scale = if (viewport.scale > 0f) viewport.scale else 1f
        val dxW = dxScreen / scale
        val dyW = dyScreen / scale
        when (val t = dragTarget) {
            DragTarget.None -> return
            DragTarget.MoveSelection -> {
                dispatch(VectorEditAction.MoveSelection(dxW, dyW))
                dragMutated = true
            }
            is DragTarget.Handle -> {
                handleWorldX += dxW
                handleWorldY += dyW
                dispatch(VectorEditAction.MoveHandle(t.anchorId, t.side, handleWorldX, handleWorldY))
                dragMutated = true
            }
        }
    }

    /**
     * Finish the drag. A continuous drag pushes one undo snapshot per tick; collapse
     * them so a single Undo reverts the whole gesture to its pre-drag state.
     */
    fun onDragEnd() {
        if (dragMutated) coalesceDragUndo()
        dragTarget = DragTarget.None
        dragMutated = false
    }

    // ---- viewport ----

    fun pan(dxScreen: Float, dyScreen: Float) = viewport.applyPan(dxScreen, dyScreen)

    fun zoom(focusScreenX: Float, focusScreenY: Float, factor: Float) =
        viewport.applyZoom(focusScreenX, focusScreenY, factor)

    // ---- internals ----

    /** Current world-space pick tolerance for the live zoom level. */
    private fun tolerance(): Float =
        EditHitTest.worldTolerance(EditHitTest.DEFAULT_TOLERANCE_PX, viewport.scale)

    /**
     * Trim the undo stack back to the single snapshot captured when the drag began,
     * so the whole drag is one undo step. The baseline-indexed entry is the pre-drag
     * editing slice (the first mutating tick snapshotted it).
     */
    private fun coalesceDragUndo() {
        _state.update { st ->
            val keep = dragUndoBaseline + 1
            if (st.undoStack.size <= keep) st
            else st.copy(undoStack = st.undoStack.take(keep))
        }
    }

    private sealed interface DragTarget {
        object None : DragTarget
        object MoveSelection : DragTarget
        data class Handle(val anchorId: String, val side: EditHitTest.HandleSide) : DragTarget
    }

    companion object {
        /** Placeholder document before [open]; an empty 24×24 artboard. */
        val EMPTY_DOCUMENT = VectorDocument(
            viewport = VectorViewport(24f, 24f, 24f, 24f),
            root = VectorGroup(id = "root", children = emptyList()),
        )
    }
}
