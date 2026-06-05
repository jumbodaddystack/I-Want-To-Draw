package com.aichat.sandbox.data.vector

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/**
 * Parses Android `VectorDrawable` XML into a [VectorDocument].
 *
 * The import front door of the Vector Art Tune-Up pipeline. It reads the
 * `<vector>` root, its `<group>`/`<path>` descendants, and the standard sizing,
 * transform, and fill/stroke attributes, attaching parsed [PathCommand]s to
 * each path. Anything unsupported (unknown tags, gradients, clip paths,
 * malformed path data, missing viewport fields) becomes a [VectorWarning]
 * instead of an exception, and even completely malformed XML yields a safe
 * empty document plus a warning.
 *
 * It uses the JDK/Android-shared `javax.xml` DOM API (non-namespace-aware) so
 * the same code runs under plain JVM unit tests, and avoids Android framework
 * XML classes. External entity resolution is disabled to guard against XXE.
 */
object AndroidVectorDrawableParser {

    private class IdGen {
        private var pathSeq = 0
        private var groupSeq = 0
        fun nextPath(): String = "p_" + (++pathSeq).toString().padStart(3, '0')
        fun nextGroup(): String = "g_" + (++groupSeq).toString().padStart(3, '0')
    }

    fun parse(xml: String): VectorDocument {
        val xmlBytes = xml.toByteArray(Charsets.UTF_8).size
        val warnings = ArrayList<VectorWarning>()

        val rootElement = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                setFeatureQuietly("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                setFeatureQuietly("http://xml.org/sax/features/external-general-entities", false)
                setFeatureQuietly("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val doc = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            doc.documentElement
        } catch (t: Throwable) {
            return emptyDocument(
                xmlBytes,
                VectorWarning(
                    VectorWarning.Codes.MALFORMED_XML,
                    "Could not parse VectorDrawable XML: ${t.message ?: t.javaClass.simpleName}",
                ),
            )
        }

        if (rootElement == null || rootElement.tagName != "vector") {
            return emptyDocument(
                xmlBytes,
                VectorWarning(
                    VectorWarning.Codes.MALFORMED_XML,
                    "Root element is not <vector> (was <${rootElement?.tagName ?: "none"}>)",
                ),
            )
        }

        val viewport = parseViewport(rootElement, warnings)
        val ids = IdGen()
        val children = parseChildren(rootElement, ids, warnings)
        val root = VectorGroup(id = "root", children = children)

        return VectorDocument(
            viewport = viewport,
            root = root,
            warnings = warnings,
            originalXmlBytes = xmlBytes,
        )
    }

    private fun emptyDocument(xmlBytes: Int, warning: VectorWarning): VectorDocument =
        VectorDocument(
            viewport = VectorViewport(24f, 24f, 24f, 24f),
            root = VectorGroup(id = "root", children = emptyList()),
            warnings = listOf(warning),
            originalXmlBytes = xmlBytes,
        )

    private fun parseViewport(el: Element, warnings: MutableList<VectorWarning>): VectorViewport {
        val width = parseDimension(attr(el, "android:width"))
        val height = parseDimension(attr(el, "android:height"))
        val vpW = attr(el, "android:viewportWidth")?.toFloatOrNull()
        val vpH = attr(el, "android:viewportHeight")?.toFloatOrNull()

        if (vpW == null || vpH == null) {
            warnings += VectorWarning(
                VectorWarning.Codes.MISSING_VIEWPORT,
                "Missing android:viewportWidth/viewportHeight; using safe defaults",
            )
        }
        val resolvedVpW = vpW ?: width ?: 24f
        val resolvedVpH = vpH ?: height ?: 24f
        return VectorViewport(
            widthDp = width ?: resolvedVpW,
            heightDp = height ?: resolvedVpH,
            viewportWidth = resolvedVpW,
            viewportHeight = resolvedVpH,
        )
    }

    private fun parseChildren(
        parent: Element,
        ids: IdGen,
        warnings: MutableList<VectorWarning>,
    ): List<VectorNode> {
        val out = ArrayList<VectorNode>()
        val nodes = parent.childNodes
        for (idx in 0 until nodes.length) {
            val node = nodes.item(idx)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val el = node as Element
            when (el.tagName) {
                "path" -> out += VectorNode.PathNode(parsePath(el, ids, warnings))
                "group" -> out += VectorNode.GroupNode(parseGroup(el, ids, warnings))
                "clip-path" -> warnings += VectorWarning(
                    VectorWarning.Codes.CLIP_PATH_NOT_SUPPORTED,
                    "<clip-path> is not supported and was dropped",
                )
                else -> warnings += VectorWarning(
                    VectorWarning.Codes.UNSUPPORTED_TAG,
                    "Unsupported element <${el.tagName}> was dropped",
                )
            }
        }
        return out
    }

    private fun parseGroup(el: Element, ids: IdGen, warnings: MutableList<VectorWarning>): VectorGroup {
        val id = ids.nextGroup()
        return VectorGroup(
            id = id,
            name = attr(el, "android:name"),
            rotation = attr(el, "android:rotation")?.toFloatOrNull(),
            pivotX = attr(el, "android:pivotX")?.toFloatOrNull(),
            pivotY = attr(el, "android:pivotY")?.toFloatOrNull(),
            scaleX = attr(el, "android:scaleX")?.toFloatOrNull(),
            scaleY = attr(el, "android:scaleY")?.toFloatOrNull(),
            translateX = attr(el, "android:translateX")?.toFloatOrNull(),
            translateY = attr(el, "android:translateY")?.toFloatOrNull(),
            children = parseChildren(el, ids, warnings),
        )
    }

    private fun parsePath(el: Element, ids: IdGen, warnings: MutableList<VectorWarning>): VectorPath {
        val id = ids.nextPath()
        val pathData = attr(el, "android:pathData") ?: ""

        // Gradients are expressed as nested <aapt:attr name="android:fillColor">
        // /<gradient> children — parse them into a VectorFill instead of dropping.
        val fill = parseFillGradient(el, id, warnings)

        val commands: List<PathCommand>? = if (pathData.isBlank()) {
            null
        } else {
            val result = PathDataParser.parse(pathData, id)
            warnings += result.warnings
            result.commands.ifEmpty { null }
        }

        val style = VectorStyle(
            fillColor = attr(el, "android:fillColor"),
            fillAlpha = attr(el, "android:fillAlpha")?.toFloatOrNull(),
            fillType = attr(el, "android:fillType"),
            strokeColor = attr(el, "android:strokeColor"),
            strokeAlpha = attr(el, "android:strokeAlpha")?.toFloatOrNull(),
            strokeWidth = attr(el, "android:strokeWidth")?.toFloatOrNull(),
            strokeLineCap = attr(el, "android:strokeLineCap"),
            strokeLineJoin = attr(el, "android:strokeLineJoin"),
            strokeMiterLimit = attr(el, "android:strokeMiterLimit")?.toFloatOrNull(),
            fill = fill,
        )

        return VectorPath(
            id = id,
            name = attr(el, "android:name"),
            pathData = pathData,
            commands = commands,
            style = style,
        )
    }

    // ---- helpers ----

    private fun attr(el: Element, name: String): String? =
        if (el.hasAttribute(name)) el.getAttribute(name) else null

    /** Direct child elements of [el] (skips text/comment nodes). */
    private fun elementChildren(el: Element): List<Element> {
        val out = ArrayList<Element>()
        val nodes = el.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) out += n as Element
        }
        return out
    }

    /**
     * Parses a `<aapt:attr name="android:fillColor"><gradient .../></aapt:attr>`
     * child of a `<path>` into a [VectorFill]. Returns null when the path has no
     * gradient fill. Any other nested element (e.g. a gradient on a different
     * attribute we can't model) is surfaced as [GRADIENT_NOT_SUPPORTED].
     */
    private fun parseFillGradient(
        pathEl: Element,
        pathId: String,
        warnings: MutableList<VectorWarning>,
    ): VectorFill? {
        var fill: VectorFill? = null
        for (child in elementChildren(pathEl)) {
            val isFillAttr = child.tagName == "aapt:attr" &&
                attr(child, "name") == "android:fillColor"
            val gradientEl = if (isFillAttr) {
                elementChildren(child).firstOrNull { it.tagName == "gradient" }
            } else {
                null
            }
            if (gradientEl != null) {
                fill = parseGradient(gradientEl)
            } else {
                warnings += VectorWarning(
                    VectorWarning.Codes.GRADIENT_NOT_SUPPORTED,
                    "Nested <${child.tagName}> on <path> is not supported and was dropped.",
                    pathId,
                )
            }
        }
        return fill
    }

    private fun parseGradient(el: Element): VectorFill {
        val stops = elementChildren(el)
            .filter { it.tagName == "item" }
            .mapNotNull { item ->
                val offset = attr(item, "android:offset")?.toFloatOrNull() ?: return@mapNotNull null
                val color = attr(item, "android:color") ?: return@mapNotNull null
                GradientStop(offset, color)
            }
        val tileMode = attr(el, "android:tileMode")
        fun f(name: String): Float = attr(el, name)?.toFloatOrNull() ?: 0f
        return when (attr(el, "android:type")?.lowercase()) {
            "radial" -> VectorFill.Radial(
                cx = f("android:centerX"), cy = f("android:centerY"),
                radius = f("android:gradientRadius"), stops = stops, tileMode = tileMode,
            )
            "sweep" -> VectorFill.Sweep(
                cx = f("android:centerX"), cy = f("android:centerY"), stops = stops,
            )
            else -> VectorFill.Linear(
                x1 = f("android:startX"), y1 = f("android:startY"),
                x2 = f("android:endX"), y2 = f("android:endY"),
                stops = stops, tileMode = tileMode,
            )
        }
    }

    /** Strips a trailing unit suffix (`dp`, `dip`, `px`, …) and parses the number. */
    private fun parseDimension(raw: String?): Float? {
        if (raw == null) return null
        val match = Regex("^\\s*([+-]?[0-9]*\\.?[0-9]+)").find(raw) ?: return null
        return match.groupValues[1].toFloatOrNull()
    }

    private fun DocumentBuilderFactory.setFeatureQuietly(name: String, value: Boolean) {
        try {
            setFeature(name, value)
        } catch (_: Throwable) {
            // Not all parser implementations support every feature; ignore.
        }
    }
}
