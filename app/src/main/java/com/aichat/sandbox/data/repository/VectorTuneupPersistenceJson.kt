package com.aichat.sandbox.data.repository

import com.aichat.sandbox.data.vector.VectorMetrics
import com.aichat.sandbox.data.vector.VectorWarning
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Phase 6 — Gson (de)serialization for the parts of a persisted vector version
 * that the deterministic foundation produces as rich objects: [VectorMetrics]
 * and `List<VectorWarning>`. Both round-trip through plain Gson because their
 * fields are all Gson-friendly (primitives, a `Map<String, Int>`, a nullable
 * `VectorBounds`, and a list of data classes).
 *
 * Reads are defensive: a malformed or empty blob falls back to an empty value
 * rather than throwing, so a corrupt row can never crash the workspace.
 */
object VectorTuneupPersistenceJson {

    private val gson = Gson()
    private val warningsType = object : TypeToken<List<VectorWarning>>() {}.type

    /** Stable empty metrics used when a stored blob cannot be parsed. */
    val EMPTY_METRICS = VectorMetrics(
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

    fun metricsToJson(metrics: VectorMetrics): String = gson.toJson(metrics)

    fun metricsFromJson(json: String): VectorMetrics =
        runCatching { gson.fromJson(json, VectorMetrics::class.java) }.getOrNull() ?: EMPTY_METRICS

    fun warningsToJson(warnings: List<VectorWarning>): String = gson.toJson(warnings)

    fun warningsFromJson(json: String): List<VectorWarning> =
        runCatching { gson.fromJson<List<VectorWarning>>(json, warningsType) }.getOrNull() ?: emptyList()
}
