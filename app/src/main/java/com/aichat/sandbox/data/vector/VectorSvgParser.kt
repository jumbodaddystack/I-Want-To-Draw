package com.aichat.sandbox.data.vector

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Parses a safe subset of SVG into a [VectorDocument] (Phase 9).
 *
 * The SVG import front door of the Vector Art Tune-Up pipeline. It reads the
 * `<svg>` root, its `<g>` groups, and the basic shapes (`<path>`, `<rect>`,
 * `<circle>`, `<ellipse>`, `<line>`, `<polyline>`, `<polygon>`), converting every
 * shape into Android-style `pathData` + parsed [PathCommand]s so the rest of the
 * workflow (metrics, preview, optimize, AI, export) works unchanged. Style is
 * read from presentation attributes and inline `style="…"`, with basic
 * fill/stroke inheritance down through `<g>`. Simple `transform`s
 * (translate / scale / rotate) map to [VectorGroup] fields.
 *
 * Everything unsupported (unknown tags, gradients, `currentColor`, `rgb(...)`,
 * complex transforms, missing viewBox) becomes a [VectorWarning] rather than an
 * exception, and malformed XML yields a safe empty document plus a warning.
 *
 * Security: uses the namespace-unaware `javax.xml` DOM API with external DTD and
 * external entity resolution disabled, so it never fetches network resources,
 * expands external entities, resolves URLs, or runs scripts.
 */
object VectorSvgParser {

    private const val DEFAULT_SIZE = 24f

    private class IdGen {
        private var pathSeq = 0
        private var groupSeq = 0
        fun nextPath(): String = "p_" + (++pathSeq).toString().padStart(3, '0')
        fun nextGroup(): String = "g_" + (++groupSeq).toString().padStart(3, '0')
    }

    /** Effective (already-inherited) style values; null fill/stroke means "none". */
    private data class StyleCtx(
        val fillColor: String? = "#000000",
        val fillAlpha: Float? = null,
        val fillType: String? = null,
        val strokeColor: String? = null,
        val strokeAlpha: Float? = null,
        val strokeWidth: Float? = null,
        val strokeLineCap: String? = null,
        val strokeLineJoin: String? = null,
        val strokeMiterLimit: Float? = null,
        val fill: VectorFill? = null,
    )

