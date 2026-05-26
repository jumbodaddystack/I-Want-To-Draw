package com.aichat.sandbox.data.vector

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Phase 4 — compact, model-friendly JSON view of a [VectorDocument].
 *
 * Sibling to `data/notes/VectorCanvasJson`: instead of handing the model the
 * full (potentially multi-MB) VectorDrawable XML, [summarize] emits a small,
 * lossy, deterministic description it can diagnose and plan against. Path IDs in
 * the summary are the same `p_NNN` IDs the parser assigned, so the model's edit
 * plan can target them and [VectorEditPlanApplier] can resolve them.
 *
 * The summary never includes the raw XML. Per-path geometry is downsampled to
 * [MAX_SAMPLED_POINTS_PER_PATH] points, and the whole document is soft-capped at
 * [MAX_JSON_BYTES]; when over cap the largest paths are dropped first (their
 * geometry contributes most to the payload and is least likely to be edited
 * point-by-point) and reported in [Summary.droppedPathIds].
 */
object VectorSummaryJson {

    const val SCHEMA: Int = 1
    const val MAX_SAMPLED_POINTS_PER_PATH: Int = 32
    const val MAX_JSON_BYTES: Int = 180 * 1024

    /** Endpoints closer than this are treated as duplicate coordinates. */
    private const val DUPLICATE_EPS = 1e-3f

    /** Segments shorter than this (viewport units) are treated as tiny noise. */
    private const val TINY_SEGMENT = 0.5f

    data class Summary(
        val json: String,
        val includedPathIds: List<String>,
        val droppedPathIds: List<String>,
        val warnings: List<VectorWarning>,
    )

    fun summarize(
        document: VectorDocument,
        metrics: VectorMetrics,
    ): Summary {
        val paths = document.allPaths()
        val perPath = paths.map { buildPathSummary(it) }

        // Soft cap: drop the largest paths (by command count) first until the
        // encoded document fits. Filled/structural detail in small paths is
        // preserved for as long as possible.
        var working = perPath.toMutableList()
        val dropped = ArrayList<String>()
        var json = encode(document, metrics, working)
        while (json.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES && working.size > 1) {
            val victimIndex = working.indices.maxByOrNull { working[it].commandCount } ?: break
            dropped += working[victimIndex].id
            working.removeAt(victimIndex)
            json = encode(document, metrics, working)
        }

        val warnings = ArrayList<VectorWarning>()
        if (dropped.isNotEmpty()) {
            warnings += VectorWarning(
                VectorWarning.Codes.SUMMARY_PATHS_DROPPED,
                "${dropped.size} large path(s) dropped from the AI summary to fit the size cap",
            )
        }

        return Summary(
            json = json,
            includedPathIds = working.map { it.id },
            droppedPathIds = dropped,
            warnings = warnings,
        )
    }

    // ---- encoding ----

    private fun encode(
        document: VectorDocument,
        metrics: VectorMetrics,
        paths: List<PathSummary>,
    ): String {
        val root = JsonObject()
        root.addProperty("schema", SCHEMA)
        root.addProperty("format", "android_vector_drawable")

        val vp = document.viewport
        root.add("viewport", JsonObject().apply {
            addProperty("widthDp", num(vp.widthDp))
            addProperty("heightDp", num(vp.heightDp))
            addProperty("viewportWidth", num(vp.viewportWidth))
            addProperty("viewportHeight", num(vp.viewportHeight))
        })

        root.add("metrics", JsonObject().apply {
            addProperty("xmlBytes", metrics.xmlBytes)
            addProperty("pathCount", metrics.pathCount)
            addProperty("groupCount", metrics.groupCount)
            addProperty("commandCount", metrics.commandCount)
            addProperty("estimatedPointCount", metrics.estimatedPointCount)
            addProperty("strokePathCount", metrics.strokePathCount)
            addProperty("fillPathCount", metrics.fillPathCount)
            addProperty("tinySegmentEstimate", metrics.tinySegmentEstimate)
            addProperty("duplicateCoordinateEstimate", metrics.duplicateCoordinateEstimate)
            val colors = JsonObject()
            for ((color, count) in metrics.colorCounts) colors.addProperty(color, count)
            add("colors", colors)
        })

        val pathArr = JsonArray()
        for (p in paths) pathArr.add(encodePath(p))
        root.add("paths", pathArr)

        val warningArr = JsonArray()
        for (w in document.warnings) {
            warningArr.add(JsonObject().apply {
                addProperty("code", w.code)
                if (w.nodeId != null) addProperty("nodeId", w.nodeId)
            })
        }
        root.add("warnings", warningArr)

        return root.toString()
    }

