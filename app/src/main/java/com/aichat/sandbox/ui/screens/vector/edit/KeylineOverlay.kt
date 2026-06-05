package com.aichat.sandbox.ui.screens.vector.edit

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.aichat.sandbox.data.vector.KeylineGrid
import com.aichat.sandbox.ui.components.notes.ViewportController

/**
 * Phase 3 — draws a [KeylineGrid] over the node-editor canvas.
 *
 * The grid figures are viewport-space floats (the same space anchors live in), so
 * they map to screen through the editor's [ViewportController] exactly like the
 * geometry — keeping the guide the user sees aligned with the integer grid the
 * pixel-snap quantizes onto. Pure presentation: it draws thin constant-width
 * guides (the safe-zone edges + centre cross) and the selected keyline shapes
 * (circle / square / rounded-square / rects) under the anchor overlay. There is no
 * edit logic here.
 */
internal fun DrawScope.drawKeylineOverlay(
    keyline: KeylineGrid,
    viewport: ViewportController,
    lineColor: Color,
    shapeColor: Color,
    strokeWidthPx: Float,
) {
    fun sx(x: Float) = viewport.worldToScreenX(x)
    fun sy(y: Float) = viewport.worldToScreenY(y)

    val shapeStroke = Stroke(width = strokeWidthPx)

    // Keyline guides: safe-zone edges + centre cross.
    for (line in keyline.keylineLines()) {
        drawLine(
            color = lineColor.copy(alpha = 0.5f),
            start = Offset(sx(line.x0), sy(line.y0)),
            end = Offset(sx(line.x1), sy(line.y1)),
            strokeWidth = strokeWidthPx,
        )
    }

    // Keyline shapes.
    for (figure in keyline.shapeFigures()) {
        when (figure) {
            is KeylineGrid.Circle -> {
                val center = Offset(sx(figure.cx), sy(figure.cy))
                drawCircle(
                    color = shapeColor.copy(alpha = 0.6f),
                    radius = figure.r * viewport.scale,
                    center = center,
                    style = shapeStroke,
                )
            }
            is KeylineGrid.RoundRect -> {
                val topLeft = Offset(sx(figure.l), sy(figure.t))
                val size = Size(
                    (figure.r - figure.l) * viewport.scale,
                    (figure.b - figure.t) * viewport.scale,
                )
                if (figure.corner > 0f) {
                    val radius = figure.corner * viewport.scale
                    drawRoundRect(
                        color = shapeColor.copy(alpha = 0.6f),
                        topLeft = topLeft,
                        size = size,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                        style = shapeStroke,
                    )
                } else {
                    drawRect(
                        color = shapeColor.copy(alpha = 0.6f),
                        topLeft = topLeft,
                        size = size,
                        style = shapeStroke,
                    )
                }
            }
            is KeylineGrid.Line -> {
                drawLine(
                    color = shapeColor.copy(alpha = 0.6f),
                    start = Offset(sx(figure.x0), sy(figure.y0)),
                    end = Offset(sx(figure.x1), sy(figure.y1)),
                    strokeWidth = strokeWidthPx,
                )
            }
        }
    }
}