    fun parse(svg: String): VectorDocument {
        val xmlBytes = svg.toByteArray(Charsets.UTF_8).size
        val warnings = ArrayList<VectorWarning>()

        val rootElement = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                setFeatureQuietly("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setFeatureQuietly("http://xml.org/sax/features/external-general-entities", false)
                setFeatureQuietly("http://xml.org/sax/features/external-parameter-entities", false)
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(svg))).documentElement
        } catch (t: Throwable) {
            return emptyDocument(
                xmlBytes,
                VectorWarning(
                    VectorWarning.Codes.SVG_MALFORMED,
                    "Could not parse SVG: ${t.message ?: t.javaClass.simpleName}",
                ),
            )
        }

        if (rootElement == null || localName(rootElement.tagName) != "svg") {
            return emptyDocument(
                xmlBytes,
                VectorWarning(
                    VectorWarning.Codes.SVG_MALFORMED,
                    "Root element is not <svg> (was <${rootElement?.tagName ?: "none"}>)",
                ),
            )
        }

        val viewport = parseViewport(rootElement, warnings)
        val ids = IdGen()
        // Gradients can be referenced (fill="url(#id)") before they are defined, so
        // collect every <linearGradient>/<radialGradient> up front, keyed by id.
        val gradients = collectGradients(rootElement)
        val rootStyle = applyStyle(StyleCtx(), rootElement, warnings, "root", gradients)
        val children = parseChildren(rootElement, rootStyle, ids, warnings, gradients)

        if (warnings.isNotEmpty()) {
            val count = warnings.size
            warnings += VectorWarning(
                VectorWarning.Codes.SVG_IMPORT_PARTIAL,
                "SVG imported with $count warning(s).",
            )
        }

        return VectorDocument(
            viewport = viewport,
            root = VectorGroup(id = "root", children = children),
            warnings = warnings,
            originalXmlBytes = xmlBytes,
        )
    }

    private fun emptyDocument(xmlBytes: Int, warning: VectorWarning): VectorDocument =
        VectorDocument(
            viewport = VectorViewport(DEFAULT_SIZE, DEFAULT_SIZE, DEFAULT_SIZE, DEFAULT_SIZE),
            root = VectorGroup(id = "root", children = emptyList()),
            warnings = listOf(warning),
            originalXmlBytes = xmlBytes,
        )

    // ---- viewport ----

    private fun parseViewport(el: Element, warnings: MutableList<VectorWarning>): VectorViewport {
        val viewBox = attr(el, "viewBox")?.let { parseViewBox(it) }
        val width = parseLength(attr(el, "width"))
        val height = parseLength(attr(el, "height"))

        if (viewBox == null) {
            warnings += VectorWarning(
                VectorWarning.Codes.SVG_MISSING_VIEWBOX,
                if (width != null && height != null) {
                    "Missing viewBox; inferred from width/height."
                } else {
                    "Missing viewBox and width/height; defaulted to ${DEFAULT_SIZE.toInt()}x${DEFAULT_SIZE.toInt()}."
                },
            )
        }

        val vpW = viewBox?.get(2) ?: width ?: DEFAULT_SIZE
        val vpH = viewBox?.get(3) ?: height ?: DEFAULT_SIZE
        return VectorViewport(
            widthDp = width ?: viewBox?.get(2) ?: DEFAULT_SIZE,
            heightDp = height ?: viewBox?.get(3) ?: DEFAULT_SIZE,
            viewportWidth = vpW,
            viewportHeight = vpH,
        )
    }

    /** Parses "minX minY width height"; returns null when not 4 finite numbers. */
    private fun parseViewBox(raw: String): FloatArray? {
        val parts = raw.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
        if (parts.size != 4) return null
        if (parts[2] <= 0f || parts[3] <= 0f) return null
        return floatArrayOf(parts[0], parts[1], parts[2], parts[3])
    }

    /** Numeric length, stripping a `px` unit. Percentages/other units → null. */
    private fun parseLength(raw: String?): Float? {
        if (raw == null) return null
        val t = raw.trim()
        if (t.endsWith("%")) return null
        val stripped = t.removeSuffix("px")
        return stripped.trim().toFloatOrNull()
    }

    // ---- tree walking ----

    private fun parseChildren(
        parent: Element,
        ctx: StyleCtx,
        ids: IdGen,
        warnings: MutableList<VectorWarning>,
        gradients: Map<String, VectorFill>,
    ): List<VectorNode> {
        val out = ArrayList<VectorNode>()
        val nodes = parent.childNodes
        for (idx in 0 until nodes.length) {
            val node = nodes.item(idx)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            out += parseElement(node as Element, ctx, ids, warnings, gradients)
        }
        return out
    }

    private fun parseElement(
        el: Element,
        ctx: StyleCtx,
        ids: IdGen,
        warnings: MutableList<VectorWarning>,
        gradients: Map<String, VectorFill>,
    ): List<VectorNode> {
        val tag = localName(el.tagName)
        return when (tag) {
            "g" -> listOf(parseGroup(el, ctx, ids, warnings, gradients))
            "path", "rect", "circle", "ellipse", "line", "polyline", "polygon" ->
                parseShape(tag, el, ctx, ids, warnings, gradients)
            // <defs>/gradient definitions are consumed by the up-front
            // collectGradients pass (and referenced via fill="url(#id)"), so they
            // produce no renderable node here and no longer warn.
            "defs", "lineargradient", "radialgradient", "gradient" -> emptyList()
            "image", "use" -> {
                warnings += VectorWarning(
                    VectorWarning.Codes.SVG_EXTERNAL_RESOURCE_IGNORED,
                    "<$tag> references an external/linked resource and was ignored.",
                )
                emptyList()
            }
            "title", "desc", "metadata" -> emptyList()
            else -> {
                warnings += VectorWarning(
                    VectorWarning.Codes.SVG_UNSUPPORTED_TAG,
                    "Unsupported SVG tag <$tag> ignored.",
                )
                emptyList()
            }
        }
    }

    private fun parseGroup(
        el: Element,
        ctx: StyleCtx,
        ids: IdGen,
        warnings: MutableList<VectorWarning>,
        gradients: Map<String, VectorFill>,
    ): VectorNode.GroupNode {
        val childCtx = applyStyle(ctx, el, warnings, el.id(), gradients)
        val transform = attr(el, "transform")?.let { parseTransform(it, warnings, el.id()) }
        val group = VectorGroup(
            id = ids.nextGroup(),
            name = attr(el, "id"),
            rotation = transform?.rotation,
            pivotX = transform?.pivotX,
            pivotY = transform?.pivotY,
            scaleX = transform?.scaleX,
            scaleY = transform?.scaleY,
            translateX = transform?.translateX,
            translateY = transform?.translateY,
            children = parseChildren(el, childCtx, ids, warnings, gradients),
        )
        return VectorNode.GroupNode(group)
    }

    /**
     * Converts a shape element into a [VectorPath]. When the element carries a
     * `transform`, the path is wrapped in a synthetic [VectorGroup] so the
     * transform survives.
     */
    private fun parseShape(
        tag: String,
        el: Element,
        ctx: StyleCtx,
        ids: IdGen,
        warnings: MutableList<VectorWarning>,
        gradients: Map<String, VectorFill>,
    ): List<VectorNode> {
        val style = applyStyle(ctx, el, warnings, el.id(), gradients)
        val pathData = when (tag) {
            "path" -> attr(el, "d") ?: ""
            "rect" -> rectToPath(el)
            "circle" -> circleToPath(el)
            "ellipse" -> ellipseToPath(el)
            "line" -> lineToPath(el)
            "polyline" -> pointsToPath(el, closed = false)
            "polygon" -> pointsToPath(el, closed = true)
            else -> ""
        }

        val pathId = ids.nextPath()
        if (pathData.isBlank()) {
            warnings += VectorWarning(
                VectorWarning.Codes.MISSING_PATH_DATA,
                "<$tag> produced no geometry and was skipped.",
                pathId,
            )
            return emptyList()
        }
        val parsed = PathDataParser.parse(pathData, pathId)
        warnings += parsed.warnings

        val path = VectorPath(
            id = pathId,
            name = attr(el, "id"),
            pathData = pathData,
            commands = parsed.commands.ifEmpty { null },
            style = style.toVectorStyle(),
        )
        val pathNode = VectorNode.PathNode(path)

        val transform = attr(el, "transform")?.let { parseTransform(it, warnings, pathId) }
        if (transform == null || transform.isIdentity) return listOf(pathNode)

        val wrapper = VectorGroup(
            id = ids.nextGroup(),
            rotation = transform.rotation,
            pivotX = transform.pivotX,
            pivotY = transform.pivotY,
            scaleX = transform.scaleX,
            scaleY = transform.scaleY,
            translateX = transform.translateX,
            translateY = transform.translateY,
            children = listOf(pathNode),
        )
        return listOf(VectorNode.GroupNode(wrapper))
    }

    // ---- shape -> path data ----

    private fun rectToPath(el: Element): String {
        val x = num(el, "x") ?: 0f
        val y = num(el, "y") ?: 0f
        val w = num(el, "width") ?: return ""
        val h = num(el, "height") ?: return ""
        if (w <= 0f || h <= 0f) return ""

        var rx = num(el, "rx")
        var ry = num(el, "ry")
        if (rx == null && ry != null) rx = ry
        if (ry == null && rx != null) ry = rx
        if (rx == null || ry == null || rx <= 0f || ry <= 0f) {
            return "M${f(x)},${f(y)} L${f(x + w)},${f(y)} L${f(x + w)},${f(y + h)} L${f(x)},${f(y + h)} Z"
        }
        val crx = rx.coerceAtMost(w / 2f)
        val cry = ry.coerceAtMost(h / 2f)
        return buildString {
            append("M${f(x + crx)},${f(y)} ")
            append("L${f(x + w - crx)},${f(y)} ")
            append("A${f(crx)},${f(cry)} 0 0 1 ${f(x + w)},${f(y + cry)} ")
            append("L${f(x + w)},${f(y + h - cry)} ")
            append("A${f(crx)},${f(cry)} 0 0 1 ${f(x + w - crx)},${f(y + h)} ")
            append("L${f(x + crx)},${f(y + h)} ")
            append("A${f(crx)},${f(cry)} 0 0 1 ${f(x)},${f(y + h - cry)} ")
            append("L${f(x)},${f(y + cry)} ")
            append("A${f(crx)},${f(cry)} 0 0 1 ${f(x + crx)},${f(y)} Z")
        }
    }

    private fun circleToPath(el: Element): String {
        val cx = num(el, "cx") ?: 0f
        val cy = num(el, "cy") ?: 0f
        val r = num(el, "r") ?: return ""
        if (r <= 0f) return ""
        return "M${f(cx - r)},${f(cy)} " +
            "A${f(r)},${f(r)} 0 1 0 ${f(cx + r)},${f(cy)} " +
            "A${f(r)},${f(r)} 0 1 0 ${f(cx - r)},${f(cy)} Z"
    }

    private fun ellipseToPath(el: Element): String {
        val cx = num(el, "cx") ?: 0f
        val cy = num(el, "cy") ?: 0f
        val rx = num(el, "rx") ?: return ""
        val ry = num(el, "ry") ?: return ""
        if (rx <= 0f || ry <= 0f) return ""
        return "M${f(cx - rx)},${f(cy)} " +
            "A${f(rx)},${f(ry)} 0 1 0 ${f(cx + rx)},${f(cy)} " +
            "A${f(rx)},${f(ry)} 0 1 0 ${f(cx - rx)},${f(cy)} Z"
    }

    private fun lineToPath(el: Element): String {
        val x1 = num(el, "x1") ?: 0f
        val y1 = num(el, "y1") ?: 0f
        val x2 = num(el, "x2") ?: 0f
        val y2 = num(el, "y2") ?: 0f
        return "M${f(x1)},${f(y1)} L${f(x2)},${f(y2)}"
    }

    private fun pointsToPath(el: Element, closed: Boolean): String {
        val raw = attr(el, "points") ?: return ""
        val nums = raw.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
        if (nums.size < 4 || nums.size % 2 != 0) return ""
        val sb = StringBuilder()
        var i = 0
        while (i < nums.size) {
            sb.append(if (i == 0) "M" else " L").append(f(nums[i])).append(',').append(f(nums[i + 1]))
            i += 2
        }
        if (closed) sb.append(" Z")
        return sb.toString()
    }

    // ---- style ----

    /**
     * Returns [ctx] overlaid with this element's presentation attributes and
     * inline `style="…"` (inline wins). Unsupported color forms warn and leave
     * the inherited value intact.
     */
    private fun applyStyle(
        ctx: StyleCtx,
        el: Element,
        warnings: MutableList<VectorWarning>,
        nodeId: String?,
        gradients: Map<String, VectorFill>,
    ): StyleCtx {
        val inline = attr(el, "style")?.let { parseInlineStyle(it) } ?: emptyMap()
        fun prop(name: String): String? = inline[name] ?: attr(el, name)

        var result = ctx

        prop("fill")?.let { value ->
            val ref = gradientRef(value)
            if (ref != null) {
                // fill="url(#id)" — resolve against the collected gradients. Keep a
                // sensible scalar fallback (the first stop) for anything that only
                // reads fillColor; VectorFill is the source of truth from here.
                val gradient = gradients[ref]
                if (gradient != null) {
                    result = result.copy(fill = gradient, fillColor = firstStopColor(gradient))
                } else {
                    warnings += VectorWarning(
                        VectorWarning.Codes.SVG_GRADIENT_UNSUPPORTED,
                        "Gradient reference fill ($value) could not be resolved; left default.",
                        nodeId,
                    )
                }
            } else when (val c = parseColor(value)) {
                is ColorResult.None -> result = result.copy(fillColor = null, fill = null)
                is ColorResult.Resolved -> result = result.copy(fillColor = c.android, fill = null)
                is ColorResult.Unsupported -> warnings += unsupportedColor("fill", value, c.gradient, nodeId)
            }
        }
        prop("fill-opacity")?.toFloatOrNull()?.let { result = result.copy(fillAlpha = it) }
        prop("fill-rule")?.let {
            if (it.equals("evenodd", ignoreCase = true)) result = result.copy(fillType = "evenOdd")
        }
        prop("stroke")?.let { value ->
            when (val c = parseColor(value)) {
                is ColorResult.None -> result = result.copy(strokeColor = null)
                is ColorResult.Resolved -> result = result.copy(strokeColor = c.android)
                is ColorResult.Unsupported -> warnings += unsupportedColor("stroke", value, c.gradient, nodeId)
            }
        }
        prop("stroke-opacity")?.toFloatOrNull()?.let { result = result.copy(strokeAlpha = it) }
        prop("stroke-width")?.let { parseLength(it) }?.let { result = result.copy(strokeWidth = it) }
        prop("stroke-linecap")?.let { result = result.copy(strokeLineCap = it.trim()) }
        prop("stroke-linejoin")?.let { result = result.copy(strokeLineJoin = it.trim()) }
        prop("stroke-miterlimit")?.toFloatOrNull()?.let { result = result.copy(strokeMiterLimit = it) }

        return result
    }

    private fun unsupportedColor(
        property: String,
        value: String,
        gradient: Boolean,
        nodeId: String?,
    ): VectorWarning = if (gradient) {
        VectorWarning(
            VectorWarning.Codes.SVG_GRADIENT_UNSUPPORTED,
            "Gradient $property ($value) is not imported; left default.",
            nodeId,
        )
    } else {
        VectorWarning(
            VectorWarning.Codes.SVG_STYLE_UNSUPPORTED,
            "Unsupported $property color ($value); left default.",
            nodeId,
        )
    }

    private fun StyleCtx.toVectorStyle(): VectorStyle = VectorStyle(
        fillColor = fillColor,
        fillAlpha = fillAlpha,
        fillType = fillType,
        strokeColor = strokeColor,
        strokeAlpha = strokeAlpha,
        // SVG default stroke-width is 1 when a stroke is set but no width is given.
        strokeWidth = if (strokeColor != null) (strokeWidth ?: 1f) else strokeWidth,
        strokeLineCap = strokeLineCap,
        strokeLineJoin = strokeLineJoin,
        strokeMiterLimit = strokeMiterLimit,
        fill = fill,
    )

    private fun parseInlineStyle(style: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (decl in style.split(';')) {
            val idx = decl.indexOf(':')
            if (idx <= 0) continue
            val key = decl.substring(0, idx).trim().lowercase()
            val value = decl.substring(idx + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) map[key] = value
        }
        return map
    }

    private sealed interface ColorResult {
        data object None : ColorResult
        data class Resolved(val android: String) : ColorResult
        data class Unsupported(val gradient: Boolean) : ColorResult
    }

    /**
     * Maps an SVG color value to an Android color literal. Supports `none`, hex
     * (#RGB/#RGBA/#RRGGBB/#RRGGBBAA), and a small set of named colors.
     * `currentColor`, `rgb(...)`, `url(#…)` gradients, and unknown names are
     * reported as [ColorResult.Unsupported].
     */
    private fun parseColor(raw: String): ColorResult {
        val v = raw.trim()
        if (v.isEmpty()) return ColorResult.Unsupported(gradient = false)
        if (v.equals("none", ignoreCase = true) || v.equals("transparent", ignoreCase = true)) {
            return ColorResult.None
        }
        if (v.startsWith("url(", ignoreCase = true)) return ColorResult.Unsupported(gradient = true)
        if (v.equals("currentColor", ignoreCase = true)) return ColorResult.Unsupported(gradient = false)
        if (v.startsWith("rgb", ignoreCase = true) || v.startsWith("hsl", ignoreCase = true)) {
            return ColorResult.Unsupported(gradient = false)
        }
        if (v.startsWith("#")) {
            val hex = v.removePrefix("#")
            return when (hex.length) {
                3 -> if (hex.all { it.isHex() }) ColorResult.Resolved("#" + expandHex(hex)) else ColorResult.Unsupported(false)
                6 -> if (hex.all { it.isHex() }) ColorResult.Resolved("#" + hex.uppercase()) else ColorResult.Unsupported(false)
                4 -> if (hex.all { it.isHex() }) {
                    // #RGBA -> #AARRGGBB
                    val r = hex[0]; val g = hex[1]; val b = hex[2]; val a = hex[3]
                    ColorResult.Resolved("#" + ("$a$a$r$r$g$g$b$b").uppercase())
                } else ColorResult.Unsupported(false)
                8 -> if (hex.all { it.isHex() }) {
                    // #RRGGBBAA -> #AARRGGBB
                    val rgb = hex.substring(0, 6); val aa = hex.substring(6, 8)
                    ColorResult.Resolved("#" + (aa + rgb).uppercase())
                } else ColorResult.Unsupported(false)
                else -> ColorResult.Unsupported(false)
            }
        }
        return NAMED_COLORS[v.lowercase()]?.let { ColorResult.Resolved(it) }
            ?: ColorResult.Unsupported(gradient = false)
    }

    // ---- gradients ----

    /**
     * Collects every `<linearGradient>`/`<radialGradient>` in the document into a
     * map keyed by `id`, so a `fill="url(#id)"` can be resolved regardless of
     * whether the gradient is defined before or after the shape that uses it.
     */
    private fun collectGradients(root: Element): Map<String, VectorFill> {
        val map = HashMap<String, VectorFill>()
        fun walk(el: Element) {
            val tag = localName(el.tagName)
            if (tag == "lineargradient" || tag == "radialgradient") {
                attr(el, "id")?.let { id -> parseSvgGradient(tag, el)?.let { map[id] = it } }
            }
            val nodes = el.childNodes
            for (i in 0 until nodes.length) {
                val n = nodes.item(i)
                if (n.nodeType == Node.ELEMENT_NODE) walk(n as Element)
            }
        }
        walk(root)
        return map
    }

    private fun parseSvgGradient(tag: String, el: Element): VectorFill? {
        val stops = elementChildren(el)
            .filter { localName(it.tagName) == "stop" }
            .map { stopEl ->
                val style = attr(stopEl, "style")?.let { parseInlineStyle(it) } ?: emptyMap()
                fun sp(name: String): String? = style[name] ?: attr(stopEl, name)
                GradientStop(parseOffset(sp("offset")), svgStopColor(sp("stop-color"), sp("stop-opacity")))
            }
        if (stops.isEmpty()) return null
        return if (tag == "radialgradient") {
            VectorFill.Radial(
                cx = num(el, "cx") ?: 0f, cy = num(el, "cy") ?: 0f,
                radius = num(el, "r") ?: 0f, stops = stops,
            )
        } else {
            VectorFill.Linear(
                x1 = num(el, "x1") ?: 0f, y1 = num(el, "y1") ?: 0f,
                x2 = num(el, "x2") ?: 0f, y2 = num(el, "y2") ?: 0f, stops = stops,
            )
        }
    }

    /** Extracts the bare id from a `url(#id)` reference, or null when not a ref. */
    private fun gradientRef(value: String): String? {
        val v = value.trim()
        if (!v.startsWith("url(", ignoreCase = true)) return null
        val inside = v.substringAfter('(').substringBefore(')').trim().trim('"', '\'')
        return inside.removePrefix("#").takeIf { it.isNotEmpty() }
    }

    private fun firstStopColor(fill: VectorFill): String? = when (fill) {
        is VectorFill.Linear -> fill.stops.firstOrNull()?.color
        is VectorFill.Radial -> fill.stops.firstOrNull()?.color
        is VectorFill.Sweep -> fill.stops.firstOrNull()?.color
        is VectorFill.Solid -> fill.color
    }

    /** Parses a stop offset ("50%" or "0.5") into a 0..1 fraction. */
    private fun parseOffset(raw: String?): Float {
        val t = raw?.trim() ?: return 0f
        return if (t.endsWith("%")) (t.dropLast(1).toFloatOrNull() ?: 0f) / 100f
        else t.toFloatOrNull() ?: 0f
    }

    /** Combines an SVG `stop-color` + optional `stop-opacity` into an `#AARRGGBB`/`#RRGGBB`. */
    private fun svgStopColor(colorRaw: String?, opacityRaw: String?): String {
        val base = colorRaw?.let { (parseColor(it) as? ColorResult.Resolved)?.android } ?: "#000000"
        val opacity = opacityRaw?.toFloatOrNull() ?: return base
        val hex = base.removePrefix("#")
        val existingA: Int
        val rgb: String
        when (hex.length) {
            8 -> { existingA = hex.substring(0, 2).toInt(16); rgb = hex.substring(2) }
            6 -> { existingA = 255; rgb = hex }
            else -> { existingA = 255; rgb = "000000" }
        }
        val a = (existingA * opacity.coerceIn(0f, 1f) + 0.5f).toInt().coerceIn(0, 255)
        return "#" + a.toString(16).padStart(2, '0').uppercase() + rgb.uppercase()
    }

    private fun elementChildren(el: Element): List<Element> {
        val out = ArrayList<Element>()
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) out += n as Element
        }
        return out
    }

    // ---- transforms ----

    private data class Transform(
        val translateX: Float? = null,
        val translateY: Float? = null,
        val scaleX: Float? = null,
        val scaleY: Float? = null,
        val rotation: Float? = null,
        val pivotX: Float? = null,
        val pivotY: Float? = null,
    ) {
        val isIdentity: Boolean
            get() = translateX == null && translateY == null && scaleX == null &&
                scaleY == null && rotation == null
    }

    /**
     * Parses a small subset of the SVG `transform` grammar — `translate`,
     * `scale`, `rotate` (with optional pivot) — into [VectorGroup]-compatible
     * fields. Unsupported functions (matrix/skew) or a repeated function warn and
     * are dropped; the recognized parts are still applied (best effort).
     */
    private fun parseTransform(
        raw: String,
        warnings: MutableList<VectorWarning>,
        nodeId: String?,
    ): Transform {
        var result = Transform()
        var seenTranslate = false
        var seenScale = false
        var seenRotate = false
        var partial = false

        val regex = Regex("([a-zA-Z]+)\\s*\\(([^)]*)\\)")
        var matched = false
        for (m in regex.findAll(raw)) {
            matched = true
            val fn = m.groupValues[1].lowercase()
            val args = m.groupValues[2].trim().split(Regex("[\\s,]+"))
                .mapNotNull { it.toFloatOrNull() }
            when (fn) {
                "translate" -> {
                    if (seenTranslate) { partial = true } else {
                        seenTranslate = true
                        val tx = args.getOrNull(0) ?: 0f
                        val ty = args.getOrNull(1) ?: 0f
                        result = result.copy(translateX = tx, translateY = ty)
                    }
                }
                "scale" -> {
                    if (seenScale) { partial = true } else {
                        seenScale = true
                        val sx = args.getOrNull(0) ?: 1f
                        val sy = args.getOrNull(1) ?: sx
                        result = result.copy(scaleX = sx, scaleY = sy)
                    }
                }
                "rotate" -> {
                    if (seenRotate) { partial = true } else {
                        seenRotate = true
                        val angle = args.getOrNull(0) ?: 0f
                        val cx = args.getOrNull(1)
                        val cy = args.getOrNull(2)
                        result = result.copy(rotation = angle, pivotX = cx, pivotY = cy)
                    }
                }
                else -> partial = true // matrix, skewX, skewY, …
            }
        }

        if (!matched || partial) {
            warnings += VectorWarning(
                VectorWarning.Codes.SVG_TRANSFORM_UNSUPPORTED,
                "Transform \"$raw\" only partially supported; applied translate/scale/rotate where possible.",
                nodeId,
            )
        }
        return result
    }

    // ---- helpers ----

    private fun attr(el: Element, name: String): String? =
        if (el.hasAttribute(name)) el.getAttribute(name) else null

    private fun num(el: Element, name: String): Float? = parseLength(attr(el, name))

    private fun Element.id(): String? = attr(this, "id")

    private fun localName(tagName: String): String = tagName.substringAfterLast(':').lowercase()

    private fun Char.isHex(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun expandHex(rgb: String): String = buildString {
        for (c in rgb) append(c).append(c)
    }.uppercase()

    /** Float formatting shared with the rest of the pipeline. */
    private fun f(value: Float): String = PathDataFormatter.num(value)

    private fun DocumentBuilderFactory.setFeatureQuietly(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: Throwable) {
            // Not all parser implementations support every feature; ignore.
        }
    }

    private val NAMED_COLORS: Map<String, String> = mapOf(
        "black" to "#000000",
        "white" to "#FFFFFF",
        "red" to "#FF0000",
        "green" to "#008000",
        "lime" to "#00FF00",
        "blue" to "#0000FF",
        "yellow" to "#FFFF00",
        "cyan" to "#00FFFF",
        "aqua" to "#00FFFF",
        "magenta" to "#FF00FF",
        "fuchsia" to "#FF00FF",
        "gray" to "#808080",
        "grey" to "#808080",
        "silver" to "#C0C0C0",
        "maroon" to "#800000",
        "olive" to "#808000",
        "navy" to "#000080",
        "teal" to "#008080",
        "purple" to "#800080",
        "orange" to "#FFA500",
    )
}
