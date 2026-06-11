package com.aichat.sandbox.ui.components.notes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.aichat.sandbox.data.model.NoteItem

/**
 * Sub-phase 12.1 — path rasterizer.
 *
 * Builds an [android.graphics.Path] from a [PathCodec.PathPayload]'s cubic
 * segments and draws it with the item's colour / width, the payload's
 * stroke style (dash patterns shared with [ShapeRenderer]) and cap / join.
 * The optional fill pass mirrors [ShapeRenderer]: closed paths only, with
 * the dash effect nulled so it can never slice the fill geometry.
 */
object PathRenderer {

    /** Rebuild [path] (reset + cubics) from [payload]. */
    fun buildPath(payload: PathCodec.PathPayload, path: Path) {
        path.reset()
        if (payload.anchors.isEmpty()) return
        val first = payload.anchors[0]
        path.moveTo(first.x, first.y)
        for (i in 0 until payload.segmentCount) {
            val s = PathCodec.segment(payload, i)
            path.cubicTo(s[2], s[3], s[4], s[5], s[6], s[7])
        }
        if (payload.closed) path.close()
    }

    /** Draw [item] (whose payload decodes via [PathCodec]). */
    fun draw(canvas: Canvas, item: NoteItem, paint: Paint, scratchPath: Path = Path()) {
        val payload = PathCodec.decode(item.payload)
        drawPayload(canvas, payload, item.colorArgb, item.baseWidthPx, paint, scratchPath)
    }

    fun drawPayload(
        canvas: Canvas,
        payload: PathCodec.PathPayload,
        colorArgb: Int,
        strokeWidth: Float,
        paint: Paint,
        scratchPath: Path = Path(),
    ) {
        if (payload.anchors.size < 2) return
        ShapeRenderer.configurePaint(paint, colorArgb, strokeWidth, payload.strokeStyle)
        paint.strokeCap = when (PathCodec.cap(payload.capJoin)) {
            PathCodec.CAP_BUTT -> Paint.Cap.BUTT
            PathCodec.CAP_SQUARE -> Paint.Cap.SQUARE
            else -> Paint.Cap.ROUND
        }
        paint.strokeJoin = when (PathCodec.join(payload.capJoin)) {
            PathCodec.JOIN_MITER -> Paint.Join.MITER
            PathCodec.JOIN_BEVEL -> Paint.Join.BEVEL
            else -> Paint.Join.ROUND
        }
        buildPath(payload, scratchPath)
        if (payload.closed && (payload.fillArgb != 0 || payload.gradient != null)) {
            val strokeColor = paint.color
            val strokeAlpha = paint.alpha
            val strokeEffect = paint.pathEffect
            paint.style = Paint.Style.FILL
            paint.pathEffect = null
            // 13.2 — gradient fill maps the normalized geometry onto the
            // path's exact cubic-extrema bounds.
            val shader = payload.gradient?.let { g ->
                PathCodec.boundsOf(payload)?.let { GradientShaderFactory.shaderFor(g, it) }
            }
            if (shader != null) {
                paint.shader = shader
                paint.color = -0x1
                paint.alpha = 255
            } else {
                paint.color = payload.fillArgb
                paint.alpha = Color.alpha(payload.fillArgb)
            }
            canvas.drawPath(scratchPath, paint)
            paint.shader = null
            paint.style = Paint.Style.STROKE
            paint.color = strokeColor
            paint.alpha = strokeAlpha
            paint.pathEffect = strokeEffect
        }
        canvas.drawPath(scratchPath, paint)
        scratchPath.reset()
    }
}
