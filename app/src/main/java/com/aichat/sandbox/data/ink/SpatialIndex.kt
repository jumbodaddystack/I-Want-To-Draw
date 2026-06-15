package com.aichat.sandbox.data.ink

import kotlin.math.floor

/**
 * Phase **I6 — mesh-backed geometry adoption**: the bounding-box / spatial
 * prefilter the migration plan calls out as a prerequisite for mesh-backed
 * selection and snapping (see `docs/ANDROIDX_INK_MIGRATION_PLAN.md`, capability
 * area B + the N2 "Prerequisite" note + the "Geometry cache correctness" risk).
 *
 * A uniform-grid index over axis-aligned bounding boxes keyed by note item id.
 * On a large note, mesh intersection / coverage queries are expensive (each is a
 * native call against a `PartitionedMesh`), so callers cull the candidate set
 * down to the strokes whose cheap AABB overlaps the query region **before**
 * touching any mesh. This is the cheap front half of [StrokeMeshCache]; it never
 * builds or references an ink mesh, so it is fully pure-JVM testable and carries
 * no ink dependency.
 *
 * Determinism matters for the "deterministic mapping from mesh hits back to note
 * item IDs" requirement: ids are stored in insertion order and every query
 * returns its hits in that same stable order, so a region query produces the
 * same id list run to run regardless of grid-cell hashing.
 */
class SpatialIndex(private val cellSize: Float = DEFAULT_CELL_SIZE) {

    /** Insertion-ordered id → `[minX, minY, maxX, maxY]`. */
    private val boundsById = LinkedHashMap<String, FloatArray>()

    /** Packed cell key → ids whose AABB touches that cell. */
    private val grid = HashMap<Long, MutableSet<String>>()

    val size: Int get() = boundsById.size

    /**
     * Insert or replace [id] with axis-aligned [bounds] (`[minX,minY,maxX,maxY]`).
     * Re-inserting an existing id first removes its old cell membership, so the
     * index stays consistent when a stroke is transformed and its AABB moves.
     */
    fun insert(id: String, bounds: FloatArray) {
        remove(id)
        val copy = bounds.copyOf()
        boundsById[id] = copy
        forEachCell(copy) { key ->
            grid.getOrPut(key) { LinkedHashSet() }.add(id)
        }
    }

    /** Remove [id] from the index (no-op if absent). */
    fun remove(id: String) {
        val old = boundsById.remove(id) ?: return
        forEachCell(old) { key ->
            val bucket = grid[key] ?: return@forEachCell
            bucket.remove(id)
            if (bucket.isEmpty()) grid.remove(key)
        }
    }

    /** Drop every id not in [ids]; mirrors `decodedCache.keys.retainAll(...)`. */
    fun retain(ids: Set<String>) {
        val drop = boundsById.keys.filterNot { it in ids }
        drop.forEach { remove(it) }
    }

    fun clear() {
        boundsById.clear()
        grid.clear()
    }

    fun boundsFor(id: String): FloatArray? = boundsById[id]

    /**
     * Ids whose AABB overlaps the query box, in stable insertion order. Two-stage:
     * gather the grid buckets the box touches (cheap), then confirm a true AABB
     * overlap (the grid is conservative — an id lands in a cell its AABB merely
     * touches). Iterating [boundsById] for the final pass guarantees the result
     * order is deterministic.
     */
    fun query(minX: Float, minY: Float, maxX: Float, maxY: Float): List<String> {
        if (boundsById.isEmpty()) return emptyList()
        val candidates = HashSet<String>()
        forEachCellRange(minX, minY, maxX, maxY) { key ->
            grid[key]?.let { candidates.addAll(it) }
        }
        if (candidates.isEmpty()) return emptyList()
        val out = ArrayList<String>(candidates.size)
        for ((id, b) in boundsById) {
            if (id in candidates && overlaps(b, minX, minY, maxX, maxY)) out.add(id)
        }
        return out
    }

    /** Convenience: candidates near a point within [radius]. */
    fun queryPoint(px: Float, py: Float, radius: Float): List<String> =
        query(px - radius, py - radius, px + radius, py + radius)

    private inline fun forEachCell(bounds: FloatArray, body: (Long) -> Unit) =
        forEachCellRange(bounds[0], bounds[1], bounds[2], bounds[3], body)

    private inline fun forEachCellRange(
        minX: Float, minY: Float, maxX: Float, maxY: Float, body: (Long) -> Unit,
    ) {
        val cxMin = cellOf(minX)
        val cxMax = cellOf(maxX)
        val cyMin = cellOf(minY)
        val cyMax = cellOf(maxY)
        var cx = cxMin
        while (cx <= cxMax) {
            var cy = cyMin
            while (cy <= cyMax) {
                body(packCell(cx, cy))
                cy++
            }
            cx++
        }
    }

    private fun cellOf(v: Float): Int = floor(v / cellSize).toInt()

    private companion object {
        /**
         * World-unit cell size. Strokes are typically tens–hundreds of px; a
         * 256-unit cell keeps the per-stroke cell count tiny while still
         * partitioning a large canvas into useful buckets.
         */
        const val DEFAULT_CELL_SIZE = 256f

        fun packCell(cx: Int, cy: Int): Long =
            (cx.toLong() and 0xFFFFFFFFL) shl 32 or (cy.toLong() and 0xFFFFFFFFL)

        fun overlaps(b: FloatArray, minX: Float, minY: Float, maxX: Float, maxY: Float): Boolean =
            b[0] <= maxX && b[2] >= minX && b[1] <= maxY && b[3] >= minY
    }
}
