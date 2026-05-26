package com.aichat.sandbox.data.vector

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One straight or curved segment in an absolute-coordinate [PreviewSubpath]. All
 * coordinates are resolved into the viewport's absolute space; there are no
 * relative segments and no smooth/horizontal/vertical shorthands left — those are
 * expanded by the normalizer so the renderer can draw directly.
 */
sealed interface PreviewSegment {
    val endX: Float
    val endY: Float

    data class Line(override val endX: Float, override val endY: Float) : PreviewSegment

    data class Quad(
        val cx: Float, val cy: Float,
        override val endX: Float, override val endY: Float,
    ) : PreviewSegment

    data class Cubic(
        val c1x: Float, val c1y: Float,
        val c2x: Float, val c2y: Float,
        override val endX: Float, override val endY: Float,
    ) : PreviewSegment
}

/** A single connected run of segments starting at ([startX], [startY]). */
data class PreviewSubpath(
    val startX: Float,
    val startY: Float,
    val segments: List<PreviewSegment>,
    val closed: Boolean,
)

/**
 * Phase 8 — pure-Kotlin conversion of parsed [PathCommand]s into absolute
 * [PreviewSubpath]s for rendering.
 *
 * Responsibilities:
 *  - Resolve relative (lowercase) commands against the running pen position.
 *  - Expand `H`/`V` into lines and `S`/`T` into full cubics/quads using the
 *    reflected previous control point (falling back to the current point when the
 *    previous command was not the matching curve family).
 *  - Split on `M`/`Z` so multiple subpaths stay separate; `Z` returns the pen to
 *    the subpath start and the next drawing command begins a fresh subpath there.
 *  - Approximate `A` (elliptical arc) by sampling it into short line segments via
 *    the standard endpoint→center parameterization. This is best-effort, not
 *    pixel-perfect, and degenerate arcs fall back to a straight line. Full arc
 *    fidelity is intentionally deferred.
 *
 * The whole conversion is wrapped so it never throws; on any unexpected failure
 * it returns whatever was produced so far.
 */
object VectorPreviewPathNormalizer {

    /** Line segments used to approximate a quarter turn of an arc. */
    private const val ARC_SEGMENTS_PER_QUADRANT = 8

    fun normalize(commands: List<PathCommand>): List<PreviewSubpath> {
        val out = ArrayList<PreviewSubpath>()
        runCatching { build(commands, out) }
        return out
    }

    private fun build(commands: List<PathCommand>, out: MutableList<PreviewSubpath>) {
        var cx = 0f; var cy = 0f
        var startX = 0f; var startY = 0f
        val current = ArrayList<PreviewSegment>()
        // Last cubic/quad control point, used to reflect for S/T. Null when the
        // previous command was not the matching curve family.
        var cubicCtrlX: Float? = null; var cubicCtrlY: Float? = null
        var quadCtrlX: Float? = null; var quadCtrlY: Float? = null

        fun flush(closed: Boolean) {
            if (current.isNotEmpty()) {
                out += PreviewSubpath(startX, startY, current.toList(), closed)
                current.clear()
            }
        }

        for (cmd in commands) {
            val rel = cmd.relative
            fun ax(v: Float) = if (rel) cx + v else v
            fun ay(v: Float) = if (rel) cy + v else v

            when (cmd) {
                is PathCommand.MoveTo -> {
                    flush(false)
                    val x = ax(cmd.x); val y = ay(cmd.y)
                    startX = x; startY = y; cx = x; cy = y
                    cubicCtrlX = null; cubicCtrlY = null; quadCtrlX = null; quadCtrlY = null
                }
                is PathCommand.LineTo -> {
                    val x = ax(cmd.x); val y = ay(cmd.y)
                    current += PreviewSegment.Line(x, y); cx = x; cy = y
                    cubicCtrlX = null; cubicCtrlY = null; quadCtrlX = null; quadCtrlY = null
                }
                is PathCommand.HorizontalTo -> {
                    val x = ax(cmd.x)
                    current += PreviewSegment.Line(x, cy); cx = x
                    cubicCtrlX = null; cubicCtrlY = null; quadCtrlX = null; quadCtrlY = null
                }
                is PathCommand.VerticalTo -> {
                    val y = ay(cmd.y)
                    current += PreviewSegment.Line(cx, y); cy = y
                    cubicCtrlX = null; cubicCtrlY = null; quadCtrlX = null; quadCtrlY = null
                }
                is PathCommand.CubicTo -> {
                    val x1 = ax(cmd.x1); val y1 = ay(cmd.y1)
                    val x2 = ax(cmd.x2); val y2 = ay(cmd.y2)
                    val x = ax(cmd.x); val y = ay(cmd.y)
                    current += PreviewSegment.Cubic(x1, y1, x2, y2, x, y)
                    cubicCtrlX = x2; cubicCtrlY = y2; quadCtrlX = null; quadCtrlY = null
                    cx = x; cy = y
                }
                is PathCommand.SmoothCubicTo -> {
                    val r1x = if (cubicCtrlX != null) 2 * cx - cubicCtrlX!! else cx
                    val r1y = if (cubicCtrlY != null) 2 * cy - cubicCtrlY!! else cy
                    val x2 = ax(cmd.x2); val y2 = ay(cmd.y2)
                    val x = ax(cmd.x); val y = ay(cmd.y)
                    current += PreviewSegment.Cubic(r1x, r1y, x2, y2, x, y)
                    cubicCtrlX = x2; cubicCtrlY = y2; quadCtrlX = null; quadCtrlY = null
                    cx = x; cy = y
                }
                is PathCommand.QuadTo -> {
                    val x1 = ax(cmd.x1); val y1 = ay(cmd.y1)
                    val x = ax(cmd.x); val y = ay(cmd.y)
                    current += PreviewSegment.Quad(x1, y1, x, y)
                    quadCtrlX = x1; quadCtrlY = y1; cubicCtrlX = null; cubicCtrlY = null
                    cx = x; cy = y
                }
                is PathCommand.SmoothQuadTo -> {
                    val qx = if (quadCtrlX != null) 2 * cx - quadCtrlX!! else cx
                    val qy = if (quadCtrlY != null) 2 * cy - quadCtrlY!! else cy
                    val x = ax(cmd.x); val y = ay(cmd.y)
                    current += PreviewSegment.Quad(qx, qy, x, y)
                    quadCtrlX = qx; quadCtrlY = qy; cubicCtrlX = null; cubicCtrlY = null
                    cx = x; cy = y
                }
                is PathCommand.ArcTo -> {
                    val ex = ax(cmd.x); val ey = ay(cmd.y)
                    appendArc(current, cx, cy, cmd.rx, cmd.ry, cmd.xAxisRotation, cmd.largeArc, cmd.sweep, ex, ey)
                    cx = ex; cy = ey
                    cubicCtrlX = null; cubicCtrlY = null; quadCtrlX = null; quadCtrlY = null
                }
                is PathCommand.Close -> {
                    flush(true)
                    cx = startX; cy = startY
                    cubicCtrlX = null; cubicCtrlY = null; quadCtrlX = null; quadCtrlY = null
                }
            }
        }
        flush(false)
    }

