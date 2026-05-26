package com.aichat.sandbox.data.vector

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Phase 5 — strict, tolerant parser for the model's `vector-scene` reply.
 *
 * Sibling to [VectorEditPlanParser], but for the transformative redraw path: the
 * model returns a fresh scene of primitives, not edits to existing paths. [parse]
 * extracts the first recognisable JSON object (fenced or balanced), validates the
 * viewport, then validates every object — colors, geometry, path data — clamping
 * numbers into safe ranges and fitting geometry to the viewport. Objects that
 * cannot be accepted are recorded in [VectorScene.rejected] with a reason; the
 * scene is still returned. [parse] **never throws** and only fails outright when
 * the whole reply contains no parseable JSON object.
 */
object VectorSceneParser {

    private val FENCE_REGEX: Regex =
        Regex(
            "```(?:vector-scene|vector_scene|json)?\\s*\\n?([\\s\\S]*?)```",
            RegexOption.IGNORE_CASE,
        )

    private val COLOR_REGEX: Regex =
        Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

    private val LINE_CAPS = setOf("butt", "round", "square")
    private val LINE_JOINS = setOf("miter", "round", "bevel")

    private val SUPPORTED_TYPES = setOf("path", "line", "rect", "ellipse", "polygon", "polyline")

    // Safe clamp ranges.
    private const val STROKE_WIDTH_MIN = 0.1f
    private const val STROKE_WIDTH_MAX = 64f
    private const val COORD_LIMIT = 100_000f

    // Rejection reasons surfaced to the user (see the Phase 5 spec).
    private const val REASON_MISSING_TYPE = "missing type"
    private const val REASON_UNSUPPORTED_TYPE = "unsupported type"
    private const val REASON_INVALID_GEOMETRY = "invalid geometry"
    private const val REASON_MALFORMED_COLOR = "malformed color"
    private const val REASON_MALFORMED_PATH = "malformed pathData"
    private const val REASON_NO_FILL_OR_STROKE = "no fill or stroke"
    private const val REASON_OUT_OF_BOUNDS = "out of bounds"
    private const val REASON_NOT_AN_OBJECT = "not an object"

    fun parse(
        raw: String,
        fallbackViewport: VectorViewport,
    ): Result<VectorScene> {
        if (raw.isBlank()) return Result.failure(IllegalArgumentException("empty reply"))
        val root = extractObject(raw)
            ?: return Result.failure(IllegalArgumentException("no JSON object found in reply"))
        return try {
            Result.success(parseScene(root, fallbackViewport))
        } catch (t: Throwable) {
            Result.failure(IllegalArgumentException("malformed scene: ${t.message}"))
        }
    }

    private fun parseScene(root: JsonObject, fallbackViewport: VectorViewport): VectorScene {
        val schema = root.intOrNull("schema") ?: VectorScene.SCHEMA
        val styleIntent = root.stringOrNull("styleIntent") ?: ""

        val rejected = ArrayList<VectorScene.RejectedObject>()
        val viewport = parseViewport(root.get("viewport") as? JsonObject, fallbackViewport, rejected)

        val objectsArr = root.get("objects") as? JsonArray ?: JsonArray()
        val accepted = ArrayList<VectorSceneObject>(objectsArr.size())
        for ((index, el) in objectsArr.withIndex()) {
            val obj = el as? JsonObject
            if (obj == null) {
                rejected += VectorScene.RejectedObject(el.toString(), REASON_NOT_AN_OBJECT)
                continue
            }
            when (val result = parseObject(obj, index, viewport)) {
                is ObjResult.Accepted -> accepted += result.obj
                is ObjResult.Rejected ->
                    rejected += VectorScene.RejectedObject(obj.toString(), result.reason)
            }
        }

        return VectorScene(
            schema = schema,
            viewport = viewport,
            styleIntent = styleIntent,
            objects = accepted,
            rejected = rejected,
        )
    }

    private fun parseViewport(
        obj: JsonObject?,
        fallback: VectorViewport,
        notes: MutableList<VectorScene.RejectedObject>,
    ): VectorViewport {
        if (obj == null) {
            notes += VectorScene.RejectedObject("viewport", "missing viewport; used fallback")
            return fallback
        }
        val vpW = obj.floatOrNull("viewportWidth") ?: obj.floatOrNull("width")
        val vpH = obj.floatOrNull("viewportHeight") ?: obj.floatOrNull("height")
        if (vpW == null || vpH == null || vpW <= 0f || vpH <= 0f) {
            notes += VectorScene.RejectedObject("viewport", "invalid viewport; used fallback")
            return fallback
        }
        val widthDp = obj.floatOrNull("widthDp")?.takeIf { it > 0f } ?: vpW
        val heightDp = obj.floatOrNull("heightDp")?.takeIf { it > 0f } ?: vpH
        return VectorViewport(
            widthDp = widthDp,
            heightDp = heightDp,
            viewportWidth = vpW,
            viewportHeight = vpH,
        )
    }

