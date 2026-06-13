package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.vector.VectorPoint
import com.aichat.sandbox.data.vector.notesbridge.PolylineSimplify
import com.aichat.sandbox.ui.components.notes.EditSnap
import com.aichat.sandbox.ui.components.notes.PathCodec
import com.aichat.sandbox.ui.components.notes.PathMerge
import com.aichat.sandbox.ui.components.notes.StrokeCodec

/**
 * Phase 17.5 follow-on — the one-tap **tidy** pass: compose the existing
 * primitives (simplify + snap-to-grid + `merge_paths`) over a selection into a
 * single net change. Icons accumulate redundant nodes, off-grid anchors, and
 * stray same-style paths after a few edit/boolean rounds; tidy cleans all three
 * in one undo entry:
 *
 *  1. **Simplify** strokes (Ramer–Douglas–Peucker, the same shared
 *     [PolylineSimplify] the AI `simplify` op and the notes→vector bridge use).
 *  2. **Snap** every path anchor to the icon grid via [EditSnap] (clamped to
 *     the artboard so nothing escapes), keeping relative bezier handles intact.
 *  3. **Merge** style-compatible paths into one multi-subpath payload via
 *     [PathMerge] — identical folding to [EditOp.MergePaths] / the canvas
 *     "Merge" action.
 *
 * Pure (no Android/Compose imports) so the algebra is unit-tested on the host
 * JVM. The caller wraps [Result] in one `CompositeEdit` for a single undo step.
 */
object NoteTidy {

    /** Net effect of a tidy pass — the three disjoint lists a `CompositeEdit` needs. */
    data class Result(
        val added: List<NoteItem>,
        val removed: List<NoteItem>,
        val modified: List<Pair<NoteItem, NoteItem>>,
    ) {
        val isEmpty: Boolean get() =
            added.isEmpty() && removed.isEmpty() && modified.isEmpty()
    }

    /** RDP tolerance (world units) for the stroke-simplify step. */
    const val DEFAULT_SIMPLIFY_TOLERANCE: Float = 1.5f

    /**
     * Tidy [items]. [gridStep] is the snap spacing (`<= 0` disables snapping —
     * e.g. a non-icon note); [bounds] (`[minX,minY,maxX,maxY]`) clamps snapped
     * anchors into the artboard when present. [newItemNoteId] stamps the merged
     * results (falls back to the first item's note).
     */
    fun tidy(
        items: List<NoteItem>,
        gridStep: Float,
        bounds: FloatArray?,
        simplifyTolerance: Float = DEFAULT_SIMPLIFY_TOLERANCE,
        newItemNoteId: String? = null,
    ): Result {
        val noteId = newItemNoteId ?: items.firstOrNull()?.noteId ?: ""
        val added = ArrayList<NoteItem>()
        val removed = ArrayList<NoteItem>()
        val modified = ArrayList<Pair<NoteItem, NoteItem>>()

        // (original, snapped) for every path; the snapped geometry feeds the
        // merge step, but `removed`/`modified` must reference the originals.
        val pathPairs = ArrayList<Pair<NoteItem, NoteItem>>()

        for (item in items) {
            when (item.kind) {
                NoteItem.KIND_STROKE -> {
                    val simplified = simplifyStroke(item, simplifyTolerance)
                    if (simplified != null && simplified != item) modified += item to simplified
                }
                PathCodec.KIND -> pathPairs += item to snapPath(item, gridStep, bounds)
                else -> Unit // shapes / text / images pass through untouched
            }
        }

        // Merge style-compatible runs of the snapped paths (z-ordered, so the
        // bottom-most source carries z / layer / group), mirroring the canvas
        // mergeSelectionPaths partitioning.
        val ordered = pathPairs.sortedBy { it.second.zIndex }
        val groups = ArrayList<MutableList<Pair<NoteItem, NoteItem>>>()
        outer@ for (pair in ordered) {
            for (g in groups) {
                if (mergeable(g.first().second, pair.second)) {
                    g.add(pair)
                    continue@outer
                }
            }
            groups.add(mutableListOf(pair))
        }

        for (g in groups) {
            if (g.size >= 2) {
                val merged = PathMerge.merge(g.map { PathCodec.decode(it.second.payload) })
                if (merged != null) {
                    val base = g.first() // lowest zIndex
                    g.forEach { removed += it.first }
                    added += NoteItem(
                        noteId = noteId,
                        zIndex = base.second.zIndex,
                        kind = PathCodec.KIND,
                        tool = null,
                        colorArgb = base.second.colorArgb,
                        baseWidthPx = base.second.baseWidthPx,
                        payload = PathCodec.encode(merged),
                        layerId = base.second.layerId,
                        groupId = base.second.groupId,
                    )
                    continue
                }
                // Merge unexpectedly failed — fall through and treat each as a
                // possible snap-only modification.
            }
            for ((original, snapped) in g) {
                if (snapped != original) modified += original to snapped
            }
        }

        return Result(added = added, removed = removed, modified = modified)
    }

    /** Quantize every path anchor to [step] (clamped to [bounds]); handles unchanged. */
    private fun snapPath(item: NoteItem, step: Float, bounds: FloatArray?): NoteItem {
        if (step <= 0f) return item
        val payload = try {
            PathCodec.decode(item.payload)
        } catch (_: Throwable) {
            return item
        }
        val snapped = payload.copy(
            subpaths = payload.subpaths.map { sub ->
                sub.copy(
                    anchors = sub.anchors.map { a ->
                        val q = if (bounds != null) {
                            EditSnap.quantizeInBounds(a.x, a.y, bounds, step)
                        } else {
                            EditSnap.quantize(a.x, a.y, step)
                        }
                        a.copy(x = q.x, y = q.y)
                    },
                )
            },
        )
        val encoded = PathCodec.encode(snapped)
        // Byte-identical re-encode → nothing moved; keep the original item so
        // no spurious "modified" no-op lands on the undo stack.
        return if (encoded.contentEquals(item.payload)) item else item.copy(payload = encoded)
    }

    /**
     * RDP-simplify a stroke's centerline (numerically identical to
     * [com.aichat.sandbox.data.vector.notesbridge.PolylineSimplify] used
     * elsewhere). Returns the item unchanged when nothing can be dropped.
     */
    private fun simplifyStroke(item: NoteItem, tolerance: Float): NoteItem? {
        if (item.kind != NoteItem.KIND_STROKE || tolerance <= 0f) return item
        val stride = StrokeCodec.FLOATS_PER_SAMPLE
        val samples = try {
            StrokeCodec.decode(item.payload)
        } catch (_: Throwable) {
            return item
        }
        val count = samples.size / stride
        if (count < 3) return item
        val centerline = ArrayList<VectorPoint>(count)
        for (i in 0 until count) centerline += VectorPoint(samples[i * stride], samples[i * stride + 1])
        val keep = PolylineSimplify.keepMask(centerline, tolerance)
        val kept = (0 until count).count { keep[it] }
        if (kept == count) return item
        val out = FloatArray(kept * stride)
        var w = 0
        for (i in 0 until count) {
            if (!keep[i]) continue
            System.arraycopy(samples, i * stride, out, w * stride, stride)
            w++
        }
        return item.copy(payload = StrokeCodec.encode(out))
    }

    /** Two paths fold together when style (colour + width) and payload are compatible. */
    private fun mergeable(a: NoteItem, b: NoteItem): Boolean =
        a.colorArgb == b.colorArgb && a.baseWidthPx == b.baseWidthPx &&
            PathMerge.compatible(PathCodec.decode(a.payload), PathCodec.decode(b.payload))
}