    /**
     * Samples an SVG/Android elliptical arc from ([x0],[y0]) to ([x],[y]) into
     * line segments. Falls back to a single line for degenerate radii or
     * coincident endpoints, and never throws.
     */
    private fun appendArc(
        out: MutableList<PreviewSegment>,
        x0: Float, y0: Float,
        rxIn: Float, ryIn: Float,
        xRotDeg: Float,
        largeArc: Boolean, sweep: Boolean,
        x: Float, y: Float,
    ) {
        runCatching {
            var rx = abs(rxIn).toDouble()
            var ry = abs(ryIn).toDouble()
            if (rx < 1e-6 || ry < 1e-6 || (x0 == x && y0 == y)) {
                out += PreviewSegment.Line(x, y); return
            }
            val phi = Math.toRadians((xRotDeg.toDouble()) % 360.0)
            val cosPhi = cos(phi); val sinPhi = sin(phi)

            val dx = (x0 - x) / 2.0; val dy = (y0 - y) / 2.0
            val x1p = cosPhi * dx + sinPhi * dy
            val y1p = -sinPhi * dx + cosPhi * dy

            // Scale up radii if they are too small to span the endpoints.
            val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
            if (lambda > 1.0) {
                val s = sqrt(lambda)
                rx *= s; ry *= s
            }

            val rxSq = rx * rx; val rySq = ry * ry
            val denom = rxSq * (y1p * y1p) + rySq * (x1p * x1p)
            if (denom <= 0.0) { out += PreviewSegment.Line(x, y); return }
            var num = rxSq * rySq - denom
            if (num < 0.0) num = 0.0
            val sign = if (largeArc != sweep) 1.0 else -1.0
            val coef = sign * sqrt(num / denom)
            val cxp = coef * (rx * y1p / ry)
            val cyp = coef * -(ry * x1p / rx)

            val cx0 = cosPhi * cxp - sinPhi * cyp + (x0 + x) / 2.0
            val cy0 = sinPhi * cxp + cosPhi * cyp + (y0 + y) / 2.0

            val ux = (x1p - cxp) / rx; val uy = (y1p - cyp) / ry
            val vx = (-x1p - cxp) / rx; val vy = (-y1p - cyp) / ry

            val theta1 = angle(1.0, 0.0, ux, uy)
            var dTheta = angle(ux, uy, vx, vy)
            if (!sweep && dTheta > 0.0) dTheta -= 2 * PI
            if (sweep && dTheta < 0.0) dTheta += 2 * PI

            val segments = max(2, ceil(abs(dTheta) / (PI / 2.0) * ARC_SEGMENTS_PER_QUADRANT).toInt())
            for (i in 1..segments) {
                val t = theta1 + dTheta * i / segments
                val px = cx0 + rx * cos(t) * cosPhi - ry * sin(t) * sinPhi
                val py = cy0 + rx * cos(t) * sinPhi + ry * sin(t) * cosPhi
                out += PreviewSegment.Line(px.toFloat(), py.toFloat())
            }
        }.onFailure {
            out += PreviewSegment.Line(x, y)
        }
    }

    private fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
        val dot = ux * vx + uy * vy
        val len = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
        if (len == 0.0) return 0.0
        var ang = acos((dot / len).coerceIn(-1.0, 1.0))
        if (ux * vy - uy * vx < 0.0) ang = -ang
        return ang
    }
}
