package com.aichat.sandbox.data.vector.notesbridge

import com.aichat.sandbox.data.vector.VectorPoint
import com.aichat.sandbox.ui.components.notes.Shape
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Phase 4 — pure geometric "is this freehand polyline actually a line / rect /
 * circle?" detector.
 *
 * Lifts the auto-shape intent that previously only existed implicitly behind the
 * notes model's [com.aichat.sandbox.data.notes.EditOp.ReplaceWithShape] into a
 * shared, testable predicate so both the bridge (vectorizing ink) and a future
 * "clean up" button call the same code. Returns a [Shape] when a simplified
 * polyline matches a primitive within conservative thresholds, else `null` — a
 * deliberate squiggle is left to curve-fitting.
 *
 * Thresholds are intentionally strict: over-fitting (snapping a meaningful
 * squiggle to a circle) is worse than under-fitting, so a candidate must clear a
 * comfortable margin on every test. All math is in viewport units.
 */
object AutoShapeFitter {

    /** Max chord-relative deviation for a polyline to read as a straight line. */
    private const val LINE_REL_TOL = 0.06f

    /** Max bbox-relative spread of ellipse radius for a closed loop to read circular. */
    private const val CIRCLE_REL_TOL = 0.16f

    /** Max bbox-relative distance to the nearest box edge for a loop to read rectangular. */
    private const val RECT_REL_TOL = 0.10f

    /** Closed-ness: first/last within this fraction of the bbox diagonal. */
    private const val CLOSE_REL_TOL = 0.22f

    /** Best-effort fit, or null when nothing matches confidently. */
    fun fit(points: List<VectorPoint>): Shape? {
        if (points.size < 2) return null
        line(points)?.let { return it }
        // Curved primitives need enough samples to be meaningful.
        if (points.size < 6) return null
        circle(points)?.let { return it }
        rect(points)?.let { return it }
        return null
    }

    // ---- line ----

    private fun line(points: List<VectorPoint>): Shape.Line? {
        val a = points.first()
        val b = points.last()
        val length = hypot(b.x - a.x, b.y - a.y)
        if (length < 1e-3f) return null // a closed/zero-extent stroke isn't a line
        var maxDev = 0f
        for (i in 1 until points.size - 1) {
            val d = PolylineSimplify.perpDistance(points[i], a, b)
            if (d > maxDev) maxDev = d
        }
        return if (maxDev <= LINE_REL_TOL * length) Shape.Line(a.x, a.y, b.x, b.y) else null
    }

    // ---- circle / ellipse ----

    private fun circle(points: List<VectorPoint>): Shape.Ellipse? {
        val box = bounds(points) ?: return null
        val (minX, minY, maxX, maxY) = box
        val rx = (maxX - minX) / 2f
        val ry = (maxY - minY) / 2f
        if (rx < 1e-3f || ry < 1e-3f) return null
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        if (!isClosed(points, box)) return null
        // Every sample should sit on the unit ellipse: (dx/rx)^2 + (dy/ry)^2 ≈ 1.
        var maxErr = 0f
        for (p in points) {
            val nx = (p.x - cx) / rx
            val ny = (p.y - cy) / ry
            val r = hypot(nx, ny)
            val err = abs(r - 1f)
            if (err > maxErr) maxErr = err
        }
        return if (maxErr <= CIRCLE_REL_TOL) Shape.Ellipse(cx, cy, rx, ry) else null
    }

    // ---- rectangle ----

    private fun rect(points: List<VectorPoint>): Shape.Rect? {
        val box = bounds(points) ?: return null
        val (minX, minY, maxX, maxY) = box
        val w = maxX - minX
        val h = maxY - minY
        if (w < 1e-3f || h < 1e-3f) return null
        if (!isClosed(points, box)) return null
        val diag = hypot(w, h)
        // Every sample must hug one of the four bounding-box edges...
        for (p in points) {
            val dEdge = min(min(p.x - minX, maxX - p.x), min(p.y - minY, maxY - p.y))
            if (dEdge > RECT_REL_TOL * diag) return null
        }
        // ...and the stroke must actually reach into all four corners (else it's
        // an L / U shape, not a rectangle).
        if (!visitsAllCorners(points, box)) return null
        return Shape.Rect(minX, minY, maxX, maxY)
    }

    private fun visitsAllCorners(points: List<VectorPoint>, box: Bounds): Boolean {
        val (minX, minY, maxX, maxY) = box
        val w = maxX - minX
        val h = maxY - minY
        val near = 0.25f // within 25% of the box size of a corner
        val tlx = near * w; val tly = near * h
        var tl = false; var tr = false; var bl = false; var br = false
        for (p in points) {
            if (p.x - minX <= tlx && p.y - minY <= tly) tl = true
            if (maxX - p.x <= tlx && p.y - minY <= tly) tr = true
            if (p.x - minX <= tlx && maxY - p.y <= tly) bl = true
            if (maxX - p.x <= tlx && maxY - p.y <= tly) br = true
        }
        return tl && tr && bl && br
    }

    // ---- helpers ----

    private data class Bounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

    private operator fun Bounds.component1() = minX
    private operator fun Bounds.component2() = minY
    private operator fun Bounds.component3() = maxX
    private operator fun Bounds.component4() = maxY

    private fun bounds(points: List<VectorPoint>): Bounds? {
        if (points.isEmpty()) return null
        var minX = points[0].x; var minY = points[0].y
        var maxX = minX; var maxY = minY
        for (p in points) {
            minX = min(minX, p.x); minY = min(minY, p.y)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y)
        }
        return Bounds(minX, minY, maxX, maxY)
    }

    private fun isClosed(points: List<VectorPoint>, box: Bounds): Boolean {
        val diag = hypot(box.maxX - box.minX, box.maxY - box.minY)
        if (diag < 1e-3f) return false
        val a = points.first()
        val b = points.last()
        return hypot(b.x - a.x, b.y - a.y) <= CLOSE_REL_TOL * diag
    }
}
