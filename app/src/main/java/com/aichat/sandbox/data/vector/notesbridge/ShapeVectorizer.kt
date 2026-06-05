package com.aichat.sandbox.data.vector.notesbridge

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.vector.PathCommand
import com.aichat.sandbox.data.vector.PathDataFormatter
import com.aichat.sandbox.data.vector.VectorPath
import com.aichat.sandbox.data.vector.VectorStyle
import com.aichat.sandbox.data.vector.edit.EditablePath
import com.aichat.sandbox.data.vector.edit.EditablePathFactory
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import kotlin.math.cos
import kotlin.math.sin

/**
 * Phase 4 — converts a notes [Shape] into **exact** [PathCommand]s and the
 * shared, all-cubic [EditablePath] model.
 *
 * Lines/rects/polygons emit their literal geometry; ellipses and rounded-rect
 * corners use the standard `0.5523` kappa cubic-arc approximation. The commands
 * are run through the Phase-1 [EditablePathFactory] so the result is the same
 * editable type a vectorized freehand stroke produces — node editing, boolean
 * ops, and lossless export then all work uniformly.
 *
 * Pure (no Android imports): [ShapeCodec]/[Shape] are plain Kotlin value types.
 */
object ShapeVectorizer {

    /** Cubic-Bézier circle constant: handle length for a quarter arc of radius 1. */
    private const val KAPPA = 0.5522847498307936f

    /** Decode a `kind=shape` [item] and vectorize it; null when undecodable. */
    fun toEditablePath(item: NoteItem): EditablePath? {
        if (item.kind != NoteItem.KIND_SHAPE) return null
        val decoded = try {
            ShapeCodec.decode(item.payload)
        } catch (_: Throwable) {
            return null
        }
        val style = VectorStyle(
            strokeColor = ColorHex.argb(item.colorArgb),
            strokeWidth = item.baseWidthPx,
            strokeLineCap = "round",
            strokeLineJoin = "round",
            fillColor = if (decoded.fillArgb == 0) null else ColorHex.argb(decoded.fillArgb),
        )
        return fromShape(decoded.shape, style, NoteVectorBridge.pathId(item), item.id)
    }

    /** Vectorize an explicit [shape] with a caller-chosen [style] and id. */
    fun fromShape(shape: Shape, style: VectorStyle, pathId: String, name: String? = null): EditablePath? {
        val commands = toCommands(shape)
        if (commands.isEmpty()) return null
        val path = VectorPath(
            id = pathId,
            name = name,
            pathData = PathDataFormatter.format(commands),
            commands = commands,
            style = style,
        )
        val editable = EditablePathFactory.fromPath(path)
        if (editable.subpaths.isEmpty()) return null
        return editable
    }

    /** Exact path commands for [shape] (absolute M/L/C/Z; kappa arcs for curves). */
    fun toCommands(shape: Shape): List<PathCommand> = when (shape) {
        is Shape.Line -> listOf(
            PathCommand.MoveTo(shape.x0, shape.y0),
            PathCommand.LineTo(shape.x1, shape.y1),
        )
        is Shape.Rect -> rectCommands(shape)
        is Shape.Ellipse -> ellipseCommands(shape)
        is Shape.Arrow -> arrowCommands(shape)
        is Shape.Polygon -> polygonCommands(shape)
    }

