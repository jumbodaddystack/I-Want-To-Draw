package com.aichat.sandbox.data.vector.notesbridge

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.vector.VectorPoint
import com.aichat.sandbox.data.vector.VectorStyle
import com.aichat.sandbox.data.vector.edit.AnchorType
import com.aichat.sandbox.data.vector.edit.ControlPoint
import com.aichat.sandbox.data.vector.edit.EditAnchor
import com.aichat.sandbox.data.vector.edit.EditSubpath
import com.aichat.sandbox.data.vector.edit.EditablePath
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import kotlin.math.max

/**
 * Phase 4 — converts a committed freehand `kind=stroke` [NoteItem] into the
 * shared, all-cubic [EditablePath] model.
 *
 * Pipeline (the bridge's decision record §2 — *centerline cubic + uniform
 * stroke*):
 *  1. decode samples via [StrokeCodec] and collapse them to a centerline
 *     polyline (pressure/tilt are dropped here but their mean drives width);
 *  2. simplify the centerline with the shared [PolylineSimplify] RDP;
 *  3. optionally snap to an exact primitive via [AutoShapeFitter] (a wobbly
 *     circle becomes a real ellipse) — opt out with `autoShape = false`;
 *  4. otherwise Catmull-Rom curve-fit the kept vertices to cubic anchors
 *     (interior anchors SMOOTH, endpoints CORNER) → one open [EditSubpath];
 *  5. carry color → `strokeColor` and width-weighted mean pressure →
 *     `strokeWidth`, round cap/join, no fill.
 *
 * Pure (no Android imports): [StrokeCodec] is plain Kotlin.
 */
object StrokeVectorizer {

    /** RDP tolerance (viewport units) applied to the raw centerline before fitting. */
    const val SIMPLIFY_TOLERANCE = 0.75f

    fun toEditablePath(
        item: NoteItem,
        widthMode: WidthMode = WidthMode.CENTERLINE_UNIFORM,
        autoShape: Boolean = true,
    ): EditablePath? {
        if (item.kind != NoteItem.KIND_STROKE) return null
        val decoded = try {
            StrokeCodec.decode(item.payload)
        } catch (_: Throwable) {
            return null
        }
        val stride = StrokeCodec.FLOATS_PER_SAMPLE
        val count = decoded.size / stride
        if (count < 1) return null

        val raw = ArrayList<VectorPoint>(count)
        var sumPressure = 0f
        for (i in 0 until count) {
            val o = i * stride
            raw += VectorPoint(decoded[o], decoded[o + 1])
            sumPressure += decoded[o + 2]
        }
        val meanPressure = if (count > 0) (sumPressure / count) else 1f
        val style = strokeStyle(item, meanPressure)
        val pathId = NoteVectorBridge.pathId(item)

        // A single committed point carries no editable geometry.
        val deduped = dedupe(raw)
        if (deduped.size < 2) return null

        val simplified = PolylineSimplify.simplify(deduped, SIMPLIFY_TOLERANCE)

        if (autoShape) {
            AutoShapeFitter.fit(simplified)?.let { shape ->
                ShapeVectorizer.fromShape(shape, style, pathId, item.id)?.let { return it }
            }
        }

        val subpath = curveFit(simplified, "$pathId.s0")
        return EditablePath(
            pathId = pathId,
            name = null,
            subpaths = listOf(subpath),
            style = style,
        )
    }

    /** Stroke paint: color from the item, width = base × mean pressure (round caps). */
    private fun strokeStyle(item: NoteItem, meanPressure: Float): VectorStyle {
        val mp = meanPressure.coerceIn(0.1f, 1f)
        val width = max(0.5f, item.baseWidthPx * mp)
        return VectorStyle(
            strokeColor = ColorHex.argb(item.colorArgb),
            strokeWidth = width,
            strokeLineCap = "round",
            strokeLineJoin = "round",
        )
    }

    private fun dedupe(points: List<VectorPoint>): List<VectorPoint> {
        if (points.size < 2) return points
        val out = ArrayList<VectorPoint>(points.size)
        out += points[0]
        for (i in 1 until points.size) {
            val prev = out.last()
            val cur = points[i]
            if (kotlin.math.hypot(cur.x - prev.x, cur.y - prev.y) > 1e-4f) out += cur
        }
        return out
    }

    /**
     * Catmull-Rom → Bézier curve fit. Each kept vertex becomes an on-curve
     * [EditAnchor]; tangent handles come from the neighbours
     * (`handle = p ± (next − prev)/6`, with endpoints clamping the missing
     * neighbour). Interior anchors are SMOOTH; the two endpoints are CORNERs with
     * a single outgoing/incoming handle so the open path round-trips cleanly.
     */
    private fun curveFit(points: List<VectorPoint>, subpathId: String): EditSubpath {
        val n = points.size
        val anchors = ArrayList<EditAnchor>(n)
        for (i in 0 until n) {
            val p = points[i]
            val prev = points[if (i == 0) 0 else i - 1]
            val next = points[if (i == n - 1) n - 1 else i + 1]
            val tx = (next.x - prev.x) / 6f
            val ty = (next.y - prev.y) / 6f
            val inHandle = if (i == 0) null else ControlPoint(p.x - tx, p.y - ty)
            val outHandle = if (i == n - 1) null else ControlPoint(p.x + tx, p.y + ty)
            val type = if (i == 0 || i == n - 1) AnchorType.CORNER else AnchorType.SMOOTH
            anchors += EditAnchor(
                id = "$subpathId.a$i",
                x = p.x, y = p.y,
                inHandle = inHandle, outHandle = outHandle,
                type = type,
            )
        }
        return EditSubpath(id = subpathId, anchors = anchors, closed = false)
    }
}
