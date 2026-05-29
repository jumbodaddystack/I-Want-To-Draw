package com.aichat.sandbox.ui.components.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.ui.theme.studio.StudioTheme

/**
 * The signature moment of Studio Bench: a measured "bench" that cradles the
 * artwork. The art sits inset on a faint precision grid; the cradle draws
 * hairline corner ticks and pins a live mono dimension readout to one edge, so
 * the artwork literally rests in a measured instrument rather than on a generic
 * shadowed card.
 *
 * This is deliberately presentational — it frames whatever [content] you place
 * (a preview canvas, an icon bitmap, an empty hint). The grid + ticks are drawn
 * behind the content; the readout floats at top-start.
 *
 * @param dimensionLabel mono readout, e.g. "108 × 108" or "2.4 KB" — null hides it.
 * @param showGrid faint dot/line precision grid behind the art.
 */
@Composable
fun ArtboardCradle(
    modifier: Modifier = Modifier,
    dimensionLabel: String? = null,
    showGrid: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = StudioTheme.colors
    val radius = StudioTheme.radius
    val sizing = StudioTheme.sizing
    val spacing = StudioTheme.spacing

    Box(
        modifier = modifier
            .background(colors.artboardCradle)
            .drawBehind {
                // Faint precision grid — a measured ground, not decoration.
                if (showGrid) {
                    val step = 24.dp.toPx()
                    val gridColor = colors.hairline
                    var x = step
                    while (x < size.width) {
                        drawLine(
                            color = gridColor,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1f,
                            alpha = 0.4f,
                        )
                        x += step
                    }
                    var y = step
                    while (y < size.height) {
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1f,
                            alpha = 0.4f,
                        )
                        y += step
                    }
                }
                // Machined corner ticks at the four corners.
                val tick = sizing.cornerTick.toPx()
                val tickColor = colors.hairlineStrong
                val sw = sizing.hairlineStrong.toPx()
                val r = radius.l.toPx()
                // top-left
                drawLine(tickColor, Offset(0f, r), Offset(0f, r + tick), sw)
                drawLine(tickColor, Offset(r, 0f), Offset(r + tick, 0f), sw)
                // top-right
                drawLine(tickColor, Offset(size.width, r), Offset(size.width, r + tick), sw)
                drawLine(tickColor, Offset(size.width - r, 0f), Offset(size.width - r - tick, 0f), sw)
                // bottom-left
                drawLine(tickColor, Offset(0f, size.height - r), Offset(0f, size.height - r - tick), sw)
                drawLine(tickColor, Offset(r, size.height), Offset(r + tick, size.height), sw)
                // bottom-right
                drawLine(tickColor, Offset(size.width, size.height - r), Offset(size.width, size.height - r - tick), sw)
                drawLine(tickColor, Offset(size.width - r, size.height), Offset(size.width - r - tick, size.height), sw)
                // Hairline frame edge.
                drawRect(
                    color = colors.hairline,
                    style = Stroke(width = sw),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (dimensionLabel != null) {
            // Live dimension readout pinned to the top edge — instrument-style.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(spacing.s)
                    .background(colors.canvasSunken)
                    .padding(horizontal = spacing.s, vertical = spacing.hair)
                    .semantics { contentDescription = "Artboard size $dimensionLabel" },
            ) {
                com.aichat.sandbox.ui.theme.studio.StudioText(
                    text = dimensionLabel,
                    style = StudioTheme.type.monoTick,
                    color = colors.inkMuted,
                )
            }
        }
    }
}
