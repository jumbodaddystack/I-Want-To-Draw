package com.aichat.sandbox.data.vector

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Phase 4 — strict, tolerant parser for the model's `vector-edit-plan` reply.
 *
 * Mirrors `data/notes/EditOpsParser`: the model is asked to return exactly a
 * fenced block, but real replies wrap it in prose, repeat the fence, omit the
 * language tag, or drop the fence entirely. [parse] extracts the first
 * recognisable JSON object (fenced or balanced), then validates every operation
 * against the known path IDs/colors and clamps numeric values into safe ranges.
 *
 * Defence in depth: [parse] **never throws**. Malformed JSON returns
 * [Result.failure] with a short reason; individual operations that can't be
 * accepted are recorded in [VectorEditPlan.rejected] (never thrown), so a single
 * bad op never sinks the whole plan. An empty `operations` array is a valid plan.
 */
object VectorEditPlanParser {

    private val FENCE_REGEX: Regex =
        Regex(
            "```(?:vector-edit-plan|vector_edit_plan|json)?\\s*\\n?([\\s\\S]*?)```",
            RegexOption.IGNORE_CASE,
        )

    private val COLOR_REGEX: Regex =
        Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

    private val LINE_CAPS = setOf("butt", "round", "square")
    private val LINE_JOINS = setOf("miter", "round", "bevel")

    // Safe clamp ranges (see the Phase 4 spec).
    private const val TOLERANCE_MIN = 0f
    private const val TOLERANCE_MAX = 4f
    private const val MIN_PATH_LENGTH_MIN = 0f
    private const val MIN_PATH_LENGTH_MAX = 10f
    private const val STROKE_WIDTH_MIN = 0.1f
    private const val STROKE_WIDTH_MAX = 64f

    // Rejection reasons surfaced to the user.
    private const val REASON_UNKNOWN_OP = "unknown op"
    private const val REASON_UNKNOWN_PATH = "unknown path id"
    private const val REASON_MALFORMED_COLOR = "malformed color"
    private const val REASON_INVALID_TARGET = "invalid target"
    private const val REASON_UNSUPPORTED = "unsupported in Phase 4"
    private const val REASON_MISSING_OP = "missing operation"
    private const val REASON_NO_CHANGES = "operation specifies no changes"

    fun parse(
        raw: String,
        knownPathIds: Set<String>,
        knownColors: Set<String>,
    ): Result<VectorEditPlan> {
        if (raw.isBlank()) return Result.failure(IllegalArgumentException("empty reply"))
        val root = extractObject(raw)
            ?: return Result.failure(IllegalArgumentException("no JSON object found in reply"))
        return try {
            Result.success(parsePlan(root, knownPathIds, knownColors))
        } catch (t: Throwable) {
            Result.failure(IllegalArgumentException("malformed plan: ${t.message}"))
        }
    }

    /**
     * Returns the first JSON object that parses out of [raw]: the fenced block
     * if present and valid, otherwise the first balanced `{ … }` run.
     */
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

    private fun parsePlan(
        root: JsonObject,
        knownPathIds: Set<String>,
        knownColors: Set<String>,
    ): VectorEditPlan {
        val schema = root.intOrNull("schema") ?: VectorEditPlan.SCHEMA
        val mode = when (root.stringOrNull("mode")?.lowercase()) {
            "faithful_cleanup" -> VectorEditPlan.Mode.FAITHFUL_CLEANUP
            else -> VectorEditPlan.Mode.TUNE_UP
        }
        val summary = root.stringOrNull("summary") ?: ""

        val opsArr = root.get("operations") as? JsonArray ?: JsonArray()
        val accepted = ArrayList<VectorEditOperation>(opsArr.size())
        val rejected = ArrayList<VectorEditPlan.RejectedOperation>()

        for (el in opsArr) {
            val obj = el as? JsonObject
            if (obj == null) {
                rejected += VectorEditPlan.RejectedOperation(el.toString(), "operation is not an object")
                continue
            }
            when (val result = parseOperation(obj, knownPathIds, knownColors)) {
                is OpResult.Accepted -> accepted += result.op
                is OpResult.Rejected ->
                    rejected += VectorEditPlan.RejectedOperation(obj.toString(), result.reason)
            }
        }

        return VectorEditPlan(
            schema = schema,
            mode = mode,
            summary = summary,
            operations = accepted,
            rejected = rejected,
        )
    }

    private sealed interface OpResult {
        data class Accepted(val op: VectorEditOperation) : OpResult
        data class Rejected(val reason: String) : OpResult
    }

