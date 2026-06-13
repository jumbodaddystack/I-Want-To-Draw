package com.aichat.sandbox.data.notes

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Sub-phase 7.2 — parser for the model's `edit-ops` reply.
 *
 * Models that obey the system message return exactly:
 * ```
 * ```edit-ops
 * { "schema": 1, "summary": "…", "ops": [ … ] }
 * ```
 * ```
 *
 * Models that drift wrap the block in narration, repeat the fence, or skip
 * the fence entirely. The parser tolerates all three: it extracts the first
 * recognisable JSON object that has a top-level `"ops"` array, and treats
 * anything else as parse failure.
 *
 * Defence in depth: the parser **never throws**. Every failure path returns
 * a [Result.failure] with a short reason; the caller surfaces that in the
 * AI side sheet. Fuzz-safe; the safety checklist (7.6) requires 1000 random
 * malformations not to crash.
 */
object EditOpsParser {

    private val FENCE_REGEX: Regex =
        Regex("```(?:edit-ops|edit_ops|json)?\\s*\\n?([\\s\\S]*?)```", RegexOption.IGNORE_CASE)

    /**
     * Parse the model's reply [raw] into a typed document.
     *
     * The optional [knownIds] / [knownLayers] sets let the parser drop ops
     * that reference items the model invented. Pass `null` to accept any
     * referenced id — useful for unit tests.
     */
    fun parse(
        raw: String,
        knownIds: Set<String>? = null,
        knownLayers: Set<String>? = null,
    ): Result<EditOpsDoc> {
        if (raw.isBlank()) return Result.failure(IllegalArgumentException("empty reply"))
        val jsonText = extractJson(raw)
            ?: return Result.failure(IllegalArgumentException("no JSON block found in reply"))
        return try {
            val root = JsonParser.parseString(jsonText) as? JsonObject
                ?: return Result.failure(IllegalArgumentException("top-level JSON is not an object"))
            Result.success(parseDoc(root, knownIds, knownLayers))
        } catch (t: Throwable) {
            Result.failure(IllegalArgumentException("malformed JSON: ${t.message}"))
        }
    }