    private sealed interface ObjResult {
        data class Accepted(val obj: VectorSceneObject) : ObjResult
        data class Rejected(val reason: String) : ObjResult
    }

    private fun parseObject(o: JsonObject, index: Int, viewport: VectorViewport): ObjResult {
        val type = o.stringOrNull("type")?.trim()?.lowercase()
            ?: return ObjResult.Rejected(REASON_MISSING_TYPE)
        if (type !in SUPPORTED_TYPES) return ObjResult.Rejected(REASON_UNSUPPORTED_TYPE)

        val id = o.stringOrNull("id")?.trim()?.takeIf { it.isNotBlank() } ?: fallbackId(index)

        val (style, styleReason) = parseStyle(o)
        if (styleReason != null) return ObjResult.Rejected(styleReason)
        if (style.strokeColor == null && style.fillColor == null) {
            return ObjResult.Rejected(REASON_NO_FILL_OR_STROKE)
        }

        val obj: VectorSceneObject = when (type) {
            "line" -> parseLine(o, id, style) ?: return ObjResult.Rejected(REASON_INVALID_GEOMETRY)
            "rect" -> parseRect(o, id, style) ?: return ObjResult.Rejected(REASON_INVALID_GEOMETRY)
            "ellipse" -> parseEllipse(o, id, style) ?: return ObjResult.Rejected(REASON_INVALID_GEOMETRY)
            "polygon" -> parsePolygon(o, id, style, defaultClosed = true)
                ?: return ObjResult.Rejected(REASON_INVALID_GEOMETRY)
            "polyline" -> parsePolygon(o, id, style, defaultClosed = false)
                ?: return ObjResult.Rejected(REASON_INVALID_GEOMETRY)
            "path" -> {
                val pathData = o.stringOrNull("pathData")?.trim()
                if (pathData.isNullOrBlank()) return ObjResult.Rejected(REASON_INVALID_GEOMETRY)
                if (PathDataParser.parse(pathData).commands.isEmpty()) {
                    return ObjResult.Rejected(REASON_MALFORMED_PATH)
                }
                VectorSceneObject.Path(id, pathData, style)
            }
            else -> return ObjResult.Rejected(REASON_UNSUPPORTED_TYPE)
        }

        val clamped = VectorSceneBounds.clampObjectToViewport(obj, viewport)
            ?: return ObjResult.Rejected(REASON_OUT_OF_BOUNDS)
        return ObjResult.Accepted(clamped)
    }

    // ---- style ----

    private fun parseStyle(o: JsonObject): Pair<VectorSceneStyle, String?> {
        val strokeRaw = o.stringOrNull("stroke")
        val fillRaw = o.stringOrNull("fill")
        val stroke = if (strokeRaw != null) {
            normalizeColor(strokeRaw) ?: return VectorSceneStyle() to REASON_MALFORMED_COLOR
        } else {
            null
        }
        val fill = if (fillRaw != null) {
            normalizeColor(fillRaw) ?: return VectorSceneStyle() to REASON_MALFORMED_COLOR
        } else {
            null
        }
        val strokeWidth = o.floatOrNull("strokeWidth")?.coerceIn(STROKE_WIDTH_MIN, STROKE_WIDTH_MAX)
        val cap = o.stringOrNull("lineCap")?.lowercase()?.takeIf { it in LINE_CAPS } ?: "round"
        val join = o.stringOrNull("lineJoin")?.lowercase()?.takeIf { it in LINE_JOINS } ?: "round"
        val strokeAlpha = o.floatOrNull("strokeAlpha")?.coerceIn(0f, 1f)
        val fillAlpha = o.floatOrNull("fillAlpha")?.coerceIn(0f, 1f)
        return VectorSceneStyle(
            strokeColor = stroke,
            fillColor = fill,
            strokeWidth = strokeWidth,
            strokeLineCap = cap,
            strokeLineJoin = join,
            strokeAlpha = strokeAlpha,
            fillAlpha = fillAlpha,
        ) to null
    }

    // ---- geometry ----

