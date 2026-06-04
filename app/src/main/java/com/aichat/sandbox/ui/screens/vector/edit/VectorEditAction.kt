package com.aichat.sandbox.ui.screens.vector.edit

import com.aichat.sandbox.data.vector.edit.AnchorType

/**
 * Phase 1 (step 1a) — the sealed set of edit transitions consumed by
 * [VectorEditReducer.reduce]. Mirrors the Tune-Up reducer's pure pattern: each
 * action is a value, the reducer is the only place state changes.
 *
 * Geometry-mutating actions push an undo snapshot; mode/selection actions
 * ([BeginEdit], [SetTool], [SetSnapMask], [SelectAnchor], [ClearSelection]) do not.
 */
sealed interface VectorEditAction {

    /** Enter node-edit on the [pathId] path of the document; resets undo history. */
    data class BeginEdit(val pathId: String) : VectorEditAction

    /** Switch the active gesture tool. */
    data class SetTool(val tool: EditTool) : VectorEditAction

    /** Replace the active snap targets (a `Snap.MASK_*` bitmask). */
    data class SetSnapMask(val mask: Int) : VectorEditAction

    // ---- pen tool ----

    /** Begin a fresh pen subpath, discarding any in-progress draft. */
    object StartPath : VectorEditAction

    /** Append a corner anchor at world ([x], [y]) to the pen draft (snapped). */
    data class PlaceAnchor(val x: Float, val y: Float) : VectorEditAction

    /** Pull symmetric handles out of the just-placed pen anchor toward ([x], [y]). */
    data class DragHandle(val x: Float, val y: Float) : VectorEditAction

    /** Fold the pen draft into [VectorEditState.editing] as a new subpath. */
    object CommitPath : VectorEditAction

    // ---- direct selection ----

    /** Select [id]; [additive] toggles it within the current selection set. */
    data class SelectAnchor(val id: String, val additive: Boolean = false) : VectorEditAction

    /** Clear the anchor selection. */
    object ClearSelection : VectorEditAction

    /** Translate every selected anchor (and its handles) by ([dx], [dy]). */
    data class MoveSelection(val dx: Float, val dy: Float) : VectorEditAction

    /**
     * Insert an anchor on the segment leaving anchor [segmentIndex] of [subpathId]
     * at parameter [t] (0..1), splitting it with a curve-preserving de Casteljau
     * split. For a closed subpath, the last segment index wraps to the start anchor.
     */
    data class InsertAnchorOnSegment(
        val subpathId: String,
        val segmentIndex: Int,
        val t: Float,
    ) : VectorEditAction

    /** Delete every selected anchor; empty subpaths are dropped. */
    object DeleteSelected : VectorEditAction

    /** Reclassify anchor [id] as [type], adjusting its handles to satisfy it. */
    data class SetAnchorType(val id: String, val type: AnchorType) : VectorEditAction

    /** Flip the closed flag of [subpathId]. */
    data class ToggleSubpathClosed(val subpathId: String) : VectorEditAction

    // ---- history + write-back ----

    object Undo : VectorEditAction
    object Redo : VectorEditAction

    /** Serialize [VectorEditState.editing] back into [VectorEditState.document]. */
    object ApplyToDocument : VectorEditAction
}
