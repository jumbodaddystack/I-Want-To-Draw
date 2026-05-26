package com.aichat.sandbox.data.vector

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Ramer–Douglas–Peucker polyline simplification plus small geometry helpers.
 *
 * Pure and deterministic: the same input always yields the same output, with no
 * Android dependencies. Used by [VectorDrawableOptimizer] to drop redundant
 * interior points from sampled paths while always keeping the first and last
 * vertex so a path never shrinks or disappears.
 */
object VectorPathSimplifier {

    /**
     * Returns [points] with interior vertices that lie within [tolerance] of the
     * line between their kept neighbors removed. The first and last point are
     * always preserved. Returns the input unchanged when there are fewer than 3
     * points or [tolerance] is non-positive.
     */
    fun simplify(points: List<VectorPoint>, tolerance: Float): List<VectorPoint> {
        if (points.size < 3) return points
        if (tolerance <= 0f) return points

        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        rdp(points, 0, points.size - 1, tolerance, keep)

        val out = ArrayList<VectorPoint>(points.size)
        for (i in points.indices) {
            if (keep[i]) out += points[i]
        }
        return out
    }

    private fun rdp(
        points: List<VectorPoint>,
        start: Int,
        end: Int,
        tolerance: Float,
        keep: BooleanArray,
    ) {
        if (end <= start + 1) return
        val a = points[start]
        val b = points[end]
        var maxDist = -1f
        var maxIdx = -1
        for (i in start + 1 until end) {
            val d = perpendicularDistance(points[i], a, b)
            if (d > maxDist) {
                maxDist = d
                maxIdx = i
            }
        }
        if (maxIdx >= 0 && maxDist > tolerance) {
            keep[maxIdx] = true
            rdp(points, start, maxIdx, tolerance, keep)
            rdp(points, maxIdx, end, tolerance, keep)
        }
    }

    /** Perpendicular distance from [p] to the (infinite) line through [a] and [b]. */
    private fun perpendicularDistance(p: VectorPoint, a: VectorPoint, b: VectorPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-12f) {
            return hypot(p.x - a.x, p.y - a.y)
        }
        val cross = dy * p.x - dx * p.y + b.x * a.y - b.y * a.x
        return abs(cross) / sqrt(lenSq)
    }

    /** Drops points that coincide with their predecessor within [epsilon]. */
    fun removeConsecutiveDuplicates(
        points: List<VectorPoint>,
        epsilon: Float = 0.0001f,
    ): List<VectorPoint> {
        if (points.size < 2) return points
        val out = ArrayList<VectorPoint>(points.size)
        out += points[0]
        for (i in 1 until points.size) {
            val prev = out[out.size - 1]
            val cur = points[i]
            if (hypot(cur.x - prev.x, cur.y - prev.y) > epsilon) {
                out += cur
            }
        }
        return out
    }

    /** Summed segment length of [points]; the closing edge is added when [closed]. */
    fun pathLength(points: List<VectorPoint>, closed: Boolean = false): Float {
        if (points.size < 2) return 0f
        var total = 0f
        for (i in 1 until points.size) {
            total += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        }
        if (closed) {
            val first = points[0]
            val last = points[points.size - 1]
            total += hypot(first.x - last.x, first.y - last.y)
        }
        return total
    }
}

/**
 * Rebuilds a simplified point list into compact `android:pathData`.
 *
 * Emits a single `M` followed by `L` segments (`M x0,y0L x1,y1...`), appending
 * `Z` for closed paths. Floats are rounded to [decimalPlaces], never written in
 * scientific notation, and have trailing zeros trimmed.
 */
object SimplifiedPathBuilder {

    fun buildPolylinePath(
        points: List<VectorPoint>,
        closed: Boolean,
        decimalPlaces: Int,
    ): String {
        if (points.isEmpty()) return ""
        val sb = StringBuilder(points.size * 8)
        sb.append('M')
            .append(formatFloat(points[0].x, decimalPlaces))
            .append(',')
            .append(formatFloat(points[0].y, decimalPlaces))
        for (i in 1 until points.size) {
            sb.append('L')
                .append(formatFloat(points[i].x, decimalPlaces))
                .append(',')
                .append(formatFloat(points[i].y, decimalPlaces))
        }
        if (closed) sb.append('Z')
        return sb.toString()
    }

    /** Deterministic float formatting honoring [decimalPlaces], no exponent form. */
    internal fun formatFloat(value: Float, decimalPlaces: Int): String {
        if (value.isNaN() || value.isInfinite()) return "0"
        val dp = decimalPlaces.coerceIn(0, 8)
        var s = BigDecimal(value.toDouble())
            .setScale(dp, RoundingMode.HALF_UP)
            .toPlainString()
        if (s.contains('.')) {
            s = s.trimEnd('0').trimEnd('.')
        }
        if (s == "-0") s = "0"
        return s
    }
}
