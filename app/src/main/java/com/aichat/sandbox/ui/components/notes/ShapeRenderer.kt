package com.aichat.sandbox.ui.components.notes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.aichat.sandbox.data.model.NoteItem
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Phase 6.2 — shape rasterizer.
 *
 * Draws a [Shape] onto a [Canvas]. Stroke colour / width / antialias setup
 * mirrors [StrokeRenderer]: the caller already configured the [Paint], we
 * only twiddle [Paint.style] / [Paint.color] when emitting the optional fill
 * pass.
 */
object ShapeRenderer {

    /**
     * Configure [paint] for a shape outline. Square caps + miter joins so
     * rectangles and arrows render with the corners users expect.
     *
     * Phase 10.3 — [strokeStyle] maps to a [DashPathEffect]: dashes scale
     * with stroke width so thick markers keep legible gaps; dots are
     * round-cap zero-ish dashes.
     */
    fun configurePaint(
        paint: Paint,
        colorArgb: Int,
        strokeWidth: Float,
        strokeStyle: Byte = ShapeCodec.STROKE_STYLE_SOLID,
    ) {
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.MITER
        paint.strokeCap = Paint.Cap.ROUND
        paint.isAntiAlias = true
        paint.color = colorArgb
        paint.alpha = Color.alpha(colorArgb)
        paint.strokeWidth = strokeWidth
        paint.shader = null
        paint.pathEffect = when (strokeStyle) {
            ShapeCodec.STROKE_STYLE_DASHED -> DashPathEffect(
                floatArrayOf(strokeWidth * DASH_ON_FACTOR, strokeWidth * DASH_OFF_FACTOR), 0f,
            )
            ShapeCodec.STROKE_STYLE_DOTTED -> DashPathEffect(
                floatArrayOf(strokeWidth * DOT_ON_FACTOR, strokeWidth * DOT_OFF_FACTOR), 0f,
            )
            else -> null
        }
    }

    /** Draw [item] (whose payload decodes to a [Shape]). */
    fun draw(canvas: Canvas, item: NoteItem, paint: Paint = Paint()) {
        val decoded = ShapeCodec.decode(item.payload)
        configurePaint(paint, item.colorArgb, item.baseWidthPx, decoded.strokeStyle)
        drawShape(canvas, decoded.shape, paint, decoded.fillArgb)
    }

