package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.ui.components.notes.StrokeCodec

/**
 * Polygon geometry for lasso selection (sub-phase 1.8).
 *
 * The lasso path is collected in world coordinates as a flattened
 * `[x0,y0, x1,y1, …]` array. Hit-testing follows the staged plan from the
 * parent doc: bbox-overlap pre-filter, then per-sample point-in-polygon.
 * Everything here is Android-free so [LassoHitTest] can run on the JVM.
 */
object LassoController {

    /** Two floats per vertex (x, y) — the lasso path doesn't carry pressure / tilt. */
    const val FLOATS_PER_VERTEX = 2

    /**
     * Even–odd ray-cast for point-in-polygon. The polygon is treated as
     * closed (an implicit edge connects the last vertex back to the first).
     * Vertex layout matches [FLOATS_PER_VERTEX].
     */
    fun polygonContainsPoint(
        polygon: FloatArray,
        vertexCount: Int,
        px: Float,
        py: Float,
    ): Boolean {
        if (vertexCount < 3) return false
        var inside = false
        var j = vertexCount - 1
        for (i in 0 until vertexCount) {
            val xi = polygon[i * FLOATS_PER_VERTEX]
            val yi = polygon[i * FLOATS_PER_VERTEX + 1]
            val xj = polygon[j * FLOATS_PER_VERTEX]
            val yj = polygon[j * FLOATS_PER_VERTEX + 1]
            // Crossing iff the edge straddles the horizontal ray at py and
            // the intersection x sits to the right of px.
            val straddles = (yi > py) != (yj > py)
            if (straddles) {
                val xIntersect = (xj - xi) * (py - yi) / (yj - yi) + xi
                if (px < xIntersect) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Axis-aligned bbox of the polygon vertices. Returns `null` for empty
     * input. Layout: `[minX, minY, maxX, maxY]`.
     */
    fun polygonBounds(polygon: FloatArray, vertexCount: Int): FloatArray? {
        if (vertexCount < 1) return null
        var minX = polygon[0]
        var minY = polygon[1]
        var maxX = minX
        var maxY = minY
        for (i in 1 until vertexCount) {
            val x = polygon[i * FLOATS_PER_VERTEX]
            val y = polygon[i * FLOATS_PER_VERTEX + 1]
            if (x < minX) minX = x else if (x > maxX) maxX = x
            if (y < minY) minY = y else if (y > maxY) maxY = y
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    /** Cheap rectangle/rectangle overlap — both layouts are `[minX, minY, maxX, maxY]`. */
    fun boundsOverlap(a: FloatArray, b: FloatArray): Boolean {
        return a[0] <= b[2] && a[2] >= b[0] && a[1] <= b[3] && a[3] >= b[1]
    }

    /**
     * Hit-test a stroke against the lasso. Lenient: any sample falling inside
     * the polygon selects the stroke (a single dot inside a loose loop is
     * enough). Bbox overlap is checked first as an early-out.
     *
     * @param strokeSamples packed `[x, y, p, t, …]` (only x/y are read).
     * @param sampleCount   number of samples in [strokeSamples].
     * @param strokeBounds  pre-computed `[minX, minY, maxX, maxY]` of the stroke.
     */
    fun strokeIntersectsPolygon(
        strokeSamples: FloatArray,
        sampleCount: Int,
        strokeBounds: FloatArray,
        polygon: FloatArray,
        vertexCount: Int,
        polygonBounds: FloatArray,
    ): Boolean {
        if (vertexCount < 3 || sampleCount < 1) return false
        if (!boundsOverlap(strokeBounds, polygonBounds)) return false
        val s = StrokeCodec.FLOATS_PER_SAMPLE
        for (i in 0 until sampleCount) {
            val base = i * s
            if (polygonContainsPoint(polygon, vertexCount, strokeSamples[base], strokeSamples[base + 1])) {
                return true
            }
        }
        return false
    }

    /**
     * Union of `[minX, minY, maxX, maxY]` rectangles. Returns `null` if the
     * input is empty. Used to draw the selection's dashed bounding box.
     */
    fun unionBounds(rects: List<FloatArray>): FloatArray? {
        if (rects.isEmpty()) return null
        var minX = rects[0][0]
        var minY = rects[0][1]
        var maxX = rects[0][2]
        var maxY = rects[0][3]
        for (i in 1 until rects.size) {
            val r = rects[i]
            if (r[0] < minX) minX = r[0]
            if (r[1] < minY) minY = r[1]
            if (r[2] > maxX) maxX = r[2]
            if (r[3] > maxY) maxY = r[3]
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }
}
