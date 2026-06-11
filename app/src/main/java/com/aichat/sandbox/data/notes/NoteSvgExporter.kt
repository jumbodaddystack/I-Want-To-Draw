package com.aichat.sandbox.data.notes

import android.content.Context
import android.graphics.Color
import android.net.Uri
import androidx.core.content.FileProvider
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.ImageItemCodec
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.ShapeRenderer
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.aichat.sandbox.ui.components.notes.StrokeRenderer
import com.aichat.sandbox.ui.components.notes.TextItemCodec
import com.aichat.sandbox.ui.components.notes.TextItemRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Base64 as AndroidBase64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Phase 6.8 — SVG export.
 *
 * Strokes serialize as quadratic-Bezier paths matching the renderer's
 * mid-point smoothing; shapes emit native SVG primitives; text items
 * become `<text>` runs. Layers are not yet a first-class concept (Phase
 * 6.4 deferred); for now everything lives in a single `<g>` group with
 * z-order preserved.
 *
 * Stroke widths are reduced to a single mean per stroke because SVG `path`
 * does not support per-segment widths. This is a documented lossy step:
 * pressure-modulated strokes flatten to their average width.
 */
@Singleton
class NoteSvgExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Render [note]'s [items] to an SVG file and return a `content://` URI. */
    suspend fun exportSvg(
        note: Note,
        items: List<NoteItem>,
        /** Sub-phase 8.1 — if non-null, bound the viewBox + content to this frame. */
        frameBounds: FloatArray? = null,
    ): Uri = withContext(Dispatchers.IO) {
        val svg = renderSvg(note, items, context.filesDir, frameBounds)
        val dir = exportsDir().apply { if (!exists()) mkdirs() }
        val outName = "${NoteExporter.sanitizeBaseName(note.title)}-${System.currentTimeMillis()}.svg"
        val finalFile = File(dir, outName)
        val tmpFile = File(dir, "$outName.tmp")
        try {
            FileOutputStream(tmpFile).use { it.write(svg.toByteArray(Charsets.UTF_8)) }
            if (finalFile.exists()) finalFile.delete()
            check(tmpFile.renameTo(finalFile)) {
                "NoteSvgExporter: rename ${tmpFile.name} → ${finalFile.name} failed"
            }
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
        NoteExporter.pruneOld(dir, keep = NoteExporter.MAX_KEEP_FILES,
            extensions = MANAGED_EXTENSIONS)
        FileProvider.getUriForFile(context, NoteExporter.fileProviderAuthority(context), finalFile)
    }

    private fun exportsDir(): File = File(context.cacheDir, NoteExporter.EXPORTS_SUBDIR)

    companion object {

        val MANAGED_EXTENSIONS: Set<String> = setOf("svg")

        /** World-units of padding added around geometry bounds for the viewBox. */
        const val MARGIN_WORLD: Float = 24f

        /**
         * Render a complete SVG document string. Public for unit tests so
         * exporters can pin the wire format with golden fixtures without
         * touching Android `FileProvider`.
         */
        fun renderSvg(
            note: Note,
            items: List<NoteItem>,
            filesDir: java.io.File? = null,
            /** Sub-phase 8.1 — bound viewBox and item filter to this frame. */
            frameBounds: FloatArray? = null,
        ): String {
            val baseBounds = frameBounds
                ?: NoteRasterizer.computeBounds(items)
                ?: NoteExporter.defaultPaperBounds()
            val visibleItems = if (frameBounds == null) items else items.filter { item ->
                val ib = NoteRasterizer.computeBounds(listOf(item)) ?: return@filter false
                rectsIntersect(ib, frameBounds)
            }
            // Frame exports skip the outer paper margin so the SVG viewBox is
            // exactly the frame rect (matches the PNG / PDF exports).
            val margin = if (frameBounds == null) MARGIN_WORLD else 0f
            val minX = baseBounds[0] - margin
            val minY = baseBounds[1] - margin
            val width = max(1f, baseBounds[2] - baseBounds[0] + 2 * margin)
            val height = max(1f, baseBounds[3] - baseBounds[1] + 2 * margin)
            val sb = StringBuilder(2048)
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n")
            sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" ")
                .append("viewBox=\"")
                .append(fmt(minX)).append(' ').append(fmt(minY)).append(' ')
                .append(fmt(width)).append(' ').append(fmt(height))
                .append("\" ")
                .append("width=\"").append(fmt(width)).append("\" ")
                .append("height=\"").append(fmt(height)).append("\">\n")
            // Paper background — keeps the export visually consistent with PNG/PDF.
            sb.append("  <rect x=\"").append(fmt(minX)).append("\" y=\"").append(fmt(minY))
                .append("\" width=\"").append(fmt(width)).append("\" height=\"")
                .append(fmt(height)).append("\" fill=\"#FFFFFF\"/>\n")
            sb.append("  <g id=\"items\">\n")
            for (item in visibleItems.sortedBy { it.zIndex }) {
                when (item.kind) {
                    "stroke" -> appendStroke(sb, item)
                    Shape.KIND -> appendShape(sb, item)
                    TextItemCodec.KIND -> appendText(sb, item)
                    NoteItem.KIND_IMAGE -> appendImage(sb, item, filesDir)
                }
            }
            sb.append("  </g>\n</svg>\n")
            return sb.toString()
        }

        private fun appendStroke(sb: StringBuilder, item: NoteItem) {
            val samples = StrokeCodec.decode(item.payload)
            val count = samples.size / StrokeCodec.FLOATS_PER_SAMPLE
            if (count < 1) return
            val s = StrokeCodec.FLOATS_PER_SAMPLE
            val color = colorToHex(item.colorArgb)
            val alpha = Color.alpha(item.colorArgb) / 255f
            val width = strokeMeanWidth(item)
            sb.append("    <path d=\"")
            if (count == 1) {
                sb.append('M').append(fmt(samples[0])).append(' ').append(fmt(samples[1]))
            } else if (count == 2) {
                sb.append('M').append(fmt(samples[0])).append(' ').append(fmt(samples[1]))
                sb.append('L').append(fmt(samples[s])).append(' ').append(fmt(samples[s + 1]))
            } else {
                sb.append('M').append(fmt(samples[0])).append(' ').append(fmt(samples[1]))
                val mid01x = (samples[0] + samples[s]) * 0.5f
                val mid01y = (samples[1] + samples[s + 1]) * 0.5f
                sb.append('L').append(fmt(mid01x)).append(' ').append(fmt(mid01y))
                for (i in 1 until count - 1) {
                    val ci = i * s; val ni = (i + 1) * s
                    val endX = (samples[ci] + samples[ni]) * 0.5f
                    val endY = (samples[ci + 1] + samples[ni + 1]) * 0.5f
                    sb.append('Q').append(fmt(samples[ci])).append(' ').append(fmt(samples[ci + 1]))
                        .append(' ').append(fmt(endX)).append(' ').append(fmt(endY))
                }
                val lastI = (count - 1) * s
                sb.append('L').append(fmt(samples[lastI])).append(' ').append(fmt(samples[lastI + 1]))
            }
            sb.append("\" fill=\"none\" stroke=\"").append(color)
                .append("\" stroke-width=\"").append(fmt(width))
                .append("\" stroke-linecap=\"")
                .append(if (item.tool == StrokeRenderer.TOOL_HIGHLIGHTER) "square" else "round")
                .append("\" stroke-linejoin=\"round\"")
            if (alpha < 1f) sb.append(" stroke-opacity=\"").append(fmt(alpha)).append('\"')
            if (item.tool == StrokeRenderer.TOOL_HIGHLIGHTER) {
                sb.append(" stroke-opacity=\"0.35\"")
            }
            sb.append("/>\n")
        }

        private fun appendShape(sb: StringBuilder, item: NoteItem) {
            val decoded = ShapeCodec.decode(item.payload)
            val color = colorToHex(item.colorArgb)
            val fill = if (decoded.fillArgb == 0) "none" else colorToHex(decoded.fillArgb)
            val width = item.baseWidthPx
            // Phase 10.3 — mirror the renderer's width-scaled dash pattern.
            val dash = dashArrayFor(decoded.strokeStyle, width)
            when (val s = decoded.shape) {
                is Shape.Line -> {
                    sb.append("    <line x1=\"").append(fmt(s.x0))
                        .append("\" y1=\"").append(fmt(s.y0))
                        .append("\" x2=\"").append(fmt(s.x1))
                        .append("\" y2=\"").append(fmt(s.y1))
                        .append("\" stroke=\"").append(color)
                        .append("\" stroke-width=\"").append(fmt(width))
                        .append('"')
                    if (dash != null) sb.append(" stroke-dasharray=\"").append(dash).append('"')
                    sb.append(" stroke-linecap=\"round\"/>\n")
                }
                is Shape.Rect -> {
                    sb.append("    <rect x=\"").append(fmt(s.minX))
                        .append("\" y=\"").append(fmt(s.minY))
                        .append("\" width=\"").append(fmt(s.maxX - s.minX))
                        .append("\" height=\"").append(fmt(s.maxY - s.minY))
                        .append('"')
                    if (s.cornerRadius > 0f) {
                        sb.append(" rx=\"").append(fmt(s.cornerRadius)).append('"')
                    }
                    sb.append(" fill=\"").append(fill).append('"')
                        .append(" stroke=\"").append(color).append('"')
                        .append(" stroke-width=\"").append(fmt(width)).append('"')
                    if (dash != null) sb.append(" stroke-dasharray=\"").append(dash).append('"')
                    sb.append("/>\n")
                }
                is Shape.Ellipse -> {
                    sb.append("    <ellipse cx=\"").append(fmt(s.cx))
                        .append("\" cy=\"").append(fmt(s.cy))
                        .append("\" rx=\"").append(fmt(s.rx))
                        .append("\" ry=\"").append(fmt(s.ry))
                        .append('"')
                    if (s.rotationRad != 0f) {
                        val deg = Math.toDegrees(s.rotationRad.toDouble()).toFloat()
                        sb.append(" transform=\"rotate(")
                            .append(fmt(deg)).append(' ')
                            .append(fmt(s.cx)).append(' ')
                            .append(fmt(s.cy)).append(")\"")
                    }
                    sb.append(" fill=\"").append(fill).append('"')
                        .append(" stroke=\"").append(color).append('"')
                        .append(" stroke-width=\"").append(fmt(width)).append('"')
                    if (dash != null) sb.append(" stroke-dasharray=\"").append(dash).append('"')
                    sb.append("/>\n")
                }
                is Shape.Arrow -> {
                    // Line + filled triangle head.
                    sb.append("    <line x1=\"").append(fmt(s.x0))
                        .append("\" y1=\"").append(fmt(s.y0))
                        .append("\" x2=\"").append(fmt(s.x1))
                        .append("\" y2=\"").append(fmt(s.y1))
                        .append("\" stroke=\"").append(color)
                        .append("\" stroke-width=\"").append(fmt(width)).append('"')
                    if (dash != null) sb.append(" stroke-dasharray=\"").append(dash).append('"')
                    sb.append("/>\n")
                    val dx = s.x1 - s.x0
                    val dy = s.y1 - s.y0
                    val len = kotlin.math.hypot(dx, dy)
                    if (len >= 1e-3f) {
                        val angle = kotlin.math.atan2(dy, dx)
                        val headSize = s.headSize.coerceAtLeast(width * 4f)
                        val headAngle = Math.PI / 6.0
                        val hx1 = s.x1 - headSize * kotlin.math.cos(angle - headAngle).toFloat()
                        val hy1 = s.y1 - headSize * kotlin.math.sin(angle - headAngle).toFloat()
                        val hx2 = s.x1 - headSize * kotlin.math.cos(angle + headAngle).toFloat()
                        val hy2 = s.y1 - headSize * kotlin.math.sin(angle + headAngle).toFloat()
                        sb.append("    <polygon points=\"")
                            .append(fmt(s.x1)).append(',').append(fmt(s.y1)).append(' ')
                            .append(fmt(hx1)).append(',').append(fmt(hy1)).append(' ')
                            .append(fmt(hx2)).append(',').append(fmt(hy2))
                            .append("\" fill=\"").append(color).append("\"/>\n")
                    }
                }
                is Shape.Polygon -> {
                    val tag = if (s.closed) "polygon" else "polyline"
                    sb.append("    <").append(tag).append(" points=\"")
                    var i = 0
                    while (i < s.points.size) {
                        if (i > 0) sb.append(' ')
                        sb.append(fmt(s.points[i])).append(',').append(fmt(s.points[i + 1]))
                        i += 2
                    }
                    sb.append('"').append(" fill=\"")
                        .append(if (s.closed) fill else "none").append('"')
                        .append(" stroke=\"").append(color).append('"')
                        .append(" stroke-width=\"").append(fmt(width)).append('"')
                    if (dash != null) sb.append(" stroke-dasharray=\"").append(dash).append('"')
                    sb.append("/>\n")
                }
            }
        }

        /**
         * SVG `stroke-dasharray` matching [ShapeRenderer]'s width-scaled
         * [android.graphics.DashPathEffect] patterns; null for solid.
         */
        private fun dashArrayFor(strokeStyle: Byte, width: Float): String? = when (strokeStyle) {
            ShapeCodec.STROKE_STYLE_DASHED ->
                "${fmt(width * ShapeRenderer.DASH_ON_FACTOR)} ${fmt(width * ShapeRenderer.DASH_OFF_FACTOR)}"
            ShapeCodec.STROKE_STYLE_DOTTED ->
                "${fmt(width * ShapeRenderer.DOT_ON_FACTOR)} ${fmt(width * ShapeRenderer.DOT_OFF_FACTOR)}"
            else -> null
        }

        private fun appendImage(sb: StringBuilder, item: NoteItem, filesDir: java.io.File?) {
            val payload = ImageItemCodec.decode(item.payload)
            val width = payload.maxX - payload.minX
            val height = payload.maxY - payload.minY
            val href = filesDir?.let { dir ->
                val file = java.io.File(dir, payload.relativePath)
                if (!file.exists()) null
                else try {
                    val bytes = file.readBytes()
                    val mime = when (file.extension.lowercase()) {
                        "jpg", "jpeg" -> "image/jpeg"
                        "webp" -> "image/webp"
                        "gif" -> "image/gif"
                        else -> "image/png"
                    }
                    "data:$mime;base64," + AndroidBase64.encodeToString(bytes, AndroidBase64.NO_WRAP)
                } catch (_: Throwable) { null }
            }
            sb.append("    <image x=\"").append(fmt(payload.minX))
                .append("\" y=\"").append(fmt(payload.minY))
                .append("\" width=\"").append(fmt(width))
                .append("\" height=\"").append(fmt(height)).append('"')
            if (payload.rotationRad != 0f) {
                val deg = Math.toDegrees(payload.rotationRad.toDouble()).toFloat()
                val cx = (payload.minX + payload.maxX) * 0.5f
                val cy = (payload.minY + payload.maxY) * 0.5f
                sb.append(" transform=\"rotate(").append(fmt(deg)).append(' ')
                    .append(fmt(cx)).append(' ').append(fmt(cy)).append(")\"")
            }
            if (href != null) {
                sb.append(" href=\"").append(href).append('"')
            }
            sb.append("/>\n")
        }

        private fun appendText(sb: StringBuilder, item: NoteItem) {
            val decoded = TextItemCodec.decode(item.payload)
            val color = colorToHex(item.colorArgb)
            // Use the matrix's translation as the anchor — full affines
            // become `transform="matrix(…)"` for fidelity.
            val anchor = when (decoded.alignment) {
                TextItemCodec.ALIGN_CENTER -> "middle"
                TextItemCodec.ALIGN_RIGHT -> "end"
                else -> "start"
            }
            // The text body may include newlines; split into <tspan> rows
            // so multi-line labels round-trip.
            val lines = decoded.body.split('\n')
            val lineHeight = decoded.fontSize * 1.2f
            sb.append("    <text x=\"").append(fmt(decoded.matrix[2]))
                .append("\" y=\"").append(fmt(decoded.matrix[5] + decoded.fontSize))
                .append("\" fill=\"").append(color).append('"')
                .append(" font-family=\"sans-serif\"")
                .append(" font-size=\"").append(fmt(decoded.fontSize)).append('"')
                .append(" text-anchor=\"").append(anchor).append("\">\n")
            for ((i, line) in lines.withIndex()) {
                sb.append("      <tspan x=\"").append(fmt(decoded.matrix[2]))
                    .append("\" dy=\"").append(fmt(if (i == 0) 0f else lineHeight)).append("\">")
                sb.append(escapeXml(line))
                sb.append("</tspan>\n")
            }
            sb.append("    </text>\n")
        }

        private fun strokeMeanWidth(item: NoteItem): Float {
            // Pressure-modulated strokes flatten to mean width — SVG path
            // is single-stroke-width. Documented lossy step in Phase 6.8.
            val samples = StrokeCodec.decode(item.payload)
            val count = samples.size / StrokeCodec.FLOATS_PER_SAMPLE
            if (count < 1) return item.baseWidthPx
            var sumPressure = 0f
            for (i in 0 until count) {
                sumPressure += samples[i * StrokeCodec.FLOATS_PER_SAMPLE + 2]
            }
            val meanPressure = (sumPressure / count).coerceIn(0.1f, 1f)
            return max(0.5f, item.baseWidthPx * meanPressure)
        }

        private fun colorToHex(argb: Int): String {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return "#%02X%02X%02X".format(r, g, b)
        }

        private fun escapeXml(s: String): String = buildString(s.length + 8) {
            for (c in s) {
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(c)
                }
            }
        }

        private fun rectsIntersect(a: FloatArray, b: FloatArray): Boolean =
            !(a[2] < b[0] || a[0] > b[2] || a[3] < b[1] || a[1] > b[3])

        /** Format a float without scientific notation, trimming trailing zeros. */
        internal fun fmt(value: Float): String {
            if (value.isNaN() || value.isInfinite()) return "0"
            // Quantise to 3 decimal places — sub-pixel precision lost to
            // SVG file size is the right tradeoff.
            val rounded = (value * 1000f).roundToInt() / 1000f
            return if (rounded == rounded.toInt().toFloat()) {
                rounded.toInt().toString()
            } else {
                rounded.toString()
            }
        }

    }
}
