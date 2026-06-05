package com.aichat.sandbox.data.vector.notesbridge

import com.aichat.sandbox.data.vector.VectorPoint
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Phase 4 — the single Ramer–Douglas–Peucker implementation shared by the notes
 * editor and the notes→vector bridge.
 *
 * Historically the freehand simplify lived privately inside
 * [com.aichat.sandbox.ui.screens.notes.EditPreviewController] (over packed
 * stroke samples) while the document side had its own RDP in
 * [com.aichat.sandbox.data.vector.VectorPathSimplifier] (over [VectorPoint]s).
 * To avoid the two drifting apart this object owns the *one* numeric RDP the
 * notes world uses; [EditPreviewController.simplifyStroke] delegates here so its
 * output is unchanged, and [StrokeVectorizer] simplifies the centerline with the
 * same code before curve-fitting.
 *
 * Pure and deterministic (no Android imports), exactly like the other geometry
 * cores in this package.
 */
object PolylineSimplify {

    /**
     * Returns a keep-mask the same length as [points]: `true` for vertices RDP
     * preserves at [tolerance] (world units). The first and last vertex are
     * always kept; interior vertices closer than [tolerance] to the line between
     * their kept neighbors are dropped. Fewer than 3 points keeps everything.
     */
    fun keepMask(points: List<VectorPoint>, tolerance: Float): BooleanArray {
        val keep = BooleanArray(points.size)
        if (points.isEmpty()) return keep
        keep[0] = true
        keep[points.size - 1] = true
        if (points.size < 3 || tolerance <= 0f) {
            for (i in points.indices) keep[i] = true
            return keep
        }
        rdp(points, 0, points.size - 1, tolerance, keep)
        return keep
    }

    /** Convenience: [points] filtered down to the [keepMask] survivors. */
    fun simplify(points: List<VectorPoint>, tolerance: Float): List<VectorPoint> {
        if (points.size < 3 || tolerance <= 0f) return points
        val keep = keepMask(points, tolerance)
        val out = ArrayList<VectorPoint>(points.size)
        for (i in points.indices) if (keep[i]) out += points[i]
        return out
    }

    private fun rdp(
        points: List<VectorPoint>,
        start: Int,
        end: Int,
        tolerance: Float,
        keep: BooleanArray,
    ) {
        if (end - start < 2) return
        val a = points[start]
        val b = points[end]
        var maxDist = 0f
        var maxIdx = -1
        for (i in start + 1 until end) {
            val d = perpDistance(points[i], a, b)
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

    /** Perpendicular distance from [p] to the (infinite) line through [a],[b]. */
    fun perpDistance(p: VectorPoint, a: VectorPoint, b: VectorPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-6f) {
            val rx = p.x - a.x
            val ry = p.y - a.y
            return sqrt(rx * rx + ry * ry)
        }
        val cross = dy * p.x - dx * p.y + b.x * a.y - b.y * a.x
        return abs(cross) / sqrt(lenSq)
    }
}
