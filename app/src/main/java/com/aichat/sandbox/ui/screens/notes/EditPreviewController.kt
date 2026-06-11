package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import com.aichat.sandbox.data.notes.EditOp
import com.aichat.sandbox.data.notes.EditOpsDoc
import com.aichat.sandbox.data.vector.VectorPoint
import com.aichat.sandbox.data.vector.notesbridge.PolylineSimplify
import com.aichat.sandbox.ui.components.notes.ImageItemCodec
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
            item.copy(payload = ShapeCodec.encode(transformed, decoded.fillArgb, decoded.strokeStyle))
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
        // Phase 11 — stickies and connectors route through the same shared
        // transform as the undo action so AI edits behave identically.
        com.aichat.sandbox.ui.components.notes.StickyCodec.KIND,
        com.aichat.sandbox.ui.components.notes.ConnectorCodec.KIND ->
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
