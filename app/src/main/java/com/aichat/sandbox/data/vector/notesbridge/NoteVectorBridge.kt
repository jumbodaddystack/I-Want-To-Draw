package com.aichat.sandbox.data.vector.notesbridge

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.vector.VectorDocument
import com.aichat.sandbox.data.vector.VectorGroup
import com.aichat.sandbox.data.vector.VectorNode
import com.aichat.sandbox.data.vector.VectorViewport
import com.aichat.sandbox.data.vector.edit.EditablePath
import com.aichat.sandbox.data.vector.edit.EditablePathSerializer

/**
 * How a freehand stroke's pressure-varying width is represented once vectorized.
 *
 *  - [CENTERLINE_UNIFORM] (the Phase-4 default, and the only mode wired today):
 *    a single cubic path along the stroke centerline with one uniform
 *    `strokeWidth` (the width-weighted mean). Matches what the legacy exporters
 *    already did — we just move the collapse into the canonical model so it is
 *    uniform and lossless *from the document's point of view*.
 *  - [OUTLINE_FILL] (deferred): trace both stroke edges into a single filled
 *    path. The enum seam keeps callers stable for when it lands.
 */
enum class WidthMode { CENTERLINE_UNIFORM, OUTLINE_FILL }

/**
 * Outcome of bridging a list of [NoteItem]s into one [VectorDocument].
 *
 * @property document the assembled canonical document (root children in z-order).
 * @property itemToPathId maps each vectorized `NoteItem.id` → its `VectorPath.id`.
 * @property skipped `NoteItem.id`s that produced no geometry (text/image kinds,
 *   empty or degenerate strokes/shapes).
 */
data class NoteVectorResult(
    val document: VectorDocument,
    val itemToPathId: Map<String, String>,
    val skipped: List<String>,
)

/**
 * Phase 4 — the bridge that unifies the two vector worlds.
 *
 * It is the **one-way boundary** (ink → canonical editable [VectorDocument]) the
 * phase's decision record names: notes strokes/shapes are an *input method* that
 * vectorizes into the same model the Tune-Up node editor, boolean ops, grids,
 * and lossless writers already operate on. Going back (document → ink) is left to
 * the round-trip tests and never used to mutate persisted strokes.
 *
 * Pure Kotlin (no Android/Compose imports) so the whole vectorization algebra is
 * unit-tested without a device, exactly like [EditablePathSerializer] and the
 * rest of `data/vector/`.
 */
object NoteVectorBridge {

    /** Stable `VectorPath.id` for a vectorized item, shared by stroke + shape paths. */
    fun pathId(item: NoteItem): String = "note_${item.id}"

    /**
     * Vectorize a whole note's committed [items] into ONE document. Z-order is
     * preserved as document tree order (root children, painter's order). Text and
     * image items, and empty/degenerate strokes/shapes, are recorded in
     * [NoteVectorResult.skipped] rather than dropped silently.
     */
    fun toDocument(
        items: List<NoteItem>,
        viewport: VectorViewport,
        widthMode: WidthMode = WidthMode.CENTERLINE_UNIFORM,
    ): NoteVectorResult {
        val nodes = ArrayList<VectorNode>()
        val itemToPathId = LinkedHashMap<String, String>()
        val skipped = ArrayList<String>()

        for (item in items.sortedBy { it.zIndex }) {
            val editable: EditablePath? = when (item.kind) {
                NoteItem.KIND_STROKE -> strokeToEditablePath(item, widthMode)
                NoteItem.KIND_SHAPE -> shapeToEditablePath(item)
                else -> null // text / image have no vector-path equivalent yet
            }
            if (editable == null) {
                skipped += item.id
                continue
            }
            val path = EditablePathSerializer.toVectorPath(editable)
            nodes += VectorNode.PathNode(path)
            itemToPathId[item.id] = path.id
        }

        val root = VectorGroup(id = "root", children = nodes)
        return NoteVectorResult(
            document = VectorDocument(viewport = viewport, root = root),
            itemToPathId = itemToPathId,
            skipped = skipped,
        )
    }

    /** Single-item entry point: a committed freehand stroke → editable cubic path. */
    fun strokeToEditablePath(
        item: NoteItem,
        widthMode: WidthMode = WidthMode.CENTERLINE_UNIFORM,
    ): EditablePath? = StrokeVectorizer.toEditablePath(item, widthMode)

    /** Single-item entry point: a `kind=shape` item → exact editable cubic path. */
    fun shapeToEditablePath(item: NoteItem): EditablePath? =
        ShapeVectorizer.toEditablePath(item)
}

/**
 * Pure ARGB-int → `#AARRGGBB` hex, kept in this package so the bridge does not
 * depend on `android.graphics.Color` (the legacy notes exporters do, which is
 * why their tests need framework stubs). Uppercase, fully opaque-preserving.
 */
internal object ColorHex {
    fun argb(argb: Int): String = "#%08X".format(argb)
}