    /**
     * Extract the JSON body from a reply that may be plain JSON or fenced.
     * Returns `null` when no JSON-looking content is present.
     */
    internal fun extractJson(raw: String): String? {
        val match = FENCE_REGEX.find(raw)
        if (match != null) return match.groupValues[1].trim()
        // No fence — accept the first balanced `{ … }` block.
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

    private fun parseDoc(
        root: JsonObject,
        knownIds: Set<String>?,
        knownLayers: Set<String>?,
    ): EditOpsDoc {
        val schema = root.get("schema")?.takeIf { it.isJsonPrimitive }?.asInt ?: EditOpsDoc.SCHEMA
        val summary = root.get("summary")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        val opsArr = root.get("ops") as? JsonArray ?: JsonArray()
        val accepted = ArrayList<EditOp>(opsArr.size())
        val rejected = ArrayList<EditOpsDoc.RejectedOp>()
        for (el in opsArr) {
            val obj = el as? JsonObject
            if (obj == null) {
                rejected += EditOpsDoc.RejectedOp(el.toString(), "op is not an object")
                continue
            }
            try {
                val op = parseOp(obj, knownIds, knownLayers)
                if (op != null) accepted += op
                else rejected += EditOpsDoc.RejectedOp(obj.toString(), "unknown op")
            } catch (t: Throwable) {
                rejected += EditOpsDoc.RejectedOp(obj.toString(), t.message ?: "parse error")
            }
        }
        return EditOpsDoc(
            schema = schema,
            summary = summary,
            ops = accepted,
            rejected = rejected,
        )
    }

    private fun parseOp(
        obj: JsonObject,
        knownIds: Set<String>?,
        knownLayers: Set<String>?,
    ): EditOp? {
        val opName = obj.get("op")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        return when (opName) {
            "transform" -> {
                val ids = parseIdList(obj, knownIds)
                if (ids.isEmpty()) throw IllegalArgumentException("transform: no valid ids")
                val matrix = parseMatrix(obj.get("matrix"))
                EditOp.Transform(ids, matrix)
            }
            "recolor" -> {
                val ids = parseIdList(obj, knownIds)
                if (ids.isEmpty()) throw IllegalArgumentException("recolor: no valid ids")
                val color = parseColor(obj.get("color")?.asString)
                EditOp.Recolor(ids, color)
            }
            "restyle" -> {
                val ids = parseIdList(obj, knownIds)
                if (ids.isEmpty()) throw IllegalArgumentException("restyle: no valid ids")
                val width = obj.get("width")?.takeIf { it.isJsonPrimitive }?.asFloat
                val opacity = obj.get("opacity")?.takeIf { it.isJsonPrimitive }?.asFloat
                if (width == null && opacity == null) {
                    throw IllegalArgumentException("restyle: missing width/opacity")
                }
                EditOp.Restyle(ids, width, opacity?.coerceIn(0f, 1f))
            }
            "replace_with_shape" -> {
                val id = obj.get("id")?.asString
                    ?: throw IllegalArgumentException("replace_with_shape: missing id")
                if (knownIds != null && id !in knownIds) {
                    throw IllegalArgumentException("replace_with_shape: unknown id $id")
                }
                val shape = parseShape(obj.get("shape") as? JsonObject
                    ?: throw IllegalArgumentException("replace_with_shape: missing shape"))
                EditOp.ReplaceWithShape(id, shape)
            }
            "smooth" -> {
                val ids = parseIdList(obj, knownIds)
                if (ids.isEmpty()) throw IllegalArgumentException("smooth: no valid ids")
                val amount = (obj.get("amount")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0.5f)
                    .coerceIn(0f, 1f)
                EditOp.Smooth(ids, amount)
            }
            "simplify" -> {
                val ids = parseIdList(obj, knownIds)
                if (ids.isEmpty()) throw IllegalArgumentException("simplify: no valid ids")
                val tolerance = (obj.get("tolerance")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 1f)
                    .coerceAtLeast(0f)
                EditOp.Simplify(ids, tolerance)
            }
            "merge_paths" -> {
                val ids = parseIdList(obj, knownIds)
                if (ids.size < 2) throw IllegalArgumentException("merge_paths: need ≥2 ids")
                EditOp.MergePaths(ids)
            }
            "delete" -> {
                val ids = parseIdList(obj, knownIds)
                if (ids.isEmpty()) throw IllegalArgumentException("delete: no valid ids")
                EditOp.Delete(ids)
            }
            "set_layer" -> {
                val ids = parseIdList(obj, knownIds)
                if (ids.isEmpty()) throw IllegalArgumentException("set_layer: no valid ids")
                val target = obj.get("layer")?.asString
                    ?: throw IllegalArgumentException("set_layer: missing layer")
                if (knownLayers != null && target !in knownLayers) {
                    throw IllegalArgumentException("set_layer: unknown layer $target")
                }
                EditOp.SetLayer(ids, target)
            }
            "group" -> {
                val ids = parseIdList(obj, knownIds)
                if (ids.isEmpty()) throw IllegalArgumentException("group: no valid ids")
                EditOp.Group(ids)
            }
            else -> null
        }
    }

    private fun parseIdList(obj: JsonObject, knownIds: Set<String>?): List<String> {
        val arr = obj.get("ids") as? JsonArray ?: return emptyList()
        val out = ArrayList<String>(arr.size())
        for (el in arr) {
            val s = el?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            if (knownIds == null || s in knownIds) out += s
        }
        return out
    }

    private fun parseMatrix(el: JsonElement?): FloatArray {
        val arr = el as? JsonArray
            ?: throw IllegalArgumentException("transform: matrix must be a JSON array")
        if (arr.size() != 6 && arr.size() != 9) {
            throw IllegalArgumentException("transform: matrix must have 6 or 9 floats")
        }
        val out = FloatArray(9)
        if (arr.size() == 9) {
            for (i in 0 until 9) out[i] = arr[i].asFloat
        } else {
            // 6-float compact form `[a, b, tx, c, d, ty]` → 3×3 with [0,0,1].
            out[0] = arr[0].asFloat; out[1] = arr[1].asFloat; out[2] = arr[2].asFloat
            out[3] = arr[3].asFloat; out[4] = arr[4].asFloat; out[5] = arr[5].asFloat
            out[6] = 0f; out[7] = 0f; out[8] = 1f
        }
        return out
    }

    private fun parseColor(hex: String?): Int {
        if (hex.isNullOrBlank()) throw IllegalArgumentException("color: missing hex")
        val raw = hex.trim().removePrefix("#")
        return when (raw.length) {
            6 -> 0xFF000000.toInt() or raw.toLong(16).toInt()
            8 -> raw.toLong(16).toInt()
            3 -> {
                val r = raw[0].digitToInt(16) * 17
                val g = raw[1].digitToInt(16) * 17
                val b = raw[2].digitToInt(16) * 17
                (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            else -> throw IllegalArgumentException("color: malformed hex '$hex'")
        }
    }

    private fun parseShape(obj: JsonObject): EditOp.ShapeSpec {
        val type = obj.get("type")?.asString?.lowercase()
            ?: throw IllegalArgumentException("shape: missing type")
        return when (type) {
            "line" -> EditOp.ShapeSpec.Line(
                obj.get("x0").asFloat, obj.get("y0").asFloat,
                obj.get("x1").asFloat, obj.get("y1").asFloat,
            )
            "rect", "rectangle" -> EditOp.ShapeSpec.Rect(
                obj.get("x0").asFloat, obj.get("y0").asFloat,
                obj.get("x1").asFloat, obj.get("y1").asFloat,
                obj.get("r")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0f,
            )
            "ellipse", "circle" -> {
                val cx = obj.get("cx").asFloat
                val cy = obj.get("cy").asFloat
                val rx = obj.get("rx")?.takeIf { it.isJsonPrimitive }?.asFloat
                    ?: obj.get("r")?.takeIf { it.isJsonPrimitive }?.asFloat
                    ?: throw IllegalArgumentException("ellipse: missing rx/r")
                val ry = obj.get("ry")?.takeIf { it.isJsonPrimitive }?.asFloat
                    ?: obj.get("r")?.takeIf { it.isJsonPrimitive }?.asFloat
                    ?: rx
                EditOp.ShapeSpec.Ellipse(
                    cx = cx, cy = cy, rx = rx, ry = ry,
                    rotation = obj.get("rotation")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0f,
                )
            }
            "arrow" -> EditOp.ShapeSpec.Arrow(
                obj.get("x0").asFloat, obj.get("y0").asFloat,
                obj.get("x1").asFloat, obj.get("y1").asFloat,
                obj.get("head")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0f,
            )
            "polygon", "polyline" -> {
                val pts = obj.get("points") as? JsonArray
                    ?: throw IllegalArgumentException("polygon: missing points")
                val flat = ArrayList<Float>(pts.size() * 2)
                for (el in pts) {
                    val pair = el as? JsonArray
                        ?: throw IllegalArgumentException("polygon: each point must be a 2-array")
                    if (pair.size() < 2) throw IllegalArgumentException("polygon: short point")
                    flat += pair[0].asFloat
                    flat += pair[1].asFloat
                }
                val closed = obj.get("closed")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: (type == "polygon")
                EditOp.ShapeSpec.Polygon(flat.toFloatArray(), closed)
            }
            else -> throw IllegalArgumentException("shape: unknown type '$type'")
        }
    }

    /**
     * The Phase 7.2 system message the EDIT request injects. Kept here so
     * tests can assert the prompt content without reaching into
     * [NoteAiService].
     */
    const val SYSTEM_MESSAGE: String =
        "You are an assistant that edits the user's hand-drawn note. You receive " +
            "the note as both an image and a JSON description of every item by ID. " +
            "Reply with ONLY a fenced ```edit-ops block matching this schema:\n\n" +
            "{ \"schema\": 1, \"summary\": \"<one short sentence>\",\n" +
            "  \"ops\": [ /* operations referencing items by ID */ ] }\n\n" +
            "Rules:\n" +
            "- Modify only items that appear in the provided JSON.\n" +
            "- Do not invent new strokes from scratch. To turn a freehand stroke into " +
            "a clean shape, use `replace_with_shape` referencing the original ID.\n" +
            "- Do not target items on locked or hidden layers.\n" +
            "- If you can't fulfil the request, return an empty ops array and explain " +
            "in `summary`. Never reply outside the fenced block."

    /**
     * Icon-mode variant of [SYSTEM_MESSAGE]. Same `edit-ops` schema and rules,
     * but guidance tuned for designing a clean vector icon on a square
     * artboard. Used when [AskRequest.isIcon] is true.
     */
    const val ICON_SYSTEM_MESSAGE: String =
        "You are an assistant that helps the user turn a rough sketch into a " +
            "clean vector icon. You receive the drawing as both an image and a " +
            "JSON description of every item by ID. The drawing sits on a square " +
            "artboard; keep all geometry inside it and aim for a balanced, " +
            "centred composition. Prefer simple, crisp geometry — straighten " +
            "wobbly strokes into clean shapes with `replace_with_shape`, align " +
            "edges, and keep the icon monochrome unless the user asks for " +
            "colour.\n\n" +
            "Reply with ONLY a fenced ```edit-ops block matching this schema:\n\n" +
            "{ \"schema\": 1, \"summary\": \"<one short sentence>\",\n" +
            "  \"ops\": [ /* operations referencing items by ID */ ] }\n\n" +
            "Rules:\n" +
            "- Modify only items that appear in the provided JSON.\n" +
            "- Do not invent new strokes from scratch. To turn a freehand stroke " +
            "into a clean shape, use `replace_with_shape` referencing the " +
            "original ID.\n" +
            "- Do not target items on locked or hidden layers.\n" +
            "- If you can't fulfil the request, return an empty ops array and " +
            "explain in `summary`. Never reply outside the fenced block."
}
