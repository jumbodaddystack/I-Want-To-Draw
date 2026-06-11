package com.aichat.sandbox.ui.components.notes

import com.aichat.sandbox.data.vector.VectorPoint
import com.aichat.sandbox.data.vector.notesbridge.PolylineSimplify
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Sub-phase 11.3 — pure ink-stroke shape recognition.
 *
 * Drives the hold-to-snap gesture: the stylus held still ≥ 600 ms before
 * lift hands the committed stroke here; a hit is replaced on the canvas via
 * one `CompositeEdit("Recognized …")` so a single undo restores the raw ink.
 *
 * The pipeline is deliberately conservative — a null is always safe (the
 * ink simply stays ink), a false positive eats the user's drawing:
 *
 *  1. Gate on sample count and bbox size.
 *  2. Straightness (max perpendicular deviation from the chord) → [Shape.Line].
 *  3. Closed-loop test (start/end gap vs. bbox diagonal); open non-straight
 *     strokes return null.
 *  4. Ellipse fit: radial variance in the bbox-normalised frame.
 *  5. RDP corner extraction (shared [PolylineSimplify], tolerance scaled to
 *     the diagonal): 4 corners covering most of the bbox → axis-aligned
 *     [Shape.Rect]; 3–8 corners → closed [Shape.Polygon]; more → null.
 */
object ShapeRecognizer {

    data class Result(val shape: Shape, val label: String)

    /** Samples are packed `[x, y, p, t]` ([StrokeCodec.FLOATS_PER_SAMPLE] stride). */
    fun recognize(samples: FloatArray, sampleCount: Int): Result? {
        if (sampleCount < MIN_SAMPLES) return null
        val s = StrokeCodec.FLOATS_PER_SAMPLE
        var minX = samples[0]; var minY = samples[1]
        var maxX = minX; var maxY = minY
        var pathLen = 0f
        for (i in 1 until sampleCount) {
            val x = samples[i * s]; val y = samples[i * s + 1]
            if (x < minX) minX = x else if (x > maxX) maxX = x
            if (y < minY) minY = y else if (y > maxY) maxY = y
            pathLen += hypot(x - samples[(i - 1) * s], y - samples[(i - 1) * s + 1])
        }
        val diag = hypot(maxX - minX, maxY - minY)
        if (diag < MIN_DIAG_WORLD || pathLen < MIN_DIAG_WORLD) return null

        val x0 = samples[0]; val y0 = samples[1]
        val xn = samples[(sampleCount - 1) * s]; val yn = samples[(sampleCount - 1) * s + 1]

        // 2 — straight line: every point hugs the chord and the path doesn't
        // double back (chord ≈ path length).
        val chord = hypot(xn - x0, yn - y0)
        if (chord > 1e-3f && chord / pathLen > LINE_CHORD_RATIO) {
            var maxDev = 0f
            for (i in 0 until sampleCount) {
                val d = pointToLineDistance(
                    samples[i * s], samples[i * s + 1], x0, y0, xn, yn,
                )
                if (d > maxDev) maxDev = d
            }
            if (maxDev <= chord * LINE_DEVIATION_FRACTION) {
                return Result(Shape.Line(x0, y0, xn, yn), "line")
            }
        }

        // 3 — open and not straight: leave handwriting alone.
        val closed = hypot(xn - x0, yn - y0) <= diag * CLOSED_GAP_FRACTION
        if (!closed) return null

        // 4 — ellipse: in the frame normalised by the bbox half-extents the
        // points of a clean ellipse sit at radius ~1 from the centre.
        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f
        val rx = ((maxX - minX) * 0.5f).coerceAtLeast(1e-3f)
        val ry = ((maxY - minY) * 0.5f).coerceAtLeast(1e-3f)
        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until sampleCount) {
            val nx = (samples[i * s] - cx) / rx
            val ny = (samples[i * s + 1] - cy) / ry
            val r = sqrt((nx * nx + ny * ny).toDouble())
            sum += r
            sumSq += r * r
        }
        val mean = sum / sampleCount
        val variance = (sumSq / sampleCount - mean * mean).coerceAtLeast(0.0)
        val stdDev = sqrt(variance)
        if (abs(mean - 1.0) < ELLIPSE_MEAN_TOLERANCE && stdDev < ELLIPSE_STDDEV_TOLERANCE) {
            return Result(Shape.Ellipse(cx, cy, rx, ry), "ellipse")
        }

        // 5 — corner extraction via the shared RDP.
        val centerline = ArrayList<VectorPoint>(sampleCount)
        for (i in 0 until sampleCount) {
            centerline += VectorPoint(samples[i * s], samples[i * s + 1])
        }
        val keep = PolylineSimplify.keepMask(centerline, diag * RDP_TOLERANCE_FRACTION)
        val corners = ArrayList<VectorPoint>()
        for (i in 0 until sampleCount) if (keep[i]) corners += centerline[i]
        // Merge the duplicated closing vertex (last ≈ first).
        if (corners.size >= 2) {
            val first = corners.first()
            val last = corners.last()
            if (hypot(last.x - first.x, last.y - first.y) <= diag * CLOSED_GAP_FRACTION) {
                corners.removeAt(corners.size - 1)
            }
        }
        if (corners.size < 3 || corners.size > MAX_POLYGON_CORNERS) return null

        // 4 corners filling most of the bbox = an axis-aligned rectangle
        // (a heavily rotated rect fails the coverage test and lands as a
        // polygon, which renders it faithfully anyway).
        if (corners.size == 4) {
            val area = abs(shoelaceArea(corners))
            val bboxArea = (maxX - minX) * (maxY - minY)
            if (bboxArea > 1e-3f && area / bboxArea >= RECT_COVERAGE) {
                return Result(Shape.Rect(minX, minY, maxX, maxY), "rectangle")
            }
        }
        val pts = FloatArray(corners.size * 2)
        for (i in corners.indices) {
            pts[i * 2] = corners[i].x
            pts[i * 2 + 1] = corners[i].y
        }
        return Result(Shape.Polygon(pts, closed = true), "polygon")
    }

    private fun pointToLineDistance(
        px: Float, py: Float,
        ax: Float, ay: Float,
        bx: Float, by: Float,
    ): Float {
        val dx = bx - ax
        val dy = by - ay
        val len = hypot(dx, dy)
        if (len < 1e-6f) return hypot(px - ax, py - ay)
        return abs((px - ax) * dy - (py - ay) * dx) / len
    }

    private fun shoelaceArea(points: List<VectorPoint>): Float {
        var sum = 0f
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum * 0.5f
    }

    /** Stillness window before lift that triggers recognition (ms). */
    const val HOLD_DURATION_MS: Long = 600L

    private const val MIN_SAMPLES = 8
    private const val MIN_DIAG_WORLD = 24f
    private const val LINE_CHORD_RATIO = 0.90f
    private const val LINE_DEVIATION_FRACTION = 0.05f
    private const val CLOSED_GAP_FRACTION = 0.25f
    // Tight on purpose: a rectangle traced in the bbox-normalised frame has
    // mean radius ≈ 1.15 / stddev ≈ 0.12, so anything looser than this
    // misclassifies clean rectangles as ellipses.
    private const val ELLIPSE_MEAN_TOLERANCE = 0.10
    private const val ELLIPSE_STDDEV_TOLERANCE = 0.10
    private const val RDP_TOLERANCE_FRACTION = 0.05f
    private const val RECT_COVERAGE = 0.75f
    private const val MAX_POLYGON_CORNERS = 8
}
