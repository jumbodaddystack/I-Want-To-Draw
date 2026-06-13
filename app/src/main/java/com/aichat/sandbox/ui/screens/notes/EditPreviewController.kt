package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import com.aichat.sandbox.data.notes.EditOp
import com.aichat.sandbox.data.notes.EditOpsDoc
import com.aichat.sandbox.data.vector.VectorPoint
import com.aichat.sandbox.data.vector.notesbridge.PolylineSimplify
import com.aichat.sandbox.ui.components.notes.ImageItemCodec
import com.aichat.sandbox.ui.components.notes.PathCodec
import com.aichat.sandbox.ui.components.notes.PathMerge
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.aichat.sandbox.ui.components.notes.StrokeTransform
import com.aichat.sandbox.ui.components.notes.TextItemCodec

/**
 * Sub-phase 7.4 — pure simulator for an [EditOpsDoc] against a current item
 * list. Returns the (added, removed, modified) tuple without touching repo
 * state — the editor reuses the result to:
 *
 *  1. Render a dry-run preview overlay (translucent magenta).
 *  2. Build a single [EditorAction.CompositeEdit] on user accept.
 *
 * Defense in depth: every op is re-validated against the live items here,
 * even when [com.aichat.sandbox.data.notes.EditOpsParser] already filtered
 * by short id. Ids that don't resolve, references to locked / hidden
 * layers, and ops that would no-op (identity matrix, same-colour recolor)
 * are silently dropped — the safety checklist (7.6) requires the applier
 * to act as a hard backstop.
 */
object EditPreviewController {

    /** Result of [simulate]: three disjoint lists describing the net effect. */
    data class Simulation(
        val added: List<NoteItem>,
        val removed: List<NoteItem>,
        val modified: List<Pair<NoteItem, NoteItem>>, // (before, after)
        /** Ops that were syntactically valid but couldn't be applied. */
        val skipped: List<String>,
    ) {
        val isEmpty: Boolean get() =
            added.isEmpty() && removed.isEmpty() && modified.isEmpty()
    }

