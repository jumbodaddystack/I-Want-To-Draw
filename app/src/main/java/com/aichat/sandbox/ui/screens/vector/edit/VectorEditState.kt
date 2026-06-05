package com.aichat.sandbox.ui.screens.vector.edit

import com.aichat.sandbox.data.vector.IconSizeSet
import com.aichat.sandbox.data.vector.KeylineGrid
import com.aichat.sandbox.data.vector.VectorDocument
import com.aichat.sandbox.data.vector.edit.EditAnchor
import com.aichat.sandbox.data.vector.edit.EditablePath

/**
 * Phase 1 (step 1a) — immutable state for the node editor.
 *
 * This is the editor analogue of `VectorTuneupUiState`: a plain data class with no
 * Compose/Android imports, so the whole edit algebra in [VectorEditReducer] is
 * exercised by JVM unit tests. [VectorEditViewModel] (step 1c) is a thin
 * `StateFlow` shell over the reducer.
 *
 * The source of truth is the immutable [document]. Entering edit on a path derives
 * a live, all-cubic [editing] view (via `EditablePathFactory`); every geometry
 * action mutates that working copy. Writing the result back into [document] is an
 * explicit step (`ApplyToDocument`), so the surrounding version-history machinery
 * keeps owning persistence.
 */
data class VectorEditState(
    /** The immutable document the editor was opened on. */
    val document: VectorDocument,
    /** The path currently in node-edit, or null before entering a path. */
    val editing: EditablePath? = null,
    /** Which gesture tool is active. */
    val activeTool: EditTool = EditTool.DIRECT_SELECT,
    /** Selected anchor ids (drives move/delete/type changes). */
    val selection: Selection = Selection(),
    /** A pen subpath being drawn but not yet committed into [editing]. */
    val pendingPen: PenDraft? = null,
    /** Active snap targets — a bitmask of `Snap.MASK_*` / `EditSnap.MASK_PIXEL`. */
    val snapMask: Int = 0,
    /** Material keyline-grid overlay to draw, or null when the overlay is off (Phase 3). */
    val keyline: KeylineGrid? = null,
    /** Multi-size artboard set + per-size optical adjustments, or null until requested (Phase 3). */
    val sizeSet: IconSizeSet? = null,
    /** Inverse snapshots for undo (most recent last), capped at ~200. */
    val undoStack: List<EditSnapshot> = emptyList(),
    /** Snapshots replayed by redo (most recent last). */
    val redoStack: List<EditSnapshot> = emptyList(),
) {
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Capture the mutable editing slice so undo/redo can restore it exactly. */
    fun snapshot(): EditSnapshot = EditSnapshot(editing, selection, pendingPen)
}

/** The two interaction modes: place/curve new anchors vs. select/move existing ones. */
enum class EditTool { PEN, DIRECT_SELECT }

/** The set of selected anchor ids. */
data class Selection(val anchorIds: Set<String> = emptySet()) {
    val isEmpty: Boolean get() = anchorIds.isEmpty()
    val size: Int get() = anchorIds.size
    operator fun contains(id: String): Boolean = id in anchorIds
}

/** A pen subpath under construction. Becomes an [com.aichat.sandbox.data.vector.edit.EditSubpath] on commit. */
data class PenDraft(val anchors: List<EditAnchor> = emptyList()) {
    val isEmpty: Boolean get() = anchors.isEmpty()
}

/**
 * A point-in-time copy of everything a mutating action can change. Snapshot-based
 * undo (rather than per-action inverse functions) makes "undo returns to the exact
 * prior state" true by construction — [EditablePath] and friends are immutable
 * value types, so a snapshot is cheap and a perfect inverse.
 */
data class EditSnapshot(
    val editing: EditablePath?,
    val selection: Selection,
    val pendingPen: PenDraft?,
)
