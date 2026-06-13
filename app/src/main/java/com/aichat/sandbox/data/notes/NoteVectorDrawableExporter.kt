package com.aichat.sandbox.data.notes

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import com.aichat.sandbox.ui.components.notes.LayerLookup
import com.aichat.sandbox.ui.components.notes.PathCodec
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.aichat.sandbox.ui.components.notes.StrokeOutliner
import com.aichat.sandbox.ui.components.notes.StrokeRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Android VectorDrawable (`<vector>`) export — the format Android Studio drops
 * straight into `res/drawable/`.
 *
 * Unlike SVG ([NoteSvgExporter]) the VectorDrawable schema has only `<path>`
 * and `<group>`, so every shape is flattened to `android:pathData` (the path
 * syntax is the same subset SVG uses, so the stroke smoothing matches the
 * renderer's mid-point quadratics). The drawing is uniformly scaled and
 * centred into a square `sizeDp × sizeDp` viewport so the result imports as a
 * correctly-sized icon.
 *
 * Text and image items have no VectorDrawable equivalent and are skipped; the
 * count is returned in [ExportResult.skippedCount] so the UI can warn.
 *
 * Phase 17.3 — when the caller passes the note's layers, each becomes a
 * `<group android:name="…">` in ordinal order (hidden layers dropped to
 * match the on-screen render). VectorDrawable `<group>` has no opacity
 * attribute, so a layer's opacity is **baked into each path's fill/stroke
 * alpha** instead. With no layers the output stays flat, so pre-17.3
 * fixtures are byte-identical.
 */
@Singleton
class NoteVectorDrawableExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Result of a successful export. */
    data class ExportResult(val uri: Uri, val skippedCount: Int)

    /** Available icon sizes (dp). 24 = Material, 108 = adaptive launcher icon. */
    enum class IconSize(val dp: Int, val label: String) {
        MATERIAL_24(24, "Material (24dp)"),
        MEDIUM_48(48, "Medium (48dp)"),
        ADAPTIVE_108(108, "Adaptive (108dp)"),
    }

    /**
     * Render [note]'s [items] to a VectorDrawable XML file and return a
     * `content://` URI plus the count of skipped (unsupported) items.
     */
    suspend fun exportVectorDrawable(
        note: Note,
        items: List<NoteItem>,
        sizeDp: Int,
        /** Artboard / frame bounds to map into the viewport; null = content bounds. */
        frameBounds: FloatArray? = null,
        /** Phase 15.1 — export variable-width strokes as filled outlines. */
        preservePressure: Boolean = false,
        /** Phase 17.3 — preserve these layers as `<group>`s; empty = flat. */
        layers: List<NoteLayer> = emptyList(),
    ): ExportResult = withContext(Dispatchers.IO) {
        val rendered = render(items, sizeDp, frameBounds, preservePressure, layers)
        val dir = exportsDir().apply { if (!exists()) mkdirs() }
        val outName = "${NoteExporter.sanitizeBaseName(note.title)}-${System.currentTimeMillis()}.xml"
        val finalFile = File(dir, outName)
        val tmpFile = File(dir, "$outName.tmp")
        try {
            FileOutputStream(tmpFile).use { it.write(rendered.xml.toByteArray(Charsets.UTF_8)) }
            if (finalFile.exists()) finalFile.delete()
            check(tmpFile.renameTo(finalFile)) {
                "NoteVectorDrawableExporter: rename ${tmpFile.name} → ${finalFile.name} failed"
            }
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
        NoteExporter.pruneOld(dir, keep = NoteExporter.MAX_KEEP_FILES, extensions = MANAGED_EXTENSIONS)
        val uri = FileProvider.getUriForFile(context, NoteExporter.fileProviderAuthority(context), finalFile)
        ExportResult(uri, rendered.skippedCount)
    }

    private fun exportsDir(): File = File(context.cacheDir, NoteExporter.EXPORTS_SUBDIR)

    companion object {

        val MANAGED_EXTENSIONS: Set<String> = setOf("xml")

        /** Result of [render]: the document plus how many items couldn't be represented. */
        data class Rendered(val xml: String, val skippedCount: Int)

        /**
         * Render a complete VectorDrawable document string. Public for unit
         * tests so the wire format can be pinned without Android `FileProvider`.
         */
        fun renderVectorDrawable(
            items: List<NoteItem>,
            sizeDp: Int,
            frameBounds: FloatArray? = null,
            preservePressure: Boolean = false,
            layers: List<NoteLayer> = emptyList(),
        ): String = render(items, sizeDp, frameBounds, preservePressure, layers).xml

        fun render(
            items: List<NoteItem>,
            sizeDp: Int,
            frameBounds: FloatArray? = null,
            /** Phase 15.1 — export variable-width strokes as filled outlines. */
            preservePressure: Boolean = false,
            /** Phase 17.3 — preserve these layers as `<group>`s; empty = flat. */
            layers: List<NoteLayer> = emptyList(),
        ): Rendered {
            val size = sizeDp.toFloat()
            val src = frameBounds
                ?: NoteRasterizer.computeBounds(items)
                ?: NoteExporter.defaultPaperBounds()
            val visibleItems = if (frameBounds == null) items else items.filter { item ->
                val ib = NoteRasterizer.computeBounds(listOf(item)) ?: return@filter false
                rectsIntersect(ib, frameBounds)
            }.toList()

            val transform = fitTransform(visibleItems, src, size)

            val sb = StringBuilder(1024)
            sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            sb.append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
            sb.append("    android:width=\"").append(sizeDp).append("dp\"\n")
            sb.append("    android:height=\"").append(sizeDp).append("dp\"\n")
            sb.append("    android:viewportWidth=\"").append(sizeDp).append("\"\n")
            sb.append("    android:viewportHeight=\"").append(sizeDp).append("\">\n")

            // Skipped count is kind-based (text + image have no <path>
            // equivalent) and independent of layer grouping, so it matches
            // the flat-render warning path exactly.
            val skipped = visibleItems.count {
                it.kind != NoteItem.KIND_STROKE && it.kind != Shape.KIND && it.kind != PathCodec.KIND
            }
            if (layers.isEmpty()) {
                for (item in visibleItems.sortedBy { it.zIndex }) {
                    appendItem(sb, item, transform, preservePressure, layerAlpha = 1f)
                }
            } else {
                appendLayeredItems(sb, visibleItems, layers, transform, preservePressure)
            }
            sb.append("</vector>\n")
            return Rendered(sb.toString(), skipped)
        }

        /** True for item kinds the VectorDrawable schema can represent. */
        private fun isSupported(item: NoteItem): Boolean =
            item.kind == NoteItem.KIND_STROKE ||
                item.kind == Shape.KIND ||
                item.kind == PathCodec.KIND

        private fun appendItem(
            sb: StringBuilder,
            item: NoteItem,
            transform: Transform,
            preservePressure: Boolean,
            layerAlpha: Float,
        ) {
            when (item.kind) {
                NoteItem.KIND_STROKE -> appendStroke(sb, item, transform, preservePressure, layerAlpha)
                Shape.KIND -> appendShape(sb, item, transform, layerAlpha)
                PathCodec.KIND -> appendBezierPath(sb, item, transform, layerAlpha)
            }
        }

        /**
         * 17.3 — one `<group android:name="…">` per visible layer (ordinal
         * order), items inside by zIndex. VD `<group>` carries no opacity, so
         * the layer's opacity is baked into each path's alpha. Null / dangling
         * `layerId` items fall into a final default group on top. Empty groups
         * are skipped.
         */
        private fun appendLayeredItems(
            sb: StringBuilder,
            visibleItems: List<NoteItem>,
            layers: List<NoteLayer>,
            transform: Transform,
            preservePressure: Boolean,
        ) {
            val lookup = LayerLookup(layers)
            fun emitGroup(name: String, alpha: Float, layerItems: List<NoteItem>) {
                if (layerItems.isEmpty()) return
                sb.append("  <group android:name=\"").append(escapeXmlAttr(name)).append("\">\n")
                for (item in layerItems.sortedWith(compareBy({ it.zIndex }, { it.id }))) {
                    appendItem(sb, item, transform, preservePressure, alpha)
                }
                sb.append("  </group>\n")
            }
            for ((index, layer) in lookup.layers.withIndex()) {
                if (!layer.visible) continue
                emitGroup(
                    name = layer.name.ifBlank { "layer_$index" },
                    alpha = layer.opacityPercent.coerceIn(0, 100) / 100f,
                    layerItems = visibleItems.filter { it.layerId == layer.id && isSupported(it) },
                )
            }
            emitGroup(
                name = "layer_default",
                alpha = 1f,
                layerItems = visibleItems.filter { lookup.get(it.layerId) == null && isSupported(it) },
            )
        }

        /** Escape a string for use inside a double-quoted XML attribute. */
        private fun escapeXmlAttr(s: String): String = buildString(s.length + 8) {
            for (c in s) when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }

        // ---- coordinate fitting ----

        /** Uniform scale + centring offset that fits [src] into a [size]² viewport. */
        private class Transform(
            val scale: Float,
            val minX: Float,
            val minY: Float,
            val offX: Float,
            val offY: Float,
        ) {
            fun x(v: Float) = (v - minX) * scale + offX
            fun y(v: Float) = (v - minY) * scale + offY
            fun len(v: Float) = v * scale
        }

        private fun fitTransform(items: List<NoteItem>, src: FloatArray, size: Float): Transform {
            val srcW = max(1e-3f, src[2] - src[0])
            val srcH = max(1e-3f, src[3] - src[1])
            // Inset by half the widest stroke so edge geometry isn't clipped by
            // its own stroke width. One correction pass against a provisional
            // scale keeps this bounded.
            val provisional = min(size / srcW, size / srcH)
            val maxWidthPx = items.maxOfOrNull { it.baseWidthPx } ?: 0f
            val inset = (maxWidthPx * provisional / 2f).coerceIn(0f, size * 0.25f)
            val avail = max(1f, size - 2f * inset)
            val scale = min(avail / srcW, avail / srcH)
            val contentW = srcW * scale
            val contentH = srcH * scale
            val offX = (size - contentW) / 2f
            val offY = (size - contentH) / 2f
            return Transform(scale, src[0], src[1], offX, offY)
        }

        // ---- strokes ----

        private fun appendStroke(
            sb: StringBuilder,
            item: NoteItem,
            t: Transform,
            preservePressure: Boolean,
            layerAlpha: Float = 1f,
        ) {
            val samples = StrokeCodec.decode(item.payload)
            val count = samples.size / StrokeCodec.FLOATS_PER_SAMPLE
            if (count < 1) return
            if (preservePressure &&
                StrokeOutliner.hasVariableWidth(samples, item.tool, item.baseWidthPx)
            ) {
                appendStrokeOutline(sb, item, samples, t, layerAlpha)
                return
            }
            val s = StrokeCodec.FLOATS_PER_SAMPLE
            fun px(i: Int) = t.x(samples[i])
            fun py(i: Int) = t.y(samples[i + 1])

            val d = StringBuilder()
            if (count == 1) {
                d.append('M').append(f(px(0))).append(',').append(f(py(0)))
            } else if (count == 2) {
                d.append('M').append(f(px(0))).append(',').append(f(py(0)))
                d.append('L').append(f(px(s))).append(',').append(f(py(s)))
            } else {
                d.append('M').append(f(px(0))).append(',').append(f(py(0)))
                d.append('L').append(f((px(0) + px(s)) / 2f)).append(',').append(f((py(0) + py(s)) / 2f))
                for (i in 1 until count - 1) {
                    val ci = i * s; val ni = (i + 1) * s
                    val endX = (px(ci) + px(ni)) / 2f
                    val endY = (py(ci) + py(ni)) / 2f
                    d.append('Q').append(f(px(ci))).append(',').append(f(py(ci)))
                        .append(' ').append(f(endX)).append(',').append(f(endY))
                }
                val lastI = (count - 1) * s
                d.append('L').append(f(px(lastI))).append(',').append(f(py(lastI)))
            }

            val highlighter = item.tool == StrokeRenderer.TOOL_HIGHLIGHTER
            val alpha = if (highlighter) 0.35f else ((item.colorArgb ushr 24) and 0xFF) / 255f
            appendPath(
                sb,
                pathData = d.toString(),
                fillColor = null,
                strokeColor = colorToHex(item.colorArgb),
                strokeWidth = max(0.1f, t.len(strokeMeanWidth(item, samples))),
                strokeAlpha = alpha,
                cap = if (highlighter) "square" else "round",
                layerAlpha = layerAlpha,
            )
        }

        /**
         * Phase 15.1 — variable-width stroke as a single filled outline. The
         * outline is built in world coordinates (so its local thickness tracks
         * [com.aichat.sandbox.ui.components.notes.ToolDynamics] exactly) and
         * then mapped point-by-point through the uniform [Transform].
         */
        private fun appendStrokeOutline(
            sb: StringBuilder,
            item: NoteItem,
            samples: FloatArray,
            t: Transform,
            layerAlpha: Float = 1f,
        ) {
            val outline = StrokeOutliner.outline(samples, item.tool, item.baseWidthPx)
            if (outline.size < 6) return
            var i = 0
            while (i < outline.size) {
                val x = outline[i]
                outline[i] = t.x(x)
                outline[i + 1] = t.y(outline[i + 1])
                i += 2
            }
            val alpha = ((item.colorArgb ushr 24) and 0xFF) / 255f
            appendPath(
                sb,
                pathData = StrokeOutliner.pathData(outline) { f(it) },
                fillColor = colorToHex(item.colorArgb),
                strokeColor = null,
                strokeWidth = 0f,
                fillAlpha = alpha,
                layerAlpha = layerAlpha,
            )
        }

        private fun strokeMeanWidth(item: NoteItem, samples: FloatArray): Float {
            val count = samples.size / StrokeCodec.FLOATS_PER_SAMPLE
            if (count < 1) return item.baseWidthPx
            var sumPressure = 0f
            for (i in 0 until count) sumPressure += samples[i * StrokeCodec.FLOATS_PER_SAMPLE + 2]
            val meanPressure = (sumPressure / count).coerceIn(0.1f, 1f)
            return max(0.5f, item.baseWidthPx * meanPressure)
        }

        // ---- bezier paths (12.5) ----

        private fun appendBezierPath(
            sb: StringBuilder,
            item: NoteItem,
            t: Transform,
            layerAlpha: Float = 1f,
        ) {
            val payload = PathCodec.decode(item.payload)
            if (payload.subpaths.none { it.anchors.size >= 2 }) return
            // 16.1 — every subpath in one android:pathData so holes punch
            // through the shared fill.
            val d = StringBuilder(payload.subpaths.sumOf { it.anchors.size } * 32)
            for (sub in payload.subpaths) {
                val first = sub.anchors.firstOrNull() ?: continue
                d.append('M').append(f(t.x(first.x))).append(',').append(f(t.y(first.y)))
                for (i in 0 until sub.segmentCount) {
                    val s = PathCodec.segment(sub, i)
                    d.append('C')
                        .append(f(t.x(s[2]))).append(',').append(f(t.y(s[3]))).append(' ')
                        .append(f(t.x(s[4]))).append(',').append(f(t.y(s[5]))).append(' ')
                        .append(f(t.x(s[6]))).append(',').append(f(t.y(s[7])))
                }
                if (sub.closed) d.append('Z')
            }
            val filled = payload.anyClosed && payload.fillArgb != 0
            appendPath(
                sb,
                pathData = d.toString(),
                fillColor = if (filled) {
                    colorToHex(payload.fillArgb)
                } else {
                    null
                },
                fillType = if (filled && payload.fillRule == PathCodec.FILL_RULE_EVEN_ODD) {
                    "evenOdd"
                } else {
                    null
                },
                strokeColor = colorToHex(item.colorArgb),
                strokeWidth = max(0.1f, t.len(item.baseWidthPx)),
                cap = when (PathCodec.cap(payload.capJoin)) {
                    PathCodec.CAP_BUTT -> "butt"
                    PathCodec.CAP_SQUARE -> "square"
                    else -> "round"
                },
                join = when (PathCodec.join(payload.capJoin)) {
                    PathCodec.JOIN_MITER -> "miter"
                    PathCodec.JOIN_BEVEL -> "bevel"
                    else -> "round"
                },
                layerAlpha = layerAlpha,
            )
        }

        // ---- shapes ----

        private fun appendShape(
            sb: StringBuilder,
            item: NoteItem,
            t: Transform,
            layerAlpha: Float = 1f,
        ) {
            val decoded = ShapeCodec.decode(item.payload)
            val strokeColor = colorToHex(item.colorArgb)
            val fillColor = if (decoded.fillArgb == 0) null else colorToHex(decoded.fillArgb)
            val strokeWidth = max(0.1f, t.len(item.baseWidthPx))
            when (val shape = decoded.shape) {
                is Shape.Line -> {
                    val d = "M${f(t.x(shape.x0))},${f(t.y(shape.y0))}" +
                        "L${f(t.x(shape.x1))},${f(t.y(shape.y1))}"
                    appendPath(sb, d, fillColor = null, strokeColor = strokeColor,
                        strokeWidth = strokeWidth, layerAlpha = layerAlpha)
                }
                is Shape.Rect -> {
                    appendPath(
                        sb,
                        pathData = rectPath(shape, t),
                        fillColor = fillColor,
                        strokeColor = strokeColor,
                        strokeWidth = strokeWidth,
                        layerAlpha = layerAlpha,
                    )
                }
                is Shape.Ellipse -> {
                    val cx = t.x(shape.cx); val cy = t.y(shape.cy)
                    val rx = t.len(abs(shape.rx)); val ry = t.len(abs(shape.ry))
                    val d = "M${f(cx - rx)},${f(cy)}" +
                        "A${f(rx)},${f(ry)} 0 1 0 ${f(cx + rx)},${f(cy)}" +
                        "A${f(rx)},${f(ry)} 0 1 0 ${f(cx - rx)},${f(cy)}Z"
                    if (shape.rotationRad != 0f) {
                        val deg = Math.toDegrees(shape.rotationRad.toDouble()).toFloat()
                        sb.append("  <group android:rotation=\"").append(f(deg))
                            .append("\" android:pivotX=\"").append(f(cx))
                            .append("\" android:pivotY=\"").append(f(cy)).append("\">\n")
                        appendPath(sb, d, fillColor, strokeColor, strokeWidth,
                            indent = "    ", layerAlpha = layerAlpha)
                        sb.append("  </group>\n")
                    } else {
                        appendPath(sb, d, fillColor, strokeColor, strokeWidth, layerAlpha = layerAlpha)
                    }
                }
                is Shape.Arrow -> {
                    val x0 = t.x(shape.x0); val y0 = t.y(shape.y0)
                    val x1 = t.x(shape.x1); val y1 = t.y(shape.y1)
                    appendPath(sb, "M${f(x0)},${f(y0)}L${f(x1)},${f(y1)}",
                        fillColor = null, strokeColor = strokeColor,
                        strokeWidth = strokeWidth, layerAlpha = layerAlpha)
                    val dx = x1 - x0; val dy = y1 - y0
                    val len = hypot(dx, dy)
                    if (len >= 1e-3f) {
                        val angle = atan2(dy, dx)
                        val headSize = t.len(shape.headSize).coerceAtLeast(strokeWidth * 4f)
                        val ha = Math.PI / 6.0
                        val hx1 = x1 - headSize * cos(angle - ha).toFloat()
                        val hy1 = y1 - headSize * sin(angle - ha).toFloat()
                        val hx2 = x1 - headSize * cos(angle + ha).toFloat()
                        val hy2 = y1 - headSize * sin(angle + ha).toFloat()
                        appendPath(
                            sb,
                            pathData = "M${f(x1)},${f(y1)}L${f(hx1)},${f(hy1)}L${f(hx2)},${f(hy2)}Z",
                            fillColor = strokeColor,
                            strokeColor = null,
                            strokeWidth = 0f,
                            layerAlpha = layerAlpha,
                        )
                    }
                }
                is Shape.Polygon -> {
                    if (shape.points.size < 2) return
                    val d = StringBuilder()
                    d.append('M').append(f(t.x(shape.points[0]))).append(',').append(f(t.y(shape.points[1])))
                    var i = 2
                    while (i < shape.points.size) {
                        d.append('L').append(f(t.x(shape.points[i]))).append(',').append(f(t.y(shape.points[i + 1])))
                        i += 2
                    }
                    if (shape.closed) d.append('Z')
                    appendPath(
                        sb,
                        pathData = d.toString(),
                        fillColor = if (shape.closed) fillColor else null,
                        strokeColor = strokeColor,
                        strokeWidth = strokeWidth,
                        layerAlpha = layerAlpha,
                    )
                }
            }
        }

        private fun rectPath(r: Shape.Rect, t: Transform): String {
            val minX = t.x(r.minX); val minY = t.y(r.minY)
            val maxX = t.x(r.maxX); val maxY = t.y(r.maxY)
            val rad = t.len(r.cornerRadius).coerceIn(0f, min(maxX - minX, maxY - minY) / 2f)
            if (rad <= 0f) {
                return "M${f(minX)},${f(minY)}L${f(maxX)},${f(minY)}" +
                    "L${f(maxX)},${f(maxY)}L${f(minX)},${f(maxY)}Z"
            }
            return buildString {
                append("M${f(minX + rad)},${f(minY)}")
                append("L${f(maxX - rad)},${f(minY)}")
                append("A${f(rad)},${f(rad)} 0 0 1 ${f(maxX)},${f(minY + rad)}")
                append("L${f(maxX)},${f(maxY - rad)}")
                append("A${f(rad)},${f(rad)} 0 0 1 ${f(maxX - rad)},${f(maxY)}")
                append("L${f(minX + rad)},${f(maxY)}")
                append("A${f(rad)},${f(rad)} 0 0 1 ${f(minX)},${f(maxY - rad)}")
                append("L${f(minX)},${f(minY + rad)}")
                append("A${f(rad)},${f(rad)} 0 0 1 ${f(minX + rad)},${f(minY)}Z")
            }
        }

        // ---- low-level emit ----

        private fun appendPath(
            sb: StringBuilder,
            pathData: String,
            fillColor: String?,
            strokeColor: String?,
            strokeWidth: Float,
            strokeAlpha: Float = 1f,
            cap: String? = null,
            indent: String = "  ",
            join: String = "round",
            fillAlpha: Float = 1f,
            fillType: String? = null,
            /** 17.3 — owning layer's opacity, baked in (VD `<group>` has none). */
            layerAlpha: Float = 1f,
        ) {
            // VectorDrawable groups carry no opacity, so fold the layer's
            // opacity into each path's alpha. Defaults to 1f (flat export),
            // keeping the pre-17.3 wire format byte-identical.
            val effFillAlpha = fillAlpha * layerAlpha
            val effStrokeAlpha = strokeAlpha * layerAlpha
            sb.append(indent).append("<path\n")
            sb.append(indent).append("    android:pathData=\"").append(pathData).append("\"\n")
            sb.append(indent).append("    android:fillColor=\"").append(fillColor ?: "#00000000").append("\"")
            if (fillColor != null && effFillAlpha < 1f) {
                sb.append("\n").append(indent).append("    android:fillAlpha=\"").append(f(effFillAlpha)).append("\"")
            }
            if (fillColor != null && fillType != null) {
                sb.append("\n").append(indent).append("    android:fillType=\"").append(fillType).append("\"")
            }
            if (strokeColor != null) {
                sb.append("\n").append(indent).append("    android:strokeColor=\"").append(strokeColor).append("\"")
                sb.append("\n").append(indent).append("    android:strokeWidth=\"").append(f(strokeWidth)).append("\"")
                sb.append("\n").append(indent).append("    android:strokeLineCap=\"").append(cap ?: "round").append("\"")
                sb.append("\n").append(indent).append("    android:strokeLineJoin=\"").append(join).append("\"")
                if (effStrokeAlpha < 1f) {
                    sb.append("\n").append(indent).append("    android:strokeAlpha=\"").append(f(effStrokeAlpha)).append("\"")
                }
            }
            sb.append("/>\n")
        }

        private fun colorToHex(argb: Int): String {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            return "#%02X%02X%02X".format(r, g, b)
        }

        private fun rectsIntersect(a: FloatArray, b: FloatArray): Boolean =
            !(a[2] < b[0] || a[0] > b[2] || a[3] < b[1] || a[1] > b[3])

        /** Format a float without scientific notation, trimming trailing zeros. */
        private fun f(value: Float): String = NoteSvgExporter.fmt(value)
    }
}
