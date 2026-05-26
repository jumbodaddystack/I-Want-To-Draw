package com.aichat.sandbox.data.vector

import kotlin.math.cos
import kotlin.math.sin

/**
 * Output of [VectorSceneCompiler.compile]: the compiled candidate [document]
 * (re-parsed from [xml] so it is guaranteed to round-trip), its serialized
 * VectorDrawable [xml], the [metrics] for the compiled result, and any
 * [warnings] raised while compiling.
 */
data class VectorSceneCompileResult(
    val document: VectorDocument,
    val xml: String,
    val metrics: VectorMetrics,
    val warnings: List<VectorWarning>,
)

/**
 * Phase 5 — compiles a validated [VectorScene] into Android VectorDrawable XML.
 *
 * The transformative counterpart to [VectorEditPlanApplier]: where the applier
 * edits existing paths, this builds fresh paths from scene primitives. Each
 * object becomes one `<path>` (preserving its id as the path name) in
 * deterministic scene order, geometry is expressed as plain path data the
 * [AndroidVectorDrawableWriter] can emit, and the result is re-parsed through
 * [AndroidVectorDrawableParser] so the candidate is proven to be valid
 * VectorDrawable XML. A scene with no drawable objects compiles to a safe empty
 * vector plus a [VectorWarning.Codes.SCENE_EMPTY] warning rather than failing.
 */
object VectorSceneCompiler {

    fun compile(scene: VectorScene): VectorSceneCompileResult {
        val warnings = ArrayList<VectorWarning>()
        val nodes = ArrayList<VectorNode>(scene.objects.size)

        for (obj in scene.objects) {
            val path = runCatching { compileObject(obj) }.getOrNull()
            if (path == null) {
                warnings += VectorWarning(
                    VectorWarning.Codes.SCENE_MALFORMED_PATH,
                    "Could not compile object '${obj.id}'",
                    obj.id,
                )
                continue
            }
            nodes += VectorNode.PathNode(path)
        }

        if (nodes.isEmpty()) {
            warnings += VectorWarning(
                VectorWarning.Codes.SCENE_EMPTY,
                "Scene produced no drawable objects; compiled an empty vector",
            )
        }

        val built = VectorDocument(
            viewport = scene.viewport,
            root = VectorGroup(id = "root", children = nodes),
        )
        val xml = AndroidVectorDrawableWriter.write(built)
        // Round-trip: prove the candidate parses as VectorDrawable XML and
        // compute metrics from the re-parsed form so they reflect reality.
        val reparsed = AndroidVectorDrawableParser.parse(xml)
        val metrics = VectorMetricsAnalyzer.analyze(reparsed, xml)
        warnings += reparsed.warnings

        val allWarnings = if (warnings.isEmpty()) {
            emptyList()
        } else {
            listOf(
                VectorWarning(
                    VectorWarning.Codes.SCENE_COMPILED_WITH_WARNINGS,
                    "Scene compiled with ${warnings.size} warning(s)",
                ),
            ) + warnings
        }

        return VectorSceneCompileResult(
            document = reparsed,
            xml = xml,
            metrics = metrics,
            warnings = allWarnings,
        )
    }

    private fun compileObject(obj: VectorSceneObject): VectorPath {
        val pathData = when (obj) {
            is VectorSceneObject.Line -> lineData(obj)
            is VectorSceneObject.Rect -> rectData(obj)
            is VectorSceneObject.Ellipse -> ellipseData(obj)
            is VectorSceneObject.Polygon -> polygonData(obj)
            is VectorSceneObject.Path -> obj.pathData
        }
        require(PathDataParser.parse(pathData).commands.isNotEmpty()) { "empty path data" }
        return VectorPath(
            id = obj.id,
            name = obj.id,
            pathData = pathData,
            // commands left null so the writer emits pathData verbatim; the
            // re-parse step re-derives commands for metrics.
            commands = null,
            style = toVectorStyle(obj.style),
        )
    }

    private fun toVectorStyle(s: VectorSceneStyle): VectorStyle {
        val hasStroke = s.strokeColor != null
        return VectorStyle(
            fillColor = s.fillColor,
            fillAlpha = s.fillAlpha,
            strokeColor = s.strokeColor,
            strokeAlpha = if (hasStroke) s.strokeAlpha else null,
            strokeWidth = if (hasStroke) s.strokeWidth else null,
            strokeLineCap = if (hasStroke) s.strokeLineCap else null,
            strokeLineJoin = if (hasStroke) s.strokeLineJoin else null,
        )
    }

    // ---- primitive → path data ----

    private fun lineData(o: VectorSceneObject.Line): String =
        "M${n(o.x0)},${n(o.y0)} L${n(o.x1)},${n(o.y1)}"

    private fun rectData(o: VectorSceneObject.Rect): String {
        val x = o.x
        val y = o.y
        val w = o.width
        val h = o.height
        val r = o.radius.coerceIn(0f, minOf(w, h) / 2f)
        if (r <= 0f) {
            return "M${n(x)},${n(y)} L${n(x + w)},${n(y)} " +
                "L${n(x + w)},${n(y + h)} L${n(x)},${n(y + h)} Z"
        }
        return buildString {
            append("M${n(x + r)},${n(y)} ")
            append("L${n(x + w - r)},${n(y)} ")
            append("A${n(r)},${n(r)} 0 0,1 ${n(x + w)},${n(y + r)} ")
            append("L${n(x + w)},${n(y + h - r)} ")
            append("A${n(r)},${n(r)} 0 0,1 ${n(x + w - r)},${n(y + h)} ")
            append("L${n(x + r)},${n(y + h)} ")
            append("A${n(r)},${n(r)} 0 0,1 ${n(x)},${n(y + h - r)} ")
            append("L${n(x)},${n(y + r)} ")
            append("A${n(r)},${n(r)} 0 0,1 ${n(x + r)},${n(y)} ")
            append("Z")
        }
    }

    private fun ellipseData(o: VectorSceneObject.Ellipse): String {
        if (o.rotation == 0f) {
            return "M${n(o.cx - o.rx)},${n(o.cy)} " +
                "A${n(o.rx)},${n(o.ry)} 0 1,0 ${n(o.cx + o.rx)},${n(o.cy)} " +
                "A${n(o.rx)},${n(o.ry)} 0 1,0 ${n(o.cx - o.rx)},${n(o.cy)} Z"
        }
        // Rotated ellipse: bake the rotation into the arc's x-axis rotation and
        // rotate the major-axis endpoints about the center. Approximate but
        // deterministic and re-parseable.
        val rad = Math.toRadians(o.rotation.toDouble())
        val cos = cos(rad).toFloat()
        val sin = sin(rad).toFloat()
        val p1x = o.cx + o.rx * cos
        val p1y = o.cy + o.rx * sin
        val p2x = o.cx - o.rx * cos
        val p2y = o.cy - o.rx * sin
        return "M${n(p2x)},${n(p2y)} " +
            "A${n(o.rx)},${n(o.ry)} ${n(o.rotation)} 1,0 ${n(p1x)},${n(p1y)} " +
            "A${n(o.rx)},${n(o.ry)} ${n(o.rotation)} 1,0 ${n(p2x)},${n(p2y)} Z"
    }

    private fun polygonData(o: VectorSceneObject.Polygon): String = buildString {
        o.points.forEachIndexed { i, p ->
            append(if (i == 0) "M" else " L").append(n(p.x)).append(',').append(n(p.y))
        }
        if (o.closed) append(" Z")
    }

    private fun n(value: Float): String = PathDataFormatter.num(value)
}