    private fun encodePath(p: PathSummary): JsonObject = JsonObject().apply {
        addProperty("id", p.id)
        if (p.name != null) addProperty("name", p.name)
        add("style", JsonObject().apply {
            if (p.fill != null) addProperty("fill", p.fill)
            if (p.stroke != null) addProperty("stroke", p.stroke)
            if (p.strokeWidth != null) addProperty("strokeWidth", num(p.strokeWidth))
            if (p.lineCap != null) addProperty("lineCap", p.lineCap)
            if (p.lineJoin != null) addProperty("lineJoin", p.lineJoin)
        })
        addProperty("commandCount", p.commandCount)
        addProperty("estimatedPointCount", p.estimatedPointCount)
        if (p.bounds != null) {
            val b = JsonArray(4)
            b.add(num(p.bounds.minX)); b.add(num(p.bounds.minY))
            b.add(num(p.bounds.maxX)); b.add(num(p.bounds.maxY))
            add("bounds", b)
        }
        val pts = JsonArray(p.sampledPoints.size)
        for (pt in p.sampledPoints) {
            val pair = JsonArray(2)
            pair.add(num(pt.x)); pair.add(num(pt.y))
            pts.add(pair)
        }
        add("sampledPoints", pts)
        add("noise", JsonObject().apply {
            addProperty("tinySegments", p.tinySegments)
            addProperty("duplicatePoints", p.duplicatePoints)
        })
    }

    // ---- per-path geometry ----

    private data class PathSummary(
        val id: String,
        val name: String?,
        val fill: String?,
        val stroke: String?,
        val strokeWidth: Float?,
        val lineCap: String?,
        val lineJoin: String?,
        val commandCount: Int,
        val estimatedPointCount: Int,
        val bounds: VectorBounds?,
        val sampledPoints: List<VectorPoint>,
        val tinySegments: Int,
        val duplicatePoints: Int,
    )

    private fun buildPathSummary(path: VectorPath): PathSummary {
        val commands = path.commands
        val commandCount = commands?.size ?: countRawCommands(path.pathData)
        val estimatedPointCount = commands?.sumOf { pointsIn(it) } ?: 0

        val sampled = commands?.let { VectorPathSampler.sample(it) }
        val points = sampled?.points ?: emptyList()
        val bounds = boundsOf(points)
        val sampledPoints = downsample(points, MAX_SAMPLED_POINTS_PER_PATH)
        val (tiny, dup) = noiseOf(points)

        val style = path.style
        return PathSummary(
            id = path.id,
            name = path.name,
            fill = style.fillColor,
            stroke = style.strokeColor,
            strokeWidth = style.strokeWidth,
            lineCap = style.strokeLineCap,
            lineJoin = style.strokeLineJoin,
            commandCount = commandCount,
            estimatedPointCount = estimatedPointCount,
            bounds = bounds,
            sampledPoints = sampledPoints,
            tinySegments = tiny,
            duplicatePoints = dup,
        )
    }

    private fun boundsOf(points: List<VectorPoint>): VectorBounds? {
        if (points.isEmpty()) return null
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (p in points) {
            minX = min(minX, p.x); minY = min(minY, p.y)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y)
        }
        return VectorBounds(minX, minY, maxX, maxY)
    }

    private fun noiseOf(points: List<VectorPoint>): Pair<Int, Int> {
        var tiny = 0
        var dup = 0
        for (k in 1 until points.size) {
            val d = hypot(points[k].x - points[k - 1].x, points[k].y - points[k - 1].y)
            if (d < DUPLICATE_EPS) dup++
            else if (d < TINY_SEGMENT) tiny++
        }
        return tiny to dup
    }

    /**
     * Evenly spaced subset of [points] of at most [maxPoints] entries, always
     * keeping the first and last vertex so the path's extent is preserved.
     */
    private fun downsample(points: List<VectorPoint>, maxPoints: Int): List<VectorPoint> {
        if (points.size <= maxPoints) return points
        if (maxPoints <= 1) return listOf(points.first())
        val out = ArrayList<VectorPoint>(maxPoints)
        val step = (points.size - 1).toDouble() / (maxPoints - 1).toDouble()
        for (i in 0 until maxPoints) {
            val idx = (i * step).toInt().coerceIn(0, points.size - 1)
            out += points[idx]
        }
        out[out.size - 1] = points.last()
        return out
    }

    private fun pointsIn(cmd: PathCommand): Int = when (cmd) {
        is PathCommand.MoveTo, is PathCommand.LineTo,
        is PathCommand.HorizontalTo, is PathCommand.VerticalTo,
        is PathCommand.SmoothQuadTo, is PathCommand.ArcTo -> 1
        is PathCommand.SmoothCubicTo, is PathCommand.QuadTo -> 2
        is PathCommand.CubicTo -> 3
        is PathCommand.Close -> 0
    }

    private fun countRawCommands(pathData: String): Int =
        pathData.count { it.isLetter() && it.lowercaseChar() in "mlhvcsqtaz" }

    /** Rounds to 2 decimals; returns an Int-valued double when whole. */
    private fun num(value: Float): Double {
        if (value.isNaN() || value.isInfinite()) return 0.0
        return (round(value * 100f) / 100f).toDouble()
    }
}
