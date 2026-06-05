package com.aichat.sandbox.ui.screens.vector.edit

import com.aichat.sandbox.data.vector.IconTarget
import com.aichat.sandbox.data.vector.OpticalAdjust
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
     * Drag the [side] control handle of anchor [id] to world ([x], [y]). The
     * opposite handle follows the anchor's [com.aichat.sandbox.data.vector.edit.AnchorType]:
     * a `CORNER` moves the dragged handle alone, a `SMOOTH` keeps the opposite
     * handle colinear at its own length, and a `SYMMETRIC` mirrors it exactly.
     */
    data class MoveHandle(
        val id: String,
        val side: EditHitTest.HandleSide,
        val x: Float,
        val y: Float,
    ) : VectorEditAction

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

    // ---- Phase 2: shape algebra (each one undo entry) ----

    /**
     * Combine the **selected subpaths** of the editing path with a boolean [kind].
     * Requires ≥2 subpaths to hold a selected anchor; otherwise a no-op. The
     * consumed subpaths are replaced by the single result (other subpaths preserved).
     */
    data class BooleanOp(val kind: BoolOpKind) : VectorEditAction

    /**
     * Convert the editing path's stroked centerlines into a filled outline. No-op
     * when the path has no positive `strokeWidth`.
     */
    object OutlineStroke : VectorEditAction

    /** Grow (positive) or shrink (negative) the editing path by [delta] world units. */
    data class OffsetPath(val delta: Float) : VectorEditAction

    // ---- Phase 3: pixel-perfect production (overlay / snap / sizes) ----

    /**
     * Toggle the Material keyline-grid overlay. Turning it on derives the grid from
     * the document viewport ([com.aichat.sandbox.data.vector.KeylinePresets.forViewport]);
     * turning it off clears it. Overlay-only — not undoable, no geometry change.
     */
    object ToggleKeyline : VectorEditAction

    /** Set the integer / pixel-grid snap bit ([com.aichat.sandbox.ui.components.notes.EditSnap.MASK_PIXEL]). */
    data class SetPixelSnap(val enabled: Boolean) : VectorEditAction

    /**
     * Set the manual [OpticalAdjust] for [target] in the multi-size set (creating
     * the set from the current document if needed). Adjusts only the derived
     * previews/export — the master geometry is untouched, so it is not undoable.
     */
    data class SetOpticalAdjust(val target: IconTarget, val adjust: OpticalAdjust) : VectorEditAction

    // ---- history + write-back ----

    object Undo : VectorEditAction
    object Redo : VectorEditAction

    /** Serialize [VectorEditState.editing] back into [VectorEditState.document]. */
    object ApplyToDocument : VectorEditAction
}

/** The four boolean combinations exposed in the editor toolbar. */
enum class BoolOpKind { UNION, SUBTRACT, INTERSECT, EXCLUDE }
