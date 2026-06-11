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
 * Sub-phase 11.2 — connector rasterizer.
 *
 * Draws the resolved segment (caller runs [ConnectorResolver] because only
 * it knows where the other items live) with the item's colour / width, the
 * payload's stroke style, and optional arrowheads at either end. Dashed /
 * dotted segments route through a [Path] for the same hardware-pipeline
 * reason as [ShapeRenderer].
 */
object ConnectorRenderer {

    fun draw(
        canvas: Canvas,
        item: NoteItem,
        payload: ConnectorCodec.ConnectorPayload,
        endpoints: FloatArray,
        paint: Paint,
        scratchPath: Path = Path(),
    ) {
        val x0 = endpoints[0]; val y0 = endpoints[1]
        val x1 = endpoints[2]; val y1 = endpoints[3]
        ShapeRenderer.configurePaint(paint, item.colorArgb, item.baseWidthPx, payload.strokeStyle)
        if (paint.pathEffect == null) {
            canvas.drawLine(x0, y0, x1, y1, paint)
        } else {
            scratchPath.reset()
            scratchPath.moveTo(x0, y0)
            scratchPath.lineTo(x1, y1)
            canvas.drawPath(scratchPath, paint)
            scratchPath.reset()
        }
        val headSize = headSizeFor(item.baseWidthPx)
        if (payload.arrowAtEnd) drawHead(canvas, x0, y0, x1, y1, headSize, paint, scratchPath)
        if (payload.arrowAtStart) drawHead(canvas, x1, y1, x0, y0, headSize, paint, scratchPath)
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
