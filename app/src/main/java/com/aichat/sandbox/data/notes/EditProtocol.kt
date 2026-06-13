package com.aichat.sandbox.data.notes

/**
 * Sub-phase 7.2 — schema for the model's `edit-ops` response.
 *
 * Each op is restricted to operating on items the model was given in the
 * accompanying [VectorCanvasJson] block. The model identifies items by the
 * short id (`s_001`, `h_001`, …); the applier translates those back to the
 * real on-disk UUIDs via [VectorCanvasJson.SerializedCanvas.idMap] before
 * touching anything.
 *
 * The model is **not** allowed to author new freehand strokes from scratch.
 * [EditOp.ReplaceWithShape] exists so a wobbly hand-drawn circle can be
 * cleaned up to an ellipse, but the source stroke must always be referenced.
 */
sealed interface EditOp {

    /** Short ids the op targets, in JSON-input order. */
    val ids: List<String>

    /**
     * `{ "op": "transform", "ids": [...], "matrix": [a,b,tx, c,d,ty, 0,0,1] }`.
     * Identical semantics to `EditorAction.TransformItems` — the matrix bakes
     * into each item's geometry.
     */
    data class Transform(
        override val ids: List<String>,
        /** Row-major 3×3 affine. */
        val matrix: FloatArray,
    ) : EditOp {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Transform) return false
            return ids == other.ids && matrix.contentEquals(other.matrix)
        }
        override fun hashCode(): Int = 31 * ids.hashCode() + matrix.contentHashCode()
    }

    /** `{ "op": "recolor", "ids": [...], "color": "#RRGGBB" }`. */
    data class Recolor(
        override val ids: List<String>,
        val colorArgb: Int,
    ) : EditOp

    /** `{ "op": "restyle", "ids": [...], "width"?: float, "opacity"?: float }`. */
    data class Restyle(
        override val ids: List<String>,
        val width: Float?,
        val opacity: Float?,
    ) : EditOp

    /**
     * `{ "op": "replace_with_shape", "id": "s_004", "shape": { … } }`.
     * The source stroke is deleted and a `kind=shape` item is inserted in
     * its place with the same colour / width.
     */
    data class ReplaceWithShape(
        val sourceId: String,
        val shape: ShapeSpec,
    ) : EditOp {
        override val ids: List<String> get() = listOf(sourceId)
    }

    /** `{ "op": "smooth", "ids": [...], "amount": 0..1 }`. */
    data class Smooth(
        override val ids: List<String>,
        val amount: Float,
    ) : EditOp

    /** `{ "op": "simplify", "ids": [...], "tolerance": float }`. */
    data class Simplify(
        override val ids: List<String>,
        val tolerance: Float,
    ) : EditOp

    /**
     * `{ "op": "merge_paths", "ids": [...] }` — Phase 17.5. Fold two or more
     * style-compatible `kind=path` items into one multi-subpath path
     * (concatenation, no clipping; holes preserved via subpaths). Sources are
     * removed and one merged path is inserted in the bottom-most source's
     * place. Incompatible styles / non-path ids are dropped by the applier.
     */
    data class MergePaths(
        override val ids: List<String>,
    ) : EditOp

    /** `{ "op": "delete", "ids": [...] }`. */
    data class Delete(
        override val ids: List<String>,
    ) : EditOp

    /** `{ "op": "set_layer", "ids": [...], "layer": "L1" }`. */
    data class SetLayer(
        override val ids: List<String>,
        val targetLayerShortId: String,
    ) : EditOp

    /**
     * `{ "op": "group", "ids": [...] }` — placeholder for Phase 8 frame
     * grouping. Parsed today, rejected by the applier with a friendly
     * "grouping isn't supported yet" until 8.1 lands. Kept in the protocol so
     * the model doesn't have to relearn it when Phase 8 ships.
     */
    data class Group(
        override val ids: List<String>,
    ) : EditOp

    /** Minimal shape description used by [ReplaceWithShape]. */
    sealed interface ShapeSpec {
        data class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : ShapeSpec
        data class Rect(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val r: Float = 0f) : ShapeSpec
        data class Ellipse(
            val cx: Float, val cy: Float,
            val rx: Float, val ry: Float,
            val rotation: Float = 0f,
        ) : ShapeSpec
        data class Arrow(
            val x0: Float, val y0: Float,
            val x1: Float, val y1: Float,
            val head: Float = 0f,
        ) : ShapeSpec
        data class Polygon(
            val points: FloatArray,
            val closed: Boolean,
        ) : ShapeSpec {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Polygon) return false
                return closed == other.closed && points.contentEquals(other.points)
            }
            override fun hashCode(): Int = 31 * points.contentHashCode() + closed.hashCode()
        }
    }
}

/**
 * Parsed `edit-ops` document. [summary] is the model's one-line natural-
 * language explanation, surfaced in the preview sheet so the user sees what
 * the model thinks it did.
 */
data class EditOpsDoc(
    val schema: Int,
    val summary: String,
    val ops: List<EditOp>,
    /** Any ops the parser saw but couldn't accept, with their rejection reasons. */
    val rejected: List<RejectedOp> = emptyList(),
) {
    data class RejectedOp(
        val raw: String,
        val reason: String,
    )

    companion object {
        const val SCHEMA: Int = 1
        val EMPTY: EditOpsDoc = EditOpsDoc(SCHEMA, "", emptyList())
    }
}