    fun simulate(
        currentItems: List<NoteItem>,
        doc: EditOpsDoc,
        idMap: Map<String, String>,
        layerMap: Map<String, String>,
        layers: List<NoteLayer>,
        /**
         * Phase 17.5 #1 — note id stamped onto items the model *authors*
         * (`add_path` / `add_shape`). Defaults to the first current item's
         * note (matches the editor) or "" for a blank artboard; the editor
         * passes the live note id explicitly so generated items persist.
         */
        newItemNoteId: String? = null,
        /**
         * Phase 17.5 #2 — translation `[dx, dy]` applied to items the model
         * *authors* (`add_path` / `add_shape`), so a "Make real" refine lands
         * the cleaned vector *next to* the original sketch rather than on top
         * of it. Null leaves authored geometry at the model's coordinates.
         */
        authoredOffset: FloatArray? = null,
    ): Simulation {
        val byShortId: Map<String, NoteItem> = buildMap {
            for ((short, uuid) in idMap) {
                currentItems.firstOrNull { it.id == uuid }?.let { put(short, it) }
            }
        }
        val lockedLayers: Set<String> = layers.filter { it.locked }.mapTo(HashSet()) { it.id }
        val originals = HashMap<String, NoteItem>(currentItems.size)
        // Track per-id "current after intermediate ops" so multiple ops on
        // the same id compose (e.g. recolor + transform).
        val working = HashMap<String, NoteItem>()
        val toRemove = LinkedHashSet<String>()
        val toAdd = ArrayList<NoteItem>()
        val skipped = ArrayList<String>()

        // 17.5 #1 — context for items the model authors from scratch.
        val authorNoteId = newItemNoteId ?: currentItems.firstOrNull()?.noteId ?: ""
        var nextAuthoredZ = (currentItems.maxOfOrNull { it.zIndex } ?: -1) + 1

        fun fetch(shortId: String): NoteItem? {
            val item = working[shortId] ?: byShortId[shortId] ?: return null
            // Refuse to touch locked-layer items.
            if (item.layerId != null && item.layerId in lockedLayers) return null
            originals.getOrPut(shortId) { byShortId[shortId] ?: item }
            return item
        }

        fun stash(shortId: String, next: NoteItem) {
            working[shortId] = next
        }

        for (op in doc.ops) {
            when (op) {
                is EditOp.Transform -> {
                    if (StrokeTransform.isIdentity(op.matrix)) continue
                    for (sid in op.ids) {
                        val current = fetch(sid)
                        if (current == null) {
                            skipped += "transform $sid (unknown/locked)"
                            continue
                        }
                        val next = transformItem(current, op.matrix)
                        if (next == null) {
                            skipped += "transform $sid (unsupported kind)"
                            continue
                        }
                        stash(sid, next)
                    }
                }
                is EditOp.Recolor -> {
                    for (sid in op.ids) {
                        val current = fetch(sid)
                        if (current == null) {
                            skipped += "recolor $sid (unknown/locked)"
                            continue
                        }
                        if (current.colorArgb == op.colorArgb) continue
                        stash(sid, current.copy(colorArgb = op.colorArgb))
                    }
                }
                is EditOp.Restyle -> {
                    for (sid in op.ids) {
                        val current = fetch(sid)
                        if (current == null) {
                            skipped += "restyle $sid (unknown/locked)"
                            continue
                        }
                        val width = op.width ?: current.baseWidthPx
                        stash(sid, current.copy(baseWidthPx = width))
                        // Opacity isn't stored as a separate field today; the
                        // model uses recolor with alpha for now.
                    }
                }
                is EditOp.Smooth -> {
                    val iterations = (op.amount * 4f).toInt().coerceIn(0, 4)
                    if (iterations == 0) continue
                    for (sid in op.ids) {
                        val current = fetch(sid)
                        if (current == null) {
                            skipped += "smooth $sid (unknown/locked)"
                            continue
                        }
                        val smoothed = smoothStroke(current, iterations)
                        if (smoothed == null) {
                            skipped += "smooth $sid (not a stroke)"
                            continue
                        }
                        stash(sid, smoothed)
                    }
                }
                is EditOp.Simplify -> {
                    for (sid in op.ids) {
                        val current = fetch(sid)
                        if (current == null) {
                            skipped += "simplify $sid (unknown/locked)"
                            continue
                        }
                        val simplified = simplifyStroke(current, op.tolerance)
                        if (simplified == null) {
                            skipped += "simplify $sid (not a stroke)"
                            continue
                        }
                        stash(sid, simplified)
                    }
                }
                is EditOp.MergePaths -> {
                    // Collect the live path items (post any earlier ops), in
                    // input order, then fold style-compatible ones together.
                    val sourceShortIds = ArrayList<String>()
                    val sources = ArrayList<NoteItem>()
                    for (sid in op.ids) {
                        val current = fetch(sid) ?: continue
                        if (current.kind != PathCodec.KIND) continue
                        sourceShortIds += sid
                        sources += current
                    }
                    if (sources.size < 2) {
                        skipped += "merge_paths (need ≥2 path items)"
                        continue
                    }
                    val merged = mergePathItems(sources)
                    if (merged == null) {
                        skipped += "merge_paths (incompatible styles)"
                        continue
                    }
                    for (sid in sourceShortIds) working.remove(sid)
                    for (s in sources) toRemove += s.id
                    toAdd += merged
                }
                is EditOp.Delete -> {
                    for (sid in op.ids) {
                        val current = fetch(sid)
                        if (current == null) {
                            skipped += "delete $sid (unknown/locked)"
                            continue
                        }
                        // Remove from `working` so subsequent ops don't see
                        // a stale modified version.
                        working.remove(sid)
                        toRemove += current.id
                    }
                }
                is EditOp.SetLayer -> {
                    val targetUuid = layerMap[op.targetLayerShortId]
                    if (targetUuid == null) {
                        skipped += "set_layer (unknown target ${op.targetLayerShortId})"
                        continue
                    }
                    val targetLayer = layers.firstOrNull { it.id == targetUuid }
                    if (targetLayer == null || targetLayer.locked) {
                        skipped += "set_layer (target locked)"
                        continue
                    }
                    for (sid in op.ids) {
                        val current = fetch(sid)
                        if (current == null) {
                            skipped += "set_layer $sid (unknown/locked)"
                            continue
                        }
                        if (current.layerId == targetUuid) continue
                        stash(sid, current.copy(layerId = targetUuid))
                    }
                }
                is EditOp.ReplaceWithShape -> {
                    val source = fetch(op.sourceId)
                    if (source == null) {
                        skipped += "replace_with_shape ${op.sourceId} (unknown/locked)"
                        continue
                    }
                    val replacement = buildShapeReplacement(source, op.shape)
                    working.remove(op.sourceId)
                    toRemove += source.id
                    toAdd += replacement
                }
                is EditOp.AddPath -> {
                    val item = buildAddedPath(op, authorNoteId, nextAuthoredZ)
                    if (item == null) {
                        skipped += "add_path (empty geometry)"
                        continue
                    }
                    nextAuthoredZ++
                    toAdd += offsetAuthored(item, authoredOffset)
                }
                is EditOp.AddShape -> {
                    toAdd += offsetAuthored(buildAddedShape(op, authorNoteId, nextAuthoredZ), authoredOffset)
                    nextAuthoredZ++
                }
                is EditOp.Group -> {
                    skipped += "group (Phase 8)"
                    continue
                }
            }
        }

        // Convert `working` into a modified list. Skip entries whose final
        // state is byte-identical to the original (a no-op the model didn't
        // need to emit).
        val modified = ArrayList<Pair<NoteItem, NoteItem>>()
        for ((sid, after) in working) {
            val before = originals[sid] ?: byShortId[sid] ?: continue
            if (before == after) continue
            // Don't pair an item that was also marked for deletion — the
            // `removed` collection wins.
            if (before.id in toRemove) continue
            modified += before to after
        }
        // toAdd items that share an id with anything being removed at the
        // same time keep the new id - shape replacements use a fresh UUID.
        return Simulation(
            added = toAdd,
            removed = currentItems.filter { it.id in toRemove },
            modified = modified,
            skipped = skipped,
        )
    }

