package com.aichat.sandbox.data.vector.notesbridge

import com.aichat.sandbox.data.notes.EditOp
import com.aichat.sandbox.data.vector.VectorManualEdit

/**
 * Phase 4 — routes the *geometry-free* notes [EditOp]s onto the shared
 * [VectorManualEdit] vocabulary so the same op a user issues on the notes canvas
 * can run against the bridged [com.aichat.sandbox.data.vector.VectorDocument]
 * via [com.aichat.sandbox.data.vector.VectorManualEditApplier].
 *
 * Only the ops that map cleanly to per-path style/geometry edits are converted:
 *
 *  | notes EditOp          | VectorManualEdit            |
 *  | --------------------- | --------------------------- |
 *  | [EditOp.Recolor]      | [VectorManualEdit.RecolorPaths] (stroke) |
 *  | [EditOp.Restyle]      | [VectorManualEdit.RestylePaths] (width)  |
 *  | [EditOp.Delete]       | [VectorManualEdit.DeletePaths]           |
 *  | [EditOp.Simplify]     | [VectorManualEdit.SimplifyPaths]         |
 *
 * Geometry ops ([EditOp.Transform], [EditOp.ReplaceWithShape], [EditOp.Smooth])
 * and canvas-only ops ([EditOp.SetLayer], [EditOp.Group]) are intentionally
 * dropped here — they stay on the ink path or re-vectorize, never duplicated as
 * fragile per-command math (see the phase plan). Pure Kotlin.
 */
object EditOpToManualEdit {

    /**
     * Convert [ops] to manual edits, resolving each op's id space to vector path
     * ids through [idToPathId] (e.g. the bridge's `itemToPathId`, or a composed
     * short-id→uuid→pathId map). Ops with no resolvable target, and unsupported
     * op kinds, are skipped.
     */
    fun convert(ops: List<EditOp>, idToPathId: Map<String, String>): List<VectorManualEdit> =
        ops.mapNotNull { convertOne(it, idToPathId) }

    fun convertOne(op: EditOp, idToPathId: Map<String, String>): VectorManualEdit? {
        val pathIds = op.ids.mapNotNull { idToPathId[it] }
        if (pathIds.isEmpty()) return null
        return when (op) {
            is EditOp.Recolor -> VectorManualEdit.RecolorPaths(
                pathIds = pathIds,
                strokeColor = ColorHex.argb(op.colorArgb),
            )
            is EditOp.Restyle -> {
                val width = op.width ?: return null
                VectorManualEdit.RestylePaths(pathIds = pathIds, strokeWidth = width)
            }
            is EditOp.Delete -> VectorManualEdit.DeletePaths(pathIds = pathIds)
            is EditOp.Simplify -> VectorManualEdit.SimplifyPaths(
                pathIds = pathIds,
                tolerance = op.tolerance,
            )
            // Geometry / canvas-only ops are not expressible as a VectorManualEdit.
            // merge_paths (17.5) folds note-canvas path payloads into one
            // multi-subpath item; the vector lane has no equivalent single-op,
            // so it stays on the notes side.
            // add_path / add_shape (17.5 #1) author new note-canvas geometry;
            // the vector lane re-vectorizes rather than mirroring authoring ops.
            is EditOp.Transform,
            is EditOp.ReplaceWithShape,
            is EditOp.Smooth,
            is EditOp.MergePaths,
            is EditOp.AddPath,
            is EditOp.AddShape,
            is EditOp.SetLayer,
            is EditOp.Group,
            -> null
        }
    }
}
