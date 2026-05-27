package com.aichat.sandbox.ui.screens.vector

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import com.aichat.sandbox.data.vector.PreviewSegment
import com.aichat.sandbox.data.vector.PreviewSubpath
import com.aichat.sandbox.data.vector.VectorBounds
import com.aichat.sandbox.data.vector.VectorPreviewModel
import com.aichat.sandbox.data.vector.VectorPreviewPathNormalizer
import com.aichat.sandbox.data.vector.VectorViewport
import kotlin.math.min
import kotlin.math.roundToInt

/** Fraction of the smaller canvas dimension reserved as padding around the art. */
private const val PADDING_FRACTION = 0.06f

/** Constant on-screen width (px) for highlight/bounds outlines, before unscaling. */
private const val HIGHLIGHT_WIDTH_PX = 2.5f
private const val BOUNDS_WIDTH_PX = 1.5f

/**
 * Phase 8 — renders a [VectorPreviewModel] onto a Compose [Canvas].
 *
 * Draws from the app's own parsed model (never inflates raw XML), scales the
 * viewport uniformly to fit while preserving aspect ratio, pads the edges, and
 * clips to the viewport box. Fills are drawn before strokes for each path; stroke
 * widths are expressed in viewport units so they scale with the art. Optional
 * [highlightPathIds] draw a constant-width outline so the Edit tab can show which
 * paths are selected.
 */
@Composable
fun VectorPreviewCanvas(
    model: VectorPreviewModel,
    modifier: Modifier = Modifier,
    highlightPathIds: Set<String> = emptySet(),
) {
    val prepared = remember(model) { preparePreviewPaths(model) }
    val highlightColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier) {
        val t = computePreviewTransform(size, model.viewport) ?: return@Canvas
        withTransform({
            translate(t.offsetX, t.offsetY)
            scale(t.scale, t.scale, pivot = Offset.Zero)
            clipRect(0f, 0f, t.viewportWidth, t.viewportHeight)
        }) {
            for (p in prepared) {
                drawPreparedPath(p, alpha = 1f)
                if (p.id in highlightPathIds) {
                    drawPath(
                        path = p.path,
                        color = highlightColor.copy(alpha = 0.9f),
                        style = Stroke(width = HIGHLIGHT_WIDTH_PX / t.scale),
                    )
                }
            }
        }
    }
}

// ---- shared rendering internals (reused by the visual diff overlay) ----

/** A path converted to a Compose [Path] plus its resolved paints. */
internal data class PreparedPreviewPath(
    val id: String,
    val path: Path,
    val fill: Color?,
    val stroke: Color?,
    val strokeWidth: Float,
    val cap: StrokeCap,
    val join: StrokeJoin,
)

/** Uniform fit transform: viewport coords → canvas px, centered with padding. */
internal data class PreviewTransform(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val viewportWidth: Float,
    val viewportHeight: Float,
)

internal fun computePreviewTransform(size: Size, viewport: VectorViewport): PreviewTransform? {
    val vpW = viewport.viewportWidth.takeIf { it > 0f } ?: return null
    val vpH = viewport.viewportHeight.takeIf { it > 0f } ?: return null
    if (size.width <= 0f || size.height <= 0f) return null
    val padding = size.minDimension * PADDING_FRACTION
    val availW = (size.width - 2 * padding).coerceAtLeast(1f)
    val availH = (size.height - 2 * padding).coerceAtLeast(1f)
    val scale = min(availW / vpW, availH / vpH)
    if (scale <= 0f || scale.isNaN()) return null
    val drawnW = vpW * scale; val drawnH = vpH * scale
    return PreviewTransform(
        scale = scale,
        offsetX = (size.width - drawnW) / 2f,
        offsetY = (size.height - drawnH) / 2f,
        viewportWidth = vpW,
        viewportHeight = vpH,
    )
}

internal fun preparePreviewPaths(model: VectorPreviewModel): List<PreparedPreviewPath> =
    model.paths.map { pp ->
        val subpaths = VectorPreviewPathNormalizer.normalize(pp.commands)
        val path = buildComposePath(subpaths).apply {
            fillType = if (pp.style.fillType.equals("evenOdd", ignoreCase = true)) {
                PathFillType.EvenOdd
            } else {
                PathFillType.NonZero
            }
        }
        PreparedPreviewPath(
            id = pp.id,
            path = path,
            fill = parseVectorColor(pp.style.fillColor, pp.style.fillAlpha),
            stroke = parseVectorColor(pp.style.strokeColor, pp.style.strokeAlpha),
            strokeWidth = pp.style.strokeWidth ?: 0f,
            cap = toStrokeCap(pp.style.strokeLineCap),
            join = toStrokeJoin(pp.style.strokeLineJoin),
        )
    }

