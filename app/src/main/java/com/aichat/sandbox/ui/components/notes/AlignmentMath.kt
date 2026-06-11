package com.aichat.sandbox.ui.components.notes

/**
 * Phase 10.1 — pure alignment / distribution math.
 *
 * Operates on `(id, bounds)` pairs where bounds are `[minX, minY, maxX, maxY]`
 * in world units, and returns one translation matrix (3×2 affine, layout
 * matching [StrokeTransform]) per item that needs to move. Items already in
 * place are omitted from the result so callers can skip no-op re-encodes.
 */
object AlignmentMath {

    enum class AlignEdge { LEFT, CENTER_H, RIGHT, TOP, CENTER_V, BOTTOM }

    enum class Axis { HORIZONTAL, VERTICAL }

    /** Minimum item count for [distribute] — fewer has no interior gaps to equalize. */
    const val MIN_DISTRIBUTE_COUNT = 3

    /**
     * Translation per item that aligns every entry to [edge] of the group's
     * union bounds. Returns an empty map for fewer than two entries — a
     * single item is trivially "aligned".
     */
    fun align(
        entries: List<Pair<String, FloatArray>>,
        edge: AlignEdge,
    ): Map<String, FloatArray> {
        if (entries.size < 2) return emptyMap()
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for ((_, b) in entries) {
            if (b[0] < minX) minX = b[0]
            if (b[1] < minY) minY = b[1]
            if (b[2] > maxX) maxX = b[2]
            if (b[3] > maxY) maxY = b[3]
        }
        val out = HashMap<String, FloatArray>(entries.size)
        for ((id, b) in entries) {
            val dx: Float
            val dy: Float
            when (edge) {
                AlignEdge.LEFT -> { dx = minX - b[0]; dy = 0f }
                AlignEdge.RIGHT -> { dx = maxX - b[2]; dy = 0f }
                AlignEdge.CENTER_H -> {
                    dx = (minX + maxX) * 0.5f - (b[0] + b[2]) * 0.5f; dy = 0f
                }
                AlignEdge.TOP -> { dx = 0f; dy = minY - b[1] }
                AlignEdge.BOTTOM -> { dx = 0f; dy = maxY - b[3] }
                AlignEdge.CENTER_V -> {
                    dx = 0f; dy = (minY + maxY) * 0.5f - (b[1] + b[3]) * 0.5f
                }
            }
            if (dx != 0f || dy != 0f) out[id] = StrokeTransform.translation(dx, dy)
        }
        return out
    }

    /**
     * Translation per item that equalizes the gaps between entries along
     * [axis]. The outermost two items (by leading edge) stay fixed; interior
     * items shift so every inter-item gap is identical. Requires at least
     * [MIN_DISTRIBUTE_COUNT] entries — returns an empty map otherwise.
     */
    fun distribute(
        entries: List<Pair<String, FloatArray>>,
        axis: Axis,
    ): Map<String, FloatArray> {
        if (entries.size < MIN_DISTRIBUTE_COUNT) return emptyMap()
        val lo = if (axis == Axis.HORIZONTAL) 0 else 1
        val hi = lo + 2
        val sorted = entries.sortedBy { (_, b) -> b[lo] }
        val first = sorted.first().second
        val last = sorted.last().second
        var sizes = 0f
        for ((_, b) in sorted) sizes += b[hi] - b[lo]
        val span = last[hi] - first[lo]
        val gap = (span - sizes) / (sorted.size - 1)
        val out = HashMap<String, FloatArray>(sorted.size)
        var cursor = first[lo]
        for ((id, b) in sorted) {
            val delta = cursor - b[lo]
            if (delta != 0f) {
                out[id] = if (axis == Axis.HORIZONTAL) {
                    StrokeTransform.translation(delta, 0f)
                } else {
                    StrokeTransform.translation(0f, delta)
                }
            }
            cursor += (b[hi] - b[lo]) + gap
        }
        return out
    }
}
