package com.aichat.sandbox.data.vector

/**
 * Serializes a [VectorDocument] back into Android `VectorDrawable` XML.
 *
 * The export side of the Vector Art Tune-Up pipeline and the inverse of
 * [AndroidVectorDrawableParser]. Output is deterministic — attributes are
 * emitted in a fixed order and only when present — so tests can compare strings
 * directly or re-parse and assert equivalence. Path data is taken from
 * [PathDataFormatter] when the path has parsed commands; otherwise the original
 * `pathData` is emitted verbatim so unparsed paths survive a round trip.
 *
 * The synthetic [VectorDocument.root] group is not emitted as a `<group>`; its
 * children are written directly under `<vector>`.
 */
object AndroidVectorDrawableWriter {

    private const val INDENT = "    "

    fun write(document: VectorDocument): String {
        // Phase 5: VectorDrawable has no dash/variable-width attribute, so bake
        // both into plain geometry first. No-op for any path that doesn't opt in.
        val baked = StrokeExportBaker.bakeDashes(
            StrokeExportBaker.bakeVariableWidth(document),
        ).first
        val sb = StringBuilder(1024)
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        sb.append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n")
        // Gradients are expressed as nested <aapt:attr> children, which need the
        // aapt namespace declared on the root. Only emit it when a path opts in,
        // so non-gradient documents stay byte-identical.
        if (baked.allPaths().any { it.style.fill.isGradient() }) {
            sb.append(INDENT).append("xmlns:aapt=\"http://schemas.android.com/aapt\"\n")
        }
        val vp = baked.viewport
        sb.append(INDENT).append("android:width=\"").append(num(vp.widthDp)).append("dp\"\n")
        sb.append(INDENT).append("android:height=\"").append(num(vp.heightDp)).append("dp\"\n")
        sb.append(INDENT).append("android:viewportWidth=\"").append(num(vp.viewportWidth)).append("\"\n")
        sb.append(INDENT).append("android:viewportHeight=\"").append(num(vp.viewportHeight)).append("\">\n")

        for (child in baked.root.children) {
            writeNode(sb, child, depth = 1)
        }

        sb.append("</vector>\n")
        return sb.toString()
    }

    private fun writeNode(sb: StringBuilder, node: VectorNode, depth: Int) {
        when (node) {
            is VectorNode.GroupNode -> writeGroup(sb, node.group, depth)
            is VectorNode.PathNode -> writePath(sb, node.path, depth)
            is VectorNode.InstanceNode -> Unit // symbols are expanded before export; nothing to emit
        }
    }

    private fun writeGroup(sb: StringBuilder, group: VectorGroup, depth: Int) {
        val pad = INDENT.repeat(depth)
        sb.append(pad).append("<group")
        appendAttr(sb, "android:name", group.name)
        appendAttr(sb, "android:rotation", group.rotation)
        appendAttr(sb, "android:pivotX", group.pivotX)
        appendAttr(sb, "android:pivotY", group.pivotY)
        appendAttr(sb, "android:scaleX", group.scaleX)
        appendAttr(sb, "android:scaleY", group.scaleY)
        appendAttr(sb, "android:translateX", group.translateX)
        appendAttr(sb, "android:translateY", group.translateY)
        if (group.children.isEmpty()) {
            sb.append("/>\n")
            return
        }
        sb.append(">\n")
        for (child in group.children) writeNode(sb, child, depth + 1)
        sb.append(pad).append("</group>\n")
    }