    // ---- per-kind transforms ----

    private fun transformItem(item: NoteItem, matrix: FloatArray): NoteItem? = when (item.kind) {
        NoteItem.KIND_STROKE -> {
            val samples = StrokeCodec.decode(item.payload)
            if (samples.isEmpty()) item
            else item.copy(payload = StrokeCodec.encode(StrokeTransform.applyToSamples(matrix, samples)))
        }
        Shape.KIND -> {
            val decoded = ShapeCodec.decode(item.payload)
            val transformed = ShapeCodec.transform(decoded.shape, matrix)
            item.copy(payload = ShapeCodec.encode(transformed, decoded.fillArgb, decoded.strokeStyle, decoded.gradient))
        }
        TextItemCodec.KIND -> {
            val decoded = TextItemCodec.decode(item.payload)
            val next = TextItemCodec.withMatrix(decoded, StrokeTransform.multiply(matrix, decoded.matrix))
            item.copy(payload = TextItemCodec.encode(next))
        }
        NoteItem.KIND_IMAGE -> {
            val decoded = ImageItemCodec.decode(item.payload)
            item.copy(payload = ImageItemCodec.encode(ImageItemCodec.transform(decoded, matrix)))
        }
        // Phase 11/12 — stickies, connectors, and paths route through the
        // same shared transform as the undo action so AI edits behave
        // identically.
        com.aichat.sandbox.ui.components.notes.StickyCodec.KIND,
        com.aichat.sandbox.ui.components.notes.ConnectorCodec.KIND,
        com.aichat.sandbox.ui.components.notes.PathCodec.KIND ->
            com.aichat.sandbox.ui.components.notes.ItemTransformer.transform(item, matrix)
        else -> null
    }

    /**
     * Chaikin smoothing on a stroke. `iterations` typically 1..4. Pressure
     * and tilt are linearly interpolated alongside x/y so the resampled
     * stroke keeps its dynamics.
     */
    internal fun smoothStroke(item: NoteItem, iterations: Int): NoteItem? {
        if (item.kind != NoteItem.KIND_STROKE) return null
        val stride = StrokeCodec.FLOATS_PER_SAMPLE
        var samples = StrokeCodec.decode(item.payload)
        if (samples.size / stride < 3) return item
        repeat(iterations) {
            samples = chaikin(samples, stride)
            if (samples.size / stride >= 1024) return@repeat
        }
        return item.copy(payload = StrokeCodec.encode(samples))
    }

