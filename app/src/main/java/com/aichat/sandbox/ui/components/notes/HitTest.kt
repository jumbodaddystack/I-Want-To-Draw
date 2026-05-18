package com.aichat.sandbox.ui.components.notes

import com.aichat.sandbox.ui.screens.notes.LassoController
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure hit-test helpers for stroke geometry (sub-phase 1.6).
 *
 * Operates directly on the packed `[x, y, p, t, …]` sample buffers used by
 * [StrokeCodec] so it can be unit-tested on the JVM without an Android
 * runtime. Callers are expected to decode payloads once and reuse the
 * resulting [FloatArray] for repeated queries (e.g. during an eraser swipe).
 */
object HitTest {

    /**
     * 4-element axis-aligned bounding box: `[minX, minY, maxX, maxY]`.
     * Returns `null` for empty sample sets.
     */
    fun boundsOf(samples: FloatArray, sampleCount: Int): FloatArray? {
        if (sampleCount < 1) return null
        val s = StrokeCodec.FLOATS_PER_SAMPLE
        var minX = samples[0]
        var minY = samples[1]
        var maxX = minX
        var maxY = minY
        for (i in 1 until sampleCount) {
            val base = i * s
            val x = samples[base]
            val y = samples[base + 1]
            if (x < minX) minX = x else if (x > maxX) maxX = x
            if (y < minY) minY = y else if (y > maxY) maxY = y
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    /**
     * Cheap pre-filter: does the eraser tip at (`px`,`py`) with `radius` overlap
     * the stroke's bounding box (expanded by the same radius)?
     */
    fun bboxContainsPoint(bounds: FloatArray, px: Float, py: Float, radius: Float): Boolean {
        return px >= bounds[0] - radius && px <= bounds[2] + radius &&
            py >= bounds[1] - radius && py <= bounds[3] + radius
    }

    /**
     * True if (`px`, `py`) is within `radius` of any segment of the stroke.
     *
     * Single-sample strokes are tested as points. Early-outs at the first
     * matching segment.
     */
    fun pointWithinStroke(
        samples: FloatArray,
        sampleCount: Int,
        px: Float,
        py: Float,
        radius: Float,
    ): Boolean {
        if (sampleCount < 1) return false
        val s = StrokeCodec.FLOATS_PER_SAMPLE
        val r2 = radius * radius
        if (sampleCount == 1) {
            val dx = samples[0] - px
            val dy = samples[1] - py
            return dx * dx + dy * dy <= r2
        }
        for (i in 1 until sampleCount) {
            val a = (i - 1) * s
            val b = i * s
            if (pointToSegmentDistanceSquared(
                    px, py,
                    samples[a], samples[a + 1],
                    samples[b], samples[b + 1],
                ) <= r2
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Phase 6.2 — point-in-shape for the eraser and tap-pick paths.
     *
     * Lines / arrows / unclosed polygons are tested as segments with [radius]
     * tolerance. Rectangles and closed polygons hit-test interior + edge.
     * Ellipses use the canonical `((x-cx)/rx)^2 + ((y-cy)/ry)^2 <= 1` form
     * after rotating the query point into the ellipse's local frame.
     */
    fun shapeContainsPoint(shape: Shape, px: Float, py: Float, radius: Float): Boolean {
        val r2 = radius * radius
        return when (shape) {
            is Shape.Line ->
                pointToSegmentDistanceSquared(px, py, shape.x0, shape.y0, shape.x1, shape.y1) <= r2
            is Shape.Rect -> {
                val inside = px >= shape.minX - radius && px <= shape.maxX + radius &&
                    py >= shape.minY - radius && py <= shape.maxY + radius
                if (!inside) return false
                // Edge proximity check so a small radius near the outline still
                // catches the stroke-eraser even when the rect isn't filled.
                val dxLeft = abs(px - shape.minX); val dxRight = abs(px - shape.maxX)
                val dyTop = abs(py - shape.minY); val dyBot = abs(py - shape.maxY)
                val insideStrict = px > shape.minX && px < shape.maxX &&
                    py > shape.minY && py < shape.maxY
                insideStrict || dxLeft <= radius || dxRight <= radius ||
                    dyTop <= radius || dyBot <= radius
            }
            is Shape.Ellipse -> {
                val cos = cos(-shape.rotationRad)
                val sin = sin(-shape.rotationRad)
                val dx = px - shape.cx
                val dy = py - shape.cy
                val lx = cos * dx - sin * dy
                val ly = sin * dx + cos * dy
                val rx = shape.rx.coerceAtLeast(1e-3f)
                val ry = shape.ry.coerceAtLeast(1e-3f)
                val v = (lx * lx) / (rx * rx) + (ly * ly) / (ry * ry)
                v <= 1f + radius / max(rx, ry)
            }
            is Shape.Arrow ->
                pointToSegmentDistanceSquared(px, py, shape.x0, shape.y0, shape.x1, shape.y1) <= r2
            is Shape.Polygon -> {
                val n = shape.points.size / 2
                if (n < 2) return false
                if (shape.closed && LassoController.polygonContainsPoint(shape.points, n, px, py)) return true
                // Test segment-by-segment for edge proximity.
                var i = 1
                while (i < n) {
                    val ax = shape.points[(i - 1) * 2]; val ay = shape.points[(i - 1) * 2 + 1]
                    val bx = shape.points[i * 2]; val by = shape.points[i * 2 + 1]
                    if (pointToSegmentDistanceSquared(px, py, ax, ay, bx, by) <= r2) return true
                    i++
                }
                if (shape.closed && n >= 3) {
                    val ax = shape.points[(n - 1) * 2]; val ay = shape.points[(n - 1) * 2 + 1]
                    val bx = shape.points[0]; val by = shape.points[1]
                    if (pointToSegmentDistanceSquared(px, py, ax, ay, bx, by) <= r2) return true
                }
                false
            }
        }
    }

    /**
     * Phase 6.2 — lasso intersection for shapes. A shape is selected when any
     * of its representative points falls inside the polygon, mirroring the
     * stroke contract in [LassoController.strokeIntersectsPolygon].
     */
    fun shapeIntersectsPolygon(
        shape: Shape,
        polygon: FloatArray,
        vertexCount: Int,
        polygonBounds: FloatArray,
    ): Boolean {
        val sb = com.aichat.sandbox.ui.components.notes.ShapeCodec.boundsOf(shape) ?: return false
        if (!LassoController.boundsOverlap(sb, polygonBounds)) return false
        val sample: FloatArray = when (shape) {
            is Shape.Line -> floatArrayOf(
                shape.x0, shape.y0,
                (shape.x0 + shape.x1) * 0.5f, (shape.y0 + shape.y1) * 0.5f,
                shape.x1, shape.y1,
            )
            is Shape.Rect -> floatArrayOf(
                shape.minX, shape.minY, shape.maxX, shape.minY,
                shape.maxX, shape.maxY, shape.minX, shape.maxY,
                (shape.minX + shape.maxX) * 0.5f, (shape.minY + shape.maxY) * 0.5f,
            )
            is Shape.Ellipse -> floatArrayOf(
                shape.cx, shape.cy,
                shape.cx - shape.rx, shape.cy, shape.cx + shape.rx, shape.cy,
                shape.cx, shape.cy - shape.ry, shape.cx, shape.cy + shape.ry,
            )
            is Shape.Arrow -> floatArrayOf(
                shape.x0, shape.y0,
                (shape.x0 + shape.x1) * 0.5f, (shape.y0 + shape.y1) * 0.5f,
                shape.x1, shape.y1,
            )
            is Shape.Polygon -> shape.points
        }
        var i = 0
        while (i < sample.size) {
            val px = sample[i]
            val py = sample[i + 1]
            if (LassoController.polygonContainsPoint(polygon, vertexCount, px, py)) return true
            i += 2
        }
        return false
    }

    /** Squared distance from (`px`,`py`) to the closest point on segment `a→b`. */
    private fun pointToSegmentDistanceSquared(
        px: Float, py: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float,
    ): Float {
        val abx = bx - ax
        val aby = by - ay
        val lenSq = abx * abx + aby * aby
        if (lenSq == 0f) {
            val dx = px - ax
            val dy = py - ay
            return dx * dx + dy * dy
        }
        // Project (px,py) onto AB, clamped to [0,1].
        val t = ((px - ax) * abx + (py - ay) * aby) / lenSq
        val tc = min(1f, max(0f, t))
        val cx = ax + tc * abx
        val cy = ay + tc * aby
        val dx = px - cx
        val dy = py - cy
        return dx * dx + dy * dy
    }
}