    private fun writePath(sb: StringBuilder, path: VectorPath, depth: Int) {
        val pad = INDENT.repeat(depth)
        val data = path.commands?.takeIf { it.isNotEmpty() }
            ?.let { PathDataFormatter.format(it) }
            ?: path.pathData
        val style = path.style
        sb.append(pad).append("<path")
        appendAttr(sb, "android:name", path.name)
        appendAttr(sb, "android:pathData", data)
        // A non-null fill overrides the scalar fillColor/fillAlpha. A Solid fill is
        // still a plain attribute; only a gradient needs the nested <aapt:attr> block.
        val gradient = style.fill as? VectorFill.Linear
            ?: style.fill as? VectorFill.Radial
            ?: style.fill as? VectorFill.Sweep
        when (val fill = style.fill) {
            is VectorFill.Solid -> {
                appendAttr(sb, "android:fillColor", fill.color)
                appendAttr(sb, "android:fillAlpha", fill.alpha)
            }
            null -> {
                appendAttr(sb, "android:fillColor", style.fillColor)
                appendAttr(sb, "android:fillAlpha", style.fillAlpha)
            }
            else -> { /* gradient: emitted as a nested child below */ }
        }
        appendAttr(sb, "android:fillType", style.fillType)
        appendAttr(sb, "android:strokeColor", style.strokeColor)
        appendAttr(sb, "android:strokeAlpha", style.strokeAlpha)
        appendAttr(sb, "android:strokeWidth", style.strokeWidth)
        appendAttr(sb, "android:strokeLineCap", style.strokeLineCap)
        appendAttr(sb, "android:strokeLineJoin", style.strokeLineJoin)
        appendAttr(sb, "android:strokeMiterLimit", style.strokeMiterLimit)
        if (gradient == null) {
            sb.append("/>\n")
            return
        }
        sb.append(">\n")
        writeGradient(sb, gradient, depth + 1)
        sb.append(pad).append("</path>\n")
    }

    /** Emits a `<aapt:attr name="android:fillColor"><gradient .../></aapt:attr>` block. */
    private fun writeGradient(sb: StringBuilder, fill: VectorFill, depth: Int) {
        val pad = INDENT.repeat(depth)
        val gpad = INDENT.repeat(depth + 1)
        val ipad = INDENT.repeat(depth + 2)
        sb.append(pad).append("<aapt:attr name=\"android:fillColor\">\n")
        sb.append(gpad).append("<gradient")
        when (fill) {
            is VectorFill.Linear -> {
                appendAttr(sb, "android:type", "linear")
                appendAttr(sb, "android:startX", fill.x1)
                appendAttr(sb, "android:startY", fill.y1)
                appendAttr(sb, "android:endX", fill.x2)
                appendAttr(sb, "android:endY", fill.y2)
                appendAttr(sb, "android:tileMode", fill.tileMode)
            }
            is VectorFill.Radial -> {
                appendAttr(sb, "android:type", "radial")
                appendAttr(sb, "android:centerX", fill.cx)
                appendAttr(sb, "android:centerY", fill.cy)
                appendAttr(sb, "android:gradientRadius", fill.radius)
                appendAttr(sb, "android:tileMode", fill.tileMode)
            }
            is VectorFill.Sweep -> {
                appendAttr(sb, "android:type", "sweep")
                appendAttr(sb, "android:centerX", fill.cx)
                appendAttr(sb, "android:centerY", fill.cy)
            }
            is VectorFill.Solid -> {} // unreachable: gradients only
        }
        val stops = (fill as? VectorFill.Linear)?.stops
            ?: (fill as? VectorFill.Radial)?.stops
            ?: (fill as? VectorFill.Sweep)?.stops
            ?: emptyList()
        if (stops.isEmpty()) {
            sb.append("/>\n")
        } else {
            sb.append(">\n")
            for (stop in stops) {
                sb.append(ipad).append("<item")
                appendAttr(sb, "android:offset", stop.offset)
                appendAttr(sb, "android:color", stop.color)
                sb.append("/>\n")
            }
            sb.append(gpad).append("</gradient>\n")
        }
        sb.append(pad).append("</aapt:attr>\n")
    }

    private fun VectorFill?.isGradient(): Boolean =
        this is VectorFill.Linear || this is VectorFill.Radial || this is VectorFill.Sweep

    private fun appendAttr(sb: StringBuilder, name: String, value: String?) {
        if (value == null) return
        sb.append(' ').append(name).append("=\"").append(escapeXml(value)).append('"')
    }

    private fun appendAttr(sb: StringBuilder, name: String, value: Float?) {
        if (value == null) return
        sb.append(' ').append(name).append("=\"").append(num(value)).append('"')
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

    private fun num(value: Float): String = PathDataFormatter.num(value)
}