    private fun parseLine(o: JsonObject, id: String, style: VectorSceneStyle): VectorSceneObject.Line? {
        val x0 = o.floatOrNull("x0") ?: return null
        val y0 = o.floatOrNull("y0") ?: return null
        val x1 = o.floatOrNull("x1") ?: return null
        val y1 = o.floatOrNull("y1") ?: return null
        if (x0 == x1 && y0 == y1) return null // zero-length line
        return VectorSceneObject.Line(
            id, sane(x0), sane(y0), sane(x1), sane(y1), style,
        )
    }

    private fun parseRect(o: JsonObject, id: String, style: VectorSceneStyle): VectorSceneObject.Rect? {
        val x = o.floatOrNull("x") ?: return null
        val y = o.floatOrNull("y") ?: return null
        val width = o.floatOrNull("width") ?: return null
        val height = o.floatOrNull("height") ?: return null
        if (width <= 0f || height <= 0f) return null
        val radius = (o.floatOrNull("radius") ?: 0f).coerceAtLeast(0f)
        return VectorSceneObject.Rect(
            id, sane(x), sane(y), sane(width), sane(height), radius, style,
        )
    }

    private fun parseEllipse(o: JsonObject, id: String, style: VectorSceneStyle): VectorSceneObject.Ellipse? {
        val cx = o.floatOrNull("cx") ?: return null
        val cy = o.floatOrNull("cy") ?: return null
        val rx = o.floatOrNull("rx") ?: return null
        val ry = o.floatOrNull("ry") ?: return null
        if (rx <= 0f || ry <= 0f) return null
        val rotation = (o.floatOrNull("rotation") ?: 0f).takeIf { it.isFinite() } ?: 0f
        return VectorSceneObject.Ellipse(
            id, sane(cx), sane(cy), sane(rx), sane(ry), rotation, style,
        )
    }

    private fun parsePolygon(
        o: JsonObject,
        id: String,
        style: VectorSceneStyle,
        defaultClosed: Boolean,
    ): VectorSceneObject.Polygon? {
        val arr = o.get("points") as? JsonArray ?: return null
        val points = ArrayList<VectorPoint>(arr.size())
        for (el in arr) {
            val pair = el as? JsonArray ?: continue
            if (pair.size() < 2) continue
            val x = pair.get(0).asFloatOrNull() ?: continue
            val y = pair.get(1).asFloatOrNull() ?: continue
            points += VectorPoint(sane(x), sane(y))
        }
        if (points.size < 2) return null
        val closed = o.booleanOrNull("closed") ?: defaultClosed
        return VectorSceneObject.Polygon(id, points, closed, style)
    }

    // ---- JSON extraction (mirrors VectorEditPlanParser) ----

    internal fun extractObject(raw: String): JsonObject? {
        for (candidate in candidates(raw)) {
            val obj = runCatching { JsonParser.parseString(candidate) as? JsonObject }.getOrNull()
            if (obj != null) return obj
        }
        return null
    }

    private fun candidates(raw: String): List<String> {
        val out = ArrayList<String>(2)
        FENCE_REGEX.find(raw)?.let { out += it.groupValues[1].trim() }
        balancedObject(raw)?.let { out += it }
        return out
    }

    private fun balancedObject(raw: String): String? {
        val opening = raw.indexOf('{')
        if (opening < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in opening until raw.length) {
            val c = raw[i]
            if (inString) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(opening, i + 1)
                }
            }
        }
        return null
    }

    // ---- helpers ----

    private fun fallbackId(index: Int): String = "o_" + (index + 1).toString().padStart(3, '0')

    private fun sane(v: Float): Float = v.coerceIn(-COORD_LIMIT, COORD_LIMIT)

    private fun normalizeColor(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        return if (COLOR_REGEX.matches(trimmed)) trimmed else null
    }

    private fun JsonElement?.asFloatOrNull(): Float? {
        if (this == null || !isJsonPrimitive) return null
        return runCatching { asFloat }.getOrNull()?.takeIf { it.isFinite() }
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        val e = get(key) ?: return null
        return if (e.isJsonPrimitive) e.asString else null
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        val e = get(key) ?: return null
        if (!e.isJsonPrimitive) return null
        return runCatching { e.asInt }.getOrNull()
    }

    private fun JsonObject.floatOrNull(key: String): Float? {
        val e = get(key) ?: return null
        if (!e.isJsonPrimitive) return null
        return runCatching { e.asFloat }.getOrNull()?.takeIf { it.isFinite() }
    }

    private fun JsonObject.booleanOrNull(key: String): Boolean? {
        val e = get(key) ?: return null
        if (!e.isJsonPrimitive) return null
        return runCatching { e.asBoolean }.getOrNull()
    }
}
