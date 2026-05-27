package com.aichat.sandbox.data.vector

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Parsed, still-untrusted contents of a portable Vector Art Tune-Up bundle
 * (Phase 10). The raw version IDs/parent IDs from the JSON are preserved so the
 * validator can rebuild the version graph and report id remapping/parent repair.
 */
data class VectorPortableBundleData(
    val schema: Int,
    val kind: String,
    val project: VectorPortableBundle.ProjectInfo,
    val versions: List<VectorPortableBundle.VersionInfo>,
)

/**
 * Outcome of [VectorPortableBundleParser.parse]: [bundle] is non-null only when
 * the JSON was a structurally valid schema-1 `vector_tuneup_project` with a
 * project object and at least one version. [warnings] explain any rejection or
 * partial issue and are always safe to surface to the user.
 */
data class VectorPortableBundleParseResult(
    val bundle: VectorPortableBundleData?,
    val warnings: List<VectorWarning>,
)

/**
 * Parses portable bundle JSON produced by [VectorPortableBundle.build] (Phase 9
 * export) back into a [VectorPortableBundleData] (Phase 10 import).
 *
 * Deliberately defensive: it never throws from [parse]. Malformed JSON, the
 * wrong `kind`, an unsupported `schema`, a missing project, or an empty version
 * list all yield `bundle = null` plus a stable warning code. Field-level issues
 * that still allow import (e.g. a blank/garbled version row) are dropped with a
 * warning while keeping the rest of the bundle. Version order from the JSON is
 * preserved, and each version's raw id/parentId is kept verbatim for the
 * validator's graph repair.
 */
object VectorPortableBundleParser {

    fun parse(json: String): VectorPortableBundleParseResult {
        val root: JsonObject = runCatching {
            JsonParser.parseString(json).asJsonObject
        }.getOrNull() ?: return failed(
            VectorWarning.Codes.BUNDLE_PARSE_FAILED,
            "Could not read this project bundle. Paste the full bundle JSON.",
        )

        val schema = root.optInt("schema")
        if (schema == null) {
            return failed(
                VectorWarning.Codes.BUNDLE_PARSE_FAILED,
                "This file is missing a bundle schema and is not a project bundle.",
            )
        }
        if (schema != VectorPortableBundle.SCHEMA) {
            return failed(
                VectorWarning.Codes.BUNDLE_UNSUPPORTED_SCHEMA,
                "Unsupported project bundle schema $schema (expected ${VectorPortableBundle.SCHEMA}).",
            )
        }

        val kind = root.optString("kind")
        if (kind != VectorPortableBundle.KIND) {
            return failed(
                VectorWarning.Codes.BUNDLE_WRONG_KIND,
                "This JSON is not a Vector Tune-Up project bundle.",
            )
        }

        val projectObj = root.optObject("project")
            ?: return failed(
                VectorWarning.Codes.BUNDLE_PARSE_FAILED,
                "This project bundle is missing its project information.",
            )
        val project = VectorPortableBundle.ProjectInfo(
            title = projectObj.optString("title").orEmpty(),
            createdAt = projectObj.optLong("createdAt") ?: 0L,
            updatedAt = projectObj.optLong("updatedAt") ?: 0L,
        )

        val warnings = ArrayList<VectorWarning>()
        val versionsArray = root.optArray("versions")
        if (versionsArray == null || versionsArray.size() == 0) {
            return failed(
                VectorWarning.Codes.BUNDLE_EMPTY,
                "This project bundle has no versions to import.",
            )
        }

        val versions = ArrayList<VectorPortableBundle.VersionInfo>(versionsArray.size())
        for (idx in 0 until versionsArray.size()) {
            val obj = runCatching { versionsArray[idx].asJsonObject }.getOrNull()
            if (obj == null) {
                warnings += VectorWarning(
                    VectorWarning.Codes.BUNDLE_VERSION_INVALID,
                    "Skipped a malformed version entry (#${idx + 1}).",
                )
                continue
            }
            val id = obj.optString("id")?.takeIf { it.isNotBlank() }
            if (id == null) {
                warnings += VectorWarning(
                    VectorWarning.Codes.BUNDLE_VERSION_INVALID,
                    "Skipped a version with no id (#${idx + 1}).",
                )
                continue
            }
            versions += VectorPortableBundle.VersionInfo(
                id = id,
                parentId = obj.optString("parentId")?.takeIf { it.isNotBlank() },
                label = obj.optString("label").orEmpty().ifBlank { "Version" },
                mode = obj.optString("mode").orEmpty(),
                instruction = obj.optString("instruction").orEmpty(),
                xml = obj.optString("xml").orEmpty(),
                metrics = parseMetrics(obj.optObject("metrics")),
                warnings = parseWarnings(obj),
                reportSummary = obj.optString("reportSummary"),
                createdAt = obj.optLong("createdAt") ?: 0L,
            )
        }

        if (versions.isEmpty()) {
            return failed(
                VectorWarning.Codes.BUNDLE_EMPTY,
                "This project bundle had no usable versions to import.",
            )
        }

        return VectorPortableBundleParseResult(
            bundle = VectorPortableBundleData(
                schema = schema,
                kind = kind,
                project = project,
                versions = versions,
            ),
            warnings = warnings,
        )
    }

    private fun parseMetrics(obj: JsonObject?): VectorMetrics {
        if (obj == null) return EMPTY_METRICS
        return runCatching { GSON.fromJson(obj, VectorMetrics::class.java) }
            .getOrNull() ?: EMPTY_METRICS
    }

    private fun parseWarnings(versionObj: JsonObject): List<VectorWarning> {
        val arr = versionObj.optArray("warnings") ?: return emptyList()
        return runCatching { GSON.fromJson(arr, WARNINGS_TYPE) as List<VectorWarning> }
            .getOrNull() ?: emptyList()
    }

    private fun failed(code: String, message: String): VectorPortableBundleParseResult =
        VectorPortableBundleParseResult(
            bundle = null,
            warnings = listOf(VectorWarning(code, message)),
        )

    // ---- lenient JsonObject accessors (never throw) ----

    private fun JsonObject.optInt(name: String): Int? =
        runCatching { get(name)?.takeUnless { it.isJsonNull }?.asInt }.getOrNull()

    private fun JsonObject.optLong(name: String): Long? =
        runCatching { get(name)?.takeUnless { it.isJsonNull }?.asLong }.getOrNull()

    private fun JsonObject.optString(name: String): String? =
        runCatching { get(name)?.takeUnless { it.isJsonNull }?.asString }.getOrNull()

    private fun JsonObject.optObject(name: String): JsonObject? =
        runCatching { getAsJsonObject(name) }.getOrNull()

    private fun JsonObject.optArray(name: String) =
        runCatching { getAsJsonArray(name) }.getOrNull()

    private val GSON = com.google.gson.Gson()
    private val WARNINGS_TYPE =
        com.google.gson.reflect.TypeToken.getParameterized(List::class.java, VectorWarning::class.java).type

    private val EMPTY_METRICS = VectorMetrics(
        xmlBytes = 0,
        pathCount = 0,
        groupCount = 0,
        commandCount = 0,
        parsedCommandCount = 0,
        unsupportedPathCount = 0,
        estimatedPointCount = 0,
        colorCounts = emptyMap(),
        strokePathCount = 0,
        fillPathCount = 0,
        zeroLengthPathCount = 0,
        tinySegmentEstimate = 0,
        duplicateCoordinateEstimate = 0,
        bounds = null,
        warnings = emptyList(),
    )
}
