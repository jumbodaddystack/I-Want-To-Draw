package com.aichat.sandbox.data.ink

import androidx.ink.brush.InputToolType
import androidx.ink.geometry.PartitionedMesh
import com.aichat.sandbox.ui.components.notes.HitTest
import com.aichat.sandbox.ui.components.notes.StrokeCodec

/**
 * Phase **I6 — mesh-backed geometry adoption**: the derived, cached per-stroke
 * [PartitionedMesh] layer the migration plan requires before mesh-backed
 * selection / erasing (capability area B, N2 "Prerequisite", and the
 * "Geometry cache correctness" open question in Risks).
 *
 * ## What it is — and isn't
 * The mesh is **derived and cached only**. `StrokeCodec` stays the canonical
 * source of truth and the AI edit-ops pipeline never sees ink (Adoption
 * principle 2); this cache is rebuilt from canonical payloads on demand and is
 * never serialized. It maps note item id → an ink mesh, lazily: registering a
 * stroke records only a **cheap centerline AABB** (no native work) so a large
 * note can be spatially pre-filtered ([candidates]) before any mesh is built,
 * and the (native, expensive) [PartitionedMesh] is materialised only when a
 * query actually needs that stroke's true geometry.
 *
 * ## Cache correctness (closing the open question)
 * `DrawingSurface.decodedCache` keys purely on item id and only evicts deleted
 * ids — which is wrong for an in-place edit (`item.copy(payload = …)` from a
 * transform / restyle / beautify keeps the id but changes the geometry). This
 * cache instead keys each slot on a **content signature** (payload bytes + tool
 * + width — the inputs that actually change the mesh; colour/opacity don't).
 * [register] with changed content bumps the signature, drops the stale mesh, and
 * re-indexes the new AABB, so transform / restyle / delete all invalidate
 * correctly. [retain] mirrors the `retainAll(keep)` lifecycle for deletes.
 *
 * Mesh construction goes through the same [InkInterop] seam the authoring path
 * commits, and is wrapped so a native failure degrades to `null` (the caller
 * then uses the point-to-segment fallback) rather than throwing.
 */
class StrokeMeshCache(cellSize: Float = DEFAULT_CELL_SIZE) {

    private class Slot(
        var payload: ByteArray,
        var tool: String,
        var width: Float,
        var signature: Long,
        var bounds: FloatArray,
        var mesh: PartitionedMesh? = null,
    )

    private val slots = LinkedHashMap<String, Slot>()
    private val index = SpatialIndex(cellSize)

    val size: Int get() = slots.size

    /**
     * Record (or refresh) the canonical geometry for stroke [id]. Cheap: decodes
     * the payload for a centerline AABB padded by [width] (a conservative
     * superset of the rendered mesh extent, so the spatial prefilter never drops
     * a true hit) and indexes it. The native mesh is **not** built here. When the
     * content signature is unchanged this is a no-op beyond the hash, so it is
     * safe to call on every item-set refresh.
     *
     * Returns `false` if the payload has no usable samples (the caller should not
     * attempt a mesh query for it).
     */
    fun register(id: String, payload: ByteArray, tool: String, width: Float): Boolean {
        val sig = signatureOf(payload, tool, width)
        val existing = slots[id]
        if (existing != null && existing.signature == sig) return existing.mesh != null || existing.bounds.isNotEmpty()

        val samples = StrokeCodec.decode(payload)
        val count = samples.size / StrokeCodec.FLOATS_PER_SAMPLE
        val centerline = HitTest.boundsOf(samples, count) ?: run {
            invalidate(id)
            return false
        }
        val pad = width.coerceAtLeast(0f)
        val bounds = floatArrayOf(
            centerline[0] - pad, centerline[1] - pad,
            centerline[2] + pad, centerline[3] + pad,
        )
        slots[id] = Slot(payload, tool, width, sig, bounds, mesh = null)
        index.insert(id, bounds)
        return true
    }

    /**
     * The derived [PartitionedMesh] for [id], built on first use and cached until
     * the slot's content signature changes. Returns `null` if the stroke was
     * never [register]ed or the native build failed (caller falls back to the
     * point-to-segment loop). The mesh's own spatial index is initialised so
     * repeated queries (e.g. an eraser swipe) are fast.
     */
    fun meshFor(id: String): PartitionedMesh? {
        val slot = slots[id] ?: return null
        slot.mesh?.let { return it }
        val built = buildMesh(slot.payload, slot.tool, slot.width) ?: return null
        slot.mesh = built
        // Refine the indexed AABB to the true mesh extent now that we have it.
        InkGeometry.boundingBox(built)?.let {
            slot.bounds = it
            index.insert(id, it)
        }
        return built
    }

    /** Cheap AABB recorded for [id] (`[minX,minY,maxX,maxY]`), or `null`. */
    fun boundsFor(id: String): FloatArray? = slots[id]?.bounds

    /**
     * Ids whose AABB overlaps the query box, in deterministic (registration)
     * order — the spatial prefilter callers run before any mesh query, and the
     * stable id ordering the "mesh hits → note item ids" mapping relies on.
     */
    fun candidates(minX: Float, minY: Float, maxX: Float, maxY: Float): List<String> =
        index.query(minX, minY, maxX, maxY)

    /** Forget a single stroke (delete, or a defensive drop on build failure). */
    fun invalidate(id: String) {
        slots.remove(id)
        index.remove(id)
    }

    /** Drop every id not in [ids]; the delete half of cache invalidation. */
    fun retain(ids: Set<String>) {
        val drop = slots.keys.filterNot { it in ids }
        drop.forEach { invalidate(it) }
    }

    fun clear() {
        slots.clear()
        index.clear()
    }

    private fun buildMesh(payload: ByteArray, tool: String, width: Float): PartitionedMesh? =
        try {
            val brush = InkInterop.brushForTool(tool, OPAQUE_BLACK, width)
            val stroke = InkInterop.toStroke(payload, brush, InputToolType.STYLUS)
            stroke.shape.also { it.initializeSpatialIndex() }
        } catch (t: Throwable) {
            null
        }

    private fun signatureOf(payload: ByteArray, tool: String, width: Float): Long {
        var h = 1125899906842597L // a large prime seed
        for (b in payload) h = 31 * h + b
        h = 31 * h + tool.hashCode()
        h = 31 * h + width.toRawBits()
        return h
    }

    private companion object {
        const val DEFAULT_CELL_SIZE = 256f

        /** Geometry is colour-independent, so mesh building uses a fixed colour. */
        const val OPAQUE_BLACK = 0xFF000000.toInt()
    }
}
