package com.aichat.sandbox.data.vector

/** A 2D point in viewport coordinates. */
data class VectorPoint(
    val x: Float,
    val y: Float,
)

/**
 * A path flattened into an absolute-coordinate polyline.
 *
 * @property points Segment vertices in order. Curves are approximated by a
 *   fixed number of straight segments; this is good enough for simplification
 *   but is not an exact curve representation.
 * @property closed True when the source path ended with a `Z`/`z` close command.
 * @property sourceHadCurves True when the source contained any curve or arc
 *   command, so callers that want to preserve curves can opt out of flattening.
 */
data class SampledPath(
    val points: List<VectorPoint>,
    val closed: Boolean,
    val sourceHadCurves: Boolean,
)

/**
 * Flattens parsed [PathCommand]s into a [SampledPath] for simplification.
 *
 * Every command is resolved to absolute coordinates (relative/lowercase
 * commands are added to the running pen position). Curves (`Q T C S`) are
 * sampled into [curveSteps] straight segments; arcs (`A`) are approximated by
 * their endpoint only and flagged via [SampledPath.sourceHadCurves]. The
 * sampler is geometry-only — it does not preserve subpath boundaries, so the
 * optimizer guards multi-subpath paths against destructive simplification.
 */
object VectorPathSampler {

    fun sample(commands: List<PathCommand>, curveSteps: Int = 8): SampledPath {
        val steps = curveSteps.coerceAtLeast(1)
        val points = ArrayList<VectorPoint>(commands.size + 1)
        var cx = 0f
        var cy = 0f
        var startX = 0f
        var startY = 0f
        var closed = false
        var hadCurves = false

        // Reflected control points for smooth-curve continuation.
        var lastCubicCtrlX = 0f
        var lastCubicCtrlY = 0f
        var lastWasCubic = false
        var lastQuadCtrlX = 0f
        var lastQuadCtrlY = 0f
        var lastWasQuad = false

        fun add(x: Float, y: Float) {
            points += VectorPoint(x, y)
            cx = x
            cy = y
        }

        fun flattenQuad(x0: Float, y0: Float, qx: Float, qy: Float, x1: Float, y1: Float) {
            for (k in 1..steps) {
                val t = k.toFloat() / steps
                val mt = 1f - t
                val x = mt * mt * x0 + 2f * mt * t * qx + t * t * x1
                val y = mt * mt * y0 + 2f * mt * t * qy + t * t * y1
                add(x, y)
            }
        }

        fun flattenCubic(
            x0: Float, y0: Float,
            c1x: Float, c1y: Float,
            c2x: Float, c2y: Float,
            x1: Float, y1: Float,
        ) {
            for (k in 1..steps) {
                val t = k.toFloat() / steps
                val mt = 1f - t
                val a = mt * mt * mt
                val b = 3f * mt * mt * t
                val c = 3f * mt * t * t
                val d = t * t * t
                val x = a * x0 + b * c1x + c * c2x + d * x1
                val y = a * y0 + b * c1y + c * c2y + d * y1
                add(x, y)
            }
        }

        for (cmd in commands) {
            // Capture the pen position before this command for relative resolution.
            val px = cx
            val py = cy
            var producedCubic = false
            var producedQuad = false

            when (cmd) {
                is PathCommand.MoveTo -> {
                    val x = if (cmd.relative) px + cmd.x else cmd.x
                    val y = if (cmd.relative) py + cmd.y else cmd.y
                    add(x, y)
                    startX = x
                    startY = y
                }
                is PathCommand.LineTo -> {
                    val x = if (cmd.relative) px + cmd.x else cmd.x
                    val y = if (cmd.relative) py + cmd.y else cmd.y
                    add(x, y)
                }
                is PathCommand.HorizontalTo -> {
                    val x = if (cmd.relative) px + cmd.x else cmd.x
                    add(x, py)
                }
                is PathCommand.VerticalTo -> {
                    val y = if (cmd.relative) py + cmd.y else cmd.y
                    add(px, y)
                }
                is PathCommand.QuadTo -> {
                    hadCurves = true
                    val qx = if (cmd.relative) px + cmd.x1 else cmd.x1
                    val qy = if (cmd.relative) py + cmd.y1 else cmd.y1
                    val x = if (cmd.relative) px + cmd.x else cmd.x
                    val y = if (cmd.relative) py + cmd.y else cmd.y
                    flattenQuad(px, py, qx, qy, x, y)
                    lastQuadCtrlX = qx
                    lastQuadCtrlY = qy
                    producedQuad = true
                }
                is PathCommand.SmoothQuadTo -> {
                    hadCurves = true
                    val qx = if (lastWasQuad) 2f * px - lastQuadCtrlX else px
                    val qy = if (lastWasQuad) 2f * py - lastQuadCtrlY else py
                    val x = if (cmd.relative) px + cmd.x else cmd.x
                    val y = if (cmd.relative) py + cmd.y else cmd.y
                    flattenQuad(px, py, qx, qy, x, y)
                    lastQuadCtrlX = qx
                    lastQuadCtrlY = qy
                    producedQuad = true
                }
                is PathCommand.CubicTo -> {
                    hadCurves = true
                    val c1x = if (cmd.relative) px + cmd.x1 else cmd.x1
                    val c1y = if (cmd.relative) py + cmd.y1 else cmd.y1
                    val c2x = if (cmd.relative) px + cmd.x2 else cmd.x2
                    val c2y = if (cmd.relative) py + cmd.y2 else cmd.y2
                    val x = if (cmd.relative) px + cmd.x else cmd.x
                    val y = if (cmd.relative) py + cmd.y else cmd.y
                    flattenCubic(px, py, c1x, c1y, c2x, c2y, x, y)
                    lastCubicCtrlX = c2x
                    lastCubicCtrlY = c2y
                    producedCubic = true
                }
                is PathCommand.SmoothCubicTo -> {
                    hadCurves = true
                    val c1x = if (lastWasCubic) 2f * px - lastCubicCtrlX else px
                    val c1y = if (lastWasCubic) 2f * py - lastCubicCtrlY else py
                    val c2x = if (cmd.relative) px + cmd.x2 else cmd.x2
                    val c2y = if (cmd.relative) py + cmd.y2 else cmd.y2
                    val x = if (cmd.relative) px + cmd.x else cmd.x
                    val y = if (cmd.relative) py + cmd.y else cmd.y
                    flattenCubic(px, py, c1x, c1y, c2x, c2y, x, y)
                    lastCubicCtrlX = c2x
                    lastCubicCtrlY = c2y
                    producedCubic = true
                }
                is PathCommand.ArcTo -> {
                    // Conservative: keep only the endpoint, no arc flattening.
                    hadCurves = true
                    val x = if (cmd.relative) px + cmd.x else cmd.x
                    val y = if (cmd.relative) py + cmd.y else cmd.y
                    add(x, y)
                }
                is PathCommand.Close -> {
                    closed = true
                    cx = startX
                    cy = startY
                }
            }

            lastWasCubic = producedCubic
            lastWasQuad = producedQuad
        }

        return SampledPath(points = points, closed = closed, sourceHadCurves = hadCurves)
    }
}