    fun drawShape(canvas: Canvas, shape: Shape, paint: Paint, fillArgb: Int) {
        val strokeColor = paint.color
        val strokeAlpha = paint.alpha
        val strokeStyle = paint.style
        val strokeWidth = paint.strokeWidth
        // Phase 10.3 — a DashPathEffect must never leak into a fill pass (it
        // would slice the fill geometry into dash fragments), so fills run
        // with pathEffect nulled and the outline pass restores it.
        val strokeEffect = paint.pathEffect

        fun fillPass(draw: () -> Unit) {
            paint.style = Paint.Style.FILL
            paint.color = fillArgb
            paint.alpha = Color.alpha(fillArgb)
            paint.pathEffect = null
            draw()
            paint.style = strokeStyle
            paint.color = strokeColor
            paint.alpha = strokeAlpha
            paint.strokeWidth = strokeWidth
            paint.pathEffect = strokeEffect
        }

        val rect = RectF()
        val path = Path()
        when (shape) {
            is Shape.Line -> {
                drawStraightLine(canvas, shape.x0, shape.y0, shape.x1, shape.y1, paint, path)
            }
            is Shape.Rect -> {
                rect.set(shape.minX, shape.minY, shape.maxX, shape.maxY)
                if (fillArgb != 0) {
                    fillPass {
                        if (shape.cornerRadius > 0f) {
                            canvas.drawRoundRect(rect, shape.cornerRadius, shape.cornerRadius, paint)
                        } else {
                            canvas.drawRect(rect, paint)
                        }
                    }
                }
                if (shape.cornerRadius > 0f) {
                    canvas.drawRoundRect(rect, shape.cornerRadius, shape.cornerRadius, paint)
                } else {
                    canvas.drawRect(rect, paint)
                }
            }
            is Shape.Ellipse -> {
                rect.set(
                    shape.cx - shape.rx, shape.cy - shape.ry,
                    shape.cx + shape.rx, shape.cy + shape.ry,
                )
                val rotated = shape.rotationRad != 0f
                if (rotated) {
                    canvas.save()
                    canvas.rotate(Math.toDegrees(shape.rotationRad.toDouble()).toFloat(), shape.cx, shape.cy)
                }
                if (fillArgb != 0) {
                    fillPass { canvas.drawOval(rect, paint) }
                }
                canvas.drawOval(rect, paint)
                if (rotated) canvas.restore()
            }
            is Shape.Arrow -> {
                drawStraightLine(canvas, shape.x0, shape.y0, shape.x1, shape.y1, paint, path)
                drawArrowhead(canvas, shape, paint, path)
            }
            is Shape.Polygon -> {
                if (shape.points.size < 4) return
                path.moveTo(shape.points[0], shape.points[1])
                var i = 2
                while (i < shape.points.size) {
                    path.lineTo(shape.points[i], shape.points[i + 1])
                    i += 2
                }
                if (shape.closed) path.close()
                if (shape.closed && fillArgb != 0) {
                    fillPass { canvas.drawPath(path, paint) }
                }
                canvas.drawPath(path, paint)
            }
        }
        // Restore in case fill pass mutated paint state.
        paint.style = strokeStyle
        paint.color = strokeColor
        paint.alpha = strokeAlpha
        paint.strokeWidth = strokeWidth
        paint.pathEffect = strokeEffect
    }

    /**
     * `Canvas.drawLine` ignores [Paint.pathEffect] on some hardware
     * pipelines; routing dashed/dotted lines through a [Path] is reliable
     * everywhere. Solid lines keep the cheaper drawLine.
     */
    private fun drawStraightLine(
        canvas: Canvas,
        x0: Float, y0: Float, x1: Float, y1: Float,
        paint: Paint,
        path: Path,
    ) {
        if (paint.pathEffect == null) {
            canvas.drawLine(x0, y0, x1, y1, paint)
        } else {
            path.reset()
            path.moveTo(x0, y0)
            path.lineTo(x1, y1)
            canvas.drawPath(path, paint)
            path.reset()
        }
    }

    private fun drawArrowhead(canvas: Canvas, arrow: Shape.Arrow, paint: Paint, path: Path) {
        val dx = arrow.x1 - arrow.x0
        val dy = arrow.y1 - arrow.y0
        val len = hypot(dx, dy)
        if (len < 0.5f) return
        val angle = atan2(dy, dx)
        val headSize = arrow.headSize.coerceAtLeast(paint.strokeWidth * 4f)
        val headAngle = Math.PI / 6.0  // 30°
        val hx1 = arrow.x1 - headSize * cos(angle - headAngle).toFloat()
        val hy1 = arrow.y1 - headSize * sin(angle - headAngle).toFloat()
        val hx2 = arrow.x1 - headSize * cos(angle + headAngle).toFloat()
        val hy2 = arrow.y1 - headSize * sin(angle + headAngle).toFloat()
        val previousStyle = paint.style
        val previousEffect = paint.pathEffect
        paint.style = Paint.Style.FILL
        paint.pathEffect = null
        path.reset()
        path.moveTo(arrow.x1, arrow.y1)
        path.lineTo(hx1, hy1)
        path.lineTo(hx2, hy2)
        path.close()
        canvas.drawPath(path, paint)
        paint.style = previousStyle
        paint.pathEffect = previousEffect
    }

    /** Dash pattern factors (× stroke width) — mirrored by the SVG exporter. */
    const val DASH_ON_FACTOR = 3f
    const val DASH_OFF_FACTOR = 2f
    const val DOT_ON_FACTOR = 0.1f
    const val DOT_OFF_FACTOR = 2f
}
