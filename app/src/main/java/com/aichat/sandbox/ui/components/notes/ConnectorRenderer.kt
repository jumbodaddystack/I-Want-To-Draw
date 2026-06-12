package com.aichat.sandbox.ui.components.notes

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.aichat.sandbox.data.model.NoteItem
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Sub-phase 11.2 — connector rasterizer (14.2: route-aware).
 *
 * Draws the routed geometry (caller runs [ConnectorRouter] because only it
 * knows where the other items live) with the item's colour / width, the
 * payload's stroke style, and optional arrowheads at either end. Arrowheads
 * orient along the route's terminal tangents so elbow heads sit flush with
 * their final segment. Dashed / dotted segments route through a [Path] for
 * the same hardware-pipeline reason as [ShapeRenderer].
 */
object ConnectorRenderer {

    fun draw(
        canvas: Canvas,
        item: NoteItem,
        payload: ConnectorCodec.ConnectorPayload,
        route: ConnectorRouter.Route,
        paint: Paint,
        scratchPath: Path = Path(),
    ) {
        val pts = route.points
        ShapeRenderer.configurePaint(paint, item.colorArgb, item.baseWidthPx, payload.strokeStyle)
        if (!route.curved && pts.size == 4 && paint.pathEffect == null) {
            canvas.drawLine(pts[0], pts[1], pts[2], pts[3], paint)
        } else {
            scratchPath.reset()
            scratchPath.moveTo(pts[0], pts[1])
            if (route.curved) {
                scratchPath.cubicTo(pts[2], pts[3], pts[4], pts[5], pts[6], pts[7])
            } else {
                for (i in 1 until pts.size / 2) {
                    scratchPath.lineTo(pts[i * 2], pts[i * 2 + 1])
                }
            }
            canvas.drawPath(scratchPath, paint)
            scratchPath.reset()
        }
        val headSize = headSizeFor(item.baseWidthPx)
        if (payload.arrowAtEnd) {
            val t = ConnectorRouter.endTangent(route)
            drawHead(canvas, t[0], t[1], t[2], t[3], headSize, paint, scratchPath)
        }
        if (payload.arrowAtStart) {
            val t = ConnectorRouter.startTangent(route)
            drawHead(canvas, t[0], t[1], t[2], t[3], headSize, paint, scratchPath)
        }
    }

    /** Arrowhead size convention — mirrors the SVG exporter. */
    fun headSizeFor(strokeWidth: Float): Float = (strokeWidth * 6f).coerceAtLeast(8f)

    private fun drawHead(
        canvas: Canvas,
        fromX: Float, fromY: Float,
        tipX: Float, tipY: Float,
        headSize: Float,
        paint: Paint,
        path: Path,
    ) {
        val dx = tipX - fromX
        val dy = tipY - fromY
        if (hypot(dx, dy) < 0.5f) return
        val angle = atan2(dy, dx)
        val headAngle = Math.PI / 6.0
        val hx1 = tipX - headSize * cos(angle - headAngle).toFloat()
        val hy1 = tipY - headSize * sin(angle - headAngle).toFloat()
        val hx2 = tipX - headSize * cos(angle + headAngle).toFloat()
        val hy2 = tipY - headSize * sin(angle + headAngle).toFloat()
        val previousStyle = paint.style
        val previousEffect = paint.pathEffect
        paint.style = Paint.Style.FILL
        paint.pathEffect = null
        path.reset()
        path.moveTo(tipX, tipY)
        path.lineTo(hx1, hy1)
        path.lineTo(hx2, hy2)
        path.close()
        canvas.drawPath(path, paint)
        path.reset()
        paint.style = previousStyle
        paint.pathEffect = previousEffect
    }
}