internal fun buildComposePath(subpaths: List<PreviewSubpath>): Path {
    val path = Path()
    for (sp in subpaths) {
        path.moveTo(sp.startX, sp.startY)
        for (seg in sp.segments) {
            when (seg) {
                is PreviewSegment.Line -> path.lineTo(seg.endX, seg.endY)
                is PreviewSegment.Quad ->
                    path.quadraticBezierTo(seg.cx, seg.cy, seg.endX, seg.endY)
                is PreviewSegment.Cubic ->
                    path.cubicTo(seg.c1x, seg.c1y, seg.c2x, seg.c2y, seg.endX, seg.endY)
            }
        }
        if (sp.closed) path.close()
    }
    return path
}

/** Draws a prepared path: fill first, then stroke. Skips fully transparent paint. */
internal fun DrawScope.drawPreparedPath(p: PreparedPreviewPath, alpha: Float) {
    p.fill?.let { fill ->
        if (fill.alpha > 0f) {
            drawPath(path = p.path, color = fill, alpha = alpha, style = Fill)
        }
    }
    p.stroke?.let { stroke ->
        if (stroke.alpha > 0f && p.strokeWidth > 0f) {
            drawPath(
                path = p.path,
                color = stroke,
                alpha = alpha,
                style = Stroke(width = p.strokeWidth, cap = p.cap, join = p.join),
            )
        }
    }
}

/** Draws an axis-aligned bounds rectangle (in viewport coords) at constant width. */
internal fun DrawScope.drawBoundsBox(bounds: VectorBounds, color: Color, scale: Float) {
    val w = bounds.maxX - bounds.minX
    val h = bounds.maxY - bounds.minY
    if (w <= 0f || h <= 0f) return
    drawRect(
        color = color,
        topLeft = Offset(bounds.minX, bounds.minY),
        size = Size(w, h),
        style = Stroke(width = BOUNDS_WIDTH_PX / scale),
    )
}

// ---- color / style helpers ----

/**
 * Parses `#RGB`, `#ARGB`, `#RRGGBB`, or `#AARRGGBB` into a Compose [Color].
 * Returns null for null/invalid input (the caller skips that paint). An optional
 * [alpha] in 0..1 multiplies the parsed alpha (used for `android:fillAlpha` /
 * `android:strokeAlpha`).
 */
fun parseVectorColor(color: String?, alpha: Float? = null): Color? {
    val raw = color?.trim()?.removePrefix("#") ?: return null
    if (raw.isEmpty() || !raw.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    fun n(c: Char): Int = Character.digit(c, 16)
    val a: Int; val r: Int; val g: Int; val b: Int
    when (raw.length) {
        3 -> { a = 255; r = n(raw[0]) * 17; g = n(raw[1]) * 17; b = n(raw[2]) * 17 }
        4 -> { a = n(raw[0]) * 17; r = n(raw[1]) * 17; g = n(raw[2]) * 17; b = n(raw[3]) * 17 }
        6 -> {
            a = 255
            r = raw.substring(0, 2).toInt(16)
            g = raw.substring(2, 4).toInt(16)
            b = raw.substring(4, 6).toInt(16)
        }
        8 -> {
            a = raw.substring(0, 2).toInt(16)
            r = raw.substring(2, 4).toInt(16)
            g = raw.substring(4, 6).toInt(16)
            b = raw.substring(6, 8).toInt(16)
        }
        else -> return null
    }
    val finalA = if (alpha != null) {
        (a * alpha.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
    } else {
        a
    }
    return Color(red = r, green = g, blue = b, alpha = finalA)
}

/** Maps `android:strokeLineCap` to a Compose [StrokeCap] (Android default: butt). */
fun toStrokeCap(value: String?): StrokeCap = when (value?.lowercase()) {
    "round" -> StrokeCap.Round
    "square" -> StrokeCap.Square
    else -> StrokeCap.Butt
}

/** Maps `android:strokeLineJoin` to a Compose [StrokeJoin] (Android default: miter). */
fun toStrokeJoin(value: String?): StrokeJoin = when (value?.lowercase()) {
    "round" -> StrokeJoin.Round
    "bevel" -> StrokeJoin.Bevel
    else -> StrokeJoin.Miter
}