    /**
     * Ramer–Douglas–Peucker stroke simplification. `tolerance` in world
     * units — larger tolerance = fewer points.
     */
    internal fun simplifyStroke(item: NoteItem, tolerance: Float): NoteItem? {
        if (item.kind != NoteItem.KIND_STROKE) return null
        val stride = StrokeCodec.FLOATS_PER_SAMPLE
        val samples = StrokeCodec.decode(item.payload)
        val count = samples.size / stride
        if (count < 3 || tolerance <= 0f) return item
        // Delegate to the shared RDP (Phase 4) so notes and the notes→vector
        // bridge simplify with one numerically-identical implementation.
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

    private fun chaikin(samples: FloatArray, stride: Int): FloatArray {
        val count = samples.size / stride
        if (count < 2) return samples
        // Each interior segment produces two new points; endpoints kept.
        val out = FloatArray((2 * (count - 1)) * stride)
        var write = 0
        for (i in 0 until count - 1) {
            val a = i * stride
            val b = (i + 1) * stride
            for (k in 0 until stride) {
                out[write + k] = samples[a + k] * 0.75f + samples[b + k] * 0.25f
                out[write + stride + k] = samples[a + k] * 0.25f + samples[b + k] * 0.75f
            }
            write += 2 * stride
        }
        // Preserve original endpoints so the stroke doesn't shrink.
        for (k in 0 until stride) {
            out[k] = samples[k]
            out[out.size - stride + k] = samples[samples.size - stride + k]
        }
        return out
    }

    /**
     * 17.5 — fold style-compatible path [sources] into one multi-subpath
     * path item. Items must share the carrying-item style (colour + width)
     * and a compatible payload (fill / stroke styling); otherwise the merge
     * would silently restyle geometry, so we return null and the caller
     * skips. The merged item takes the bottom-most (lowest-zIndex) source's
     * z / layer / group and a fresh id.
     */
    internal fun mergePathItems(sources: List<NoteItem>): NoteItem? {
        if (sources.size < 2) return null
        val ordered = sources.sortedBy { it.zIndex }
        val base = ordered.first()
        if (ordered.any { it.colorArgb != base.colorArgb || it.baseWidthPx != base.baseWidthPx }) {
            return null
        }
        val merged = PathMerge.merge(ordered.map { PathCodec.decode(it.payload) }) ?: return null
        return base.copy(
            id = java.util.UUID.randomUUID().toString(),
            payload = PathCodec.encode(merged),
        )
    }

    /**
     * 17.5 #1 — build a new `kind=path` item from an [EditOp.AddPath]. Returns
     * null when no subpath carries any anchors (the parser already guards, but
     * defense in depth). Colour / width default to a black 2px outline so a
     * model that omits them still produces a visible icon part.
     */
    internal fun buildAddedPath(op: EditOp.AddPath, noteId: String, zIndex: Int): NoteItem? {
        val subpaths = op.subpaths
            .map { sub ->
                PathCodec.Subpath(
                    anchors = sub.anchors.map { a ->
                        PathCodec.Anchor(a.x, a.y, a.inDx, a.inDy, a.outDx, a.outDy)
                    },
                    closed = sub.closed,
                )
            }
            .filter { it.anchors.isNotEmpty() }
        if (subpaths.isEmpty()) return null
        val payload = PathCodec.PathPayload(
            subpaths = subpaths,
            fillRule = if (op.evenOdd) PathCodec.FILL_RULE_EVEN_ODD else PathCodec.FILL_RULE_NON_ZERO,
            fillArgb = op.fillArgb ?: 0,
        )
        return NoteItem(
            id = java.util.UUID.randomUUID().toString(),
            noteId = noteId,
            zIndex = zIndex,
            kind = PathCodec.KIND,
            tool = null,
            colorArgb = op.colorArgb ?: DEFAULT_AUTHORED_COLOR,
            baseWidthPx = op.width ?: DEFAULT_AUTHORED_WIDTH,
            payload = PathCodec.encode(payload),
        )
    }

    /** 17.5 #1 — build a new `kind=shape` item from an [EditOp.AddShape]. */
    internal fun buildAddedShape(op: EditOp.AddShape, noteId: String, zIndex: Int): NoteItem {
        val shape: Shape = when (val spec = op.shape) {
            is EditOp.ShapeSpec.Line -> Shape.Line(spec.x0, spec.y0, spec.x1, spec.y1)
            is EditOp.ShapeSpec.Rect -> Shape.Rect(spec.x0, spec.y0, spec.x1, spec.y1, spec.r)
            is EditOp.ShapeSpec.Ellipse -> Shape.Ellipse(spec.cx, spec.cy, spec.rx, spec.ry, spec.rotation)
            is EditOp.ShapeSpec.Arrow -> Shape.Arrow(spec.x0, spec.y0, spec.x1, spec.y1, spec.head)
            is EditOp.ShapeSpec.Polygon -> Shape.Polygon(spec.points.copyOf(), spec.closed)
        }
        return NoteItem(
            id = java.util.UUID.randomUUID().toString(),
            noteId = noteId,
            zIndex = zIndex,
            kind = Shape.KIND,
            tool = null,
            colorArgb = op.colorArgb ?: DEFAULT_AUTHORED_COLOR,
            baseWidthPx = op.width ?: DEFAULT_AUTHORED_WIDTH,
            payload = ShapeCodec.encode(shape, fillArgb = op.fillArgb ?: 0),
        )
    }

    /**
     * 17.5 #2 — translate an authored item by [offset] (`[dx, dy]`) so a
     * refine result lands beside the original sketch. No-op for a null / zero
     * offset; reuses the per-kind [transformItem] so path / shape geometry is
     * shifted exactly as a user drag would.
     */
    private fun offsetAuthored(item: NoteItem, offset: FloatArray?): NoteItem {
        if (offset == null || offset.size < 2 || (offset[0] == 0f && offset[1] == 0f)) return item
        val m = StrokeTransform.translation(offset[0], offset[1])
        return transformItem(item, m) ?: item
    }

    /** Defaults for model-authored geometry (17.5 #1). */
    private const val DEFAULT_AUTHORED_COLOR: Int = 0xFF000000.toInt()
    private const val DEFAULT_AUTHORED_WIDTH: Float = 2f

    private fun buildShapeReplacement(source: NoteItem, spec: EditOp.ShapeSpec): NoteItem {
        val shape: Shape = when (spec) {
            is EditOp.ShapeSpec.Line -> Shape.Line(spec.x0, spec.y0, spec.x1, spec.y1)
            is EditOp.ShapeSpec.Rect -> Shape.Rect(spec.x0, spec.y0, spec.x1, spec.y1, spec.r)
            is EditOp.ShapeSpec.Ellipse -> Shape.Ellipse(spec.cx, spec.cy, spec.rx, spec.ry, spec.rotation)
            is EditOp.ShapeSpec.Arrow -> Shape.Arrow(spec.x0, spec.y0, spec.x1, spec.y1, spec.head)
            is EditOp.ShapeSpec.Polygon -> Shape.Polygon(spec.points.copyOf(), spec.closed)
        }
        return NoteItem(
            id = java.util.UUID.randomUUID().toString(),
            noteId = source.noteId,
            zIndex = source.zIndex,
            kind = Shape.KIND,
            tool = null,
            colorArgb = source.colorArgb,
            baseWidthPx = source.baseWidthPx,
            payload = ShapeCodec.encode(shape, fillArgb = 0),
            layerId = source.layerId,
        )
    }
}

/**
 * Convenience adapter: build a [EditorAction.CompositeEdit] from a
 * simulation result. The simulation result is what the preview overlay
 * renders; calling this on user accept produces the undo entry that gets
 * pushed onto the stack.
 */
fun EditPreviewController.Simulation.toCompositeEdit(description: String): EditorAction.CompositeEdit =
    EditorAction.CompositeEdit(
        description = description,
        added = added,
        removed = removed,
        modified = modified,
    )