    private fun parseOperation(
        obj: JsonObject,
        knownPathIds: Set<String>,
        knownColors: Set<String>,
    ): OpResult {
        val opName = obj.stringOrNull("op")?.lowercase()
            ?: return OpResult.Rejected(REASON_MISSING_OP)

        when (opName) {
            "transform_paths", "normalize_viewport" -> return OpResult.Rejected(REASON_UNSUPPORTED)
            "simplify_paths", "remove_paths", "restyle_paths", "recolor_paths" -> Unit
            else -> return OpResult.Rejected(REASON_UNKNOWN_OP)
        }

        val (target, targetReason) = parseTarget(obj.get("target") as? JsonObject, knownPathIds, knownColors)
        if (target == null) return OpResult.Rejected(targetReason ?: REASON_INVALID_TARGET)

        return when (opName) {
            "simplify_paths" -> {
                val tolerance = (obj.floatOrNull("tolerance") ?: 0.25f)
                    .coerceIn(TOLERANCE_MIN, TOLERANCE_MAX)
                val minPathLength = obj.floatOrNull("minPathLength")
                    ?.coerceIn(MIN_PATH_LENGTH_MIN, MIN_PATH_LENGTH_MAX)
                val simplifyFills = obj.booleanOrNull("simplifyFills") ?: false
                OpResult.Accepted(
                    VectorEditOperation.SimplifyPaths(target, tolerance, minPathLength, simplifyFills),
                )
            }
            "remove_paths" -> OpResult.Accepted(VectorEditOperation.RemovePaths(target))
            "restyle_paths" -> {
                val strokeWidth = obj.floatOrNull("strokeWidth")
                    ?.coerceIn(STROKE_WIDTH_MIN, STROKE_WIDTH_MAX)
                val lineCap = obj.stringOrNull("lineCap")?.lowercase()?.takeIf { it in LINE_CAPS }
                val lineJoin = obj.stringOrNull("lineJoin")?.lowercase()?.takeIf { it in LINE_JOINS }
                if (strokeWidth == null && lineCap == null && lineJoin == null) {
                    OpResult.Rejected(REASON_NO_CHANGES)
                } else {
                    OpResult.Accepted(
                        VectorEditOperation.RestylePaths(target, strokeWidth, lineCap, lineJoin),
                    )
                }
            }
            "recolor_paths" -> {
                val rawStroke = obj.stringOrNull("strokeColor")
                val rawFill = obj.stringOrNull("fillColor")
                if (rawStroke == null && rawFill == null) return OpResult.Rejected(REASON_NO_CHANGES)
                val strokeColor = rawStroke?.let { normalizeColor(it) ?: return OpResult.Rejected(REASON_MALFORMED_COLOR) }
                val fillColor = rawFill?.let { normalizeColor(it) ?: return OpResult.Rejected(REASON_MALFORMED_COLOR) }
                OpResult.Accepted(
                    VectorEditOperation.RecolorPaths(target, strokeColor, fillColor),
                )
            }
            else -> OpResult.Rejected(REASON_UNKNOWN_OP)
        }
    }

    /**
     * Returns the validated target, or `(null, reason)` when no valid selector
     * survives. Unknown path IDs and malformed/unknown colors are filtered out;
     * a target that named only unknown IDs is reported distinctly.
     */
    private fun parseTarget(
        targetObj: JsonObject?,
        knownPathIds: Set<String>,
        knownColors: Set<String>,
    ): Pair<VectorPathTarget?, String?> {
        if (targetObj == null) return null to REASON_INVALID_TARGET

        val rawPathIds = stringList(targetObj.get("pathIds"))
        val pathIds = rawPathIds.filter { knownPathIds.isEmpty() || it in knownPathIds }

        val knownColorsLower = knownColors.mapTo(HashSet()) { it.lowercase() }
        val colors = stringList(targetObj.get("colors"))
            .mapNotNull { normalizeColor(it) }
            .filter { knownColors.isEmpty() || it.lowercase() in knownColorsLower }

        val all = targetObj.booleanOrNull("all") ?: false
        val strokedOnly = targetObj.booleanOrNull("strokedOnly") ?: false
        val filledOnly = targetObj.booleanOrNull("filledOnly") ?: false

        if (pathIds.isEmpty() && colors.isEmpty() && !all) {
            // Distinguish "named only unknown ids" from "no selector at all".
            val reason = if (rawPathIds.isNotEmpty()) REASON_UNKNOWN_PATH else REASON_INVALID_TARGET
            return null to reason
        }

        return VectorPathTarget(
            pathIds = pathIds,
            colors = colors,
            all = all,
            strokedOnly = strokedOnly,
            filledOnly = filledOnly,
        ) to null
    }

    private fun normalizeColor(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        return if (COLOR_REGEX.matches(trimmed)) trimmed else null
    }

    private fun stringList(el: com.google.gson.JsonElement?): List<String> {
        val arr = el as? JsonArray ?: return emptyList()
        val out = ArrayList<String>(arr.size())
        for (e in arr) {
            if (e != null && e.isJsonPrimitive) out += e.asString
        }
        return out
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