    private fun rectCommands(r: Shape.Rect): List<PathCommand> {
        val minX = r.minX; val minY = r.minY; val maxX = r.maxX; val maxY = r.maxY
        val w = maxX - minX; val h = maxY - minY
        if (w <= 0f || h <= 0f) return emptyList()
        val rad = r.cornerRadius.coerceIn(0f, minOf(w, h) / 2f)
        if (rad <= 0f) {
            return listOf(
                PathCommand.MoveTo(minX, minY),
                PathCommand.LineTo(maxX, minY),
                PathCommand.LineTo(maxX, maxY),
                PathCommand.LineTo(minX, maxY),
                PathCommand.Close(),
            )
        }
        val k = rad * KAPPA
        return listOf(
            PathCommand.MoveTo(minX + rad, minY),
            PathCommand.LineTo(maxX - rad, minY),
            PathCommand.CubicTo(maxX - rad + k, minY, maxX, minY + rad - k, maxX, minY + rad),
            PathCommand.LineTo(maxX, maxY - rad),
            PathCommand.CubicTo(maxX, maxY - rad + k, maxX - rad + k, maxY, maxX - rad, maxY),
            PathCommand.LineTo(minX + rad, maxY),
            PathCommand.CubicTo(minX + rad - k, maxY, minX, maxY - rad + k, minX, maxY - rad),
            PathCommand.LineTo(minX, minY + rad),
            PathCommand.CubicTo(minX, minY + rad - k, minX + rad - k, minY, minX + rad, minY),
            PathCommand.Close(),
        )
    }

    private fun ellipseCommands(e: Shape.Ellipse): List<PathCommand> {
        val rx = kotlin.math.abs(e.rx)
        val ry = kotlin.math.abs(e.ry)
        if (rx <= 0f || ry <= 0f) return emptyList()
        val kx = rx * KAPPA
        val ky = ry * KAPPA
        // Quarter-arc anchors (right, bottom, left, top) and their cubic handles
        // in un-rotated space, then rotate every point about the centre.
        val cos = cos(e.rotationRad)
        val sin = sin(e.rotationRad)
        fun rx2(x: Float, y: Float) = e.cx + (x * cos - y * sin)
        fun ry2(x: Float, y: Float) = e.cy + (x * sin + y * cos)
        fun cubic(c1x: Float, c1y: Float, c2x: Float, c2y: Float, ex: Float, ey: Float) =
            PathCommand.CubicTo(
                rx2(c1x, c1y), ry2(c1x, c1y),
                rx2(c2x, c2y), ry2(c2x, c2y),
                rx2(ex, ey), ry2(ex, ey),
            )
        return listOf(
            PathCommand.MoveTo(rx2(rx, 0f), ry2(rx, 0f)),
            cubic(rx, ky, kx, ry, 0f, ry),
            cubic(-kx, ry, -rx, ky, -rx, 0f),
            cubic(-rx, -ky, -kx, -ry, 0f, -ry),
            cubic(kx, -ry, rx, -ky, rx, 0f),
            PathCommand.Close(),
        )
    }

    private fun arrowCommands(a: Shape.Arrow): List<PathCommand> {
        val out = ArrayList<PathCommand>(5)
        out += PathCommand.MoveTo(a.x0, a.y0)
        out += PathCommand.LineTo(a.x1, a.y1)
        val dx = a.x1 - a.x0
        val dy = a.y1 - a.y0
        val len = kotlin.math.hypot(dx, dy)
        if (len >= 1e-3f) {
            val angle = kotlin.math.atan2(dy, dx)
            val headSize = if (a.headSize > 0f) a.headSize else len * 0.2f
            val ha = (Math.PI / 6.0)
            val hx1 = a.x1 - headSize * cos((angle - ha).toFloat())
            val hy1 = a.y1 - headSize * sin((angle - ha).toFloat())
            val hx2 = a.x1 - headSize * cos((angle + ha).toFloat())
            val hy2 = a.y1 - headSize * sin((angle + ha).toFloat())
            // Two head edges as a fresh subpath from the tip.
            out += PathCommand.MoveTo(hx1, hy1)
            out += PathCommand.LineTo(a.x1, a.y1)
            out += PathCommand.LineTo(hx2, hy2)
        }
        return out
    }

    private fun polygonCommands(p: Shape.Polygon): List<PathCommand> {
        if (p.points.size < 4) return emptyList()
        val out = ArrayList<PathCommand>(p.points.size / 2 + 1)
        out += PathCommand.MoveTo(p.points[0], p.points[1])
        var i = 2
        while (i < p.points.size) {
            out += PathCommand.LineTo(p.points[i], p.points[i + 1])
            i += 2
        }
        if (p.closed) out += PathCommand.Close()
        return out
    }
}
