package com.aichat.sandbox.data.vector

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Phase 7 — structural (non-raster) diff between two vector versions.
 *
 * Deltas are expressed as `after - before`, so a negative [bytesDelta]/
 * [commandCountDelta] means the "after" version shrank. Color movement is
 * reported as added/removed/retained sets (case-insensitive comparison, original
 * casing preserved). Everything is derived from the deterministic
 * [VectorMetrics] foundation; there is no pixel comparison.
 *
 * @property bytesDelta after.xmlBytes - before.xmlBytes.
 * @property bytesDeltaPercent Percentage change relative to before, or null when
 *   the "before" size is zero.
 * @property colorAdded Colors present in after but not before.
 * @property colorRemoved Colors present in before but not after.
 * @property colorRetained Colors present in both.
 * @property warningDelta after.warnings.size - before.warnings.size.
 * @property boundsChanged True when the geometry bounds moved (or appeared/vanished).
 * @property summary A human-readable one/two line description.
 */
data class VectorVersionDiff(
    val bytesDelta: Int,
    val bytesDeltaPercent: Float?,
    val pathCountDelta: Int,
    val commandCountDelta: Int,
    val colorAdded: List<String>,
    val colorRemoved: List<String>,
    val colorRetained: List<String>,
    val warningDelta: Int,
    val boundsChanged: Boolean,
    val summary: String,
)

object VectorVersionDiffAnalyzer {

    private const val BOUNDS_EPS = 0.01f

    fun diff(
        before: VectorVersionQualityInput,
        after: VectorVersionQualityInput,
    ): VectorVersionDiff {
        val b = before.metrics
        val a = after.metrics

        val bytesDelta = a.xmlBytes - b.xmlBytes
        val bytesDeltaPercent = if (b.xmlBytes > 0) {
            (bytesDelta.toFloat() / b.xmlBytes) * 100f
        } else null

        val pathCountDelta = a.pathCount - b.pathCount
        val commandCountDelta = a.commandCount - b.commandCount
        val warningDelta = a.warnings.size - b.warnings.size

        val beforeColors = colorList(b)
        val afterColors = colorList(a)
        val beforeLower = beforeColors.map { it.lowercase() }.toSet()
        val afterLower = afterColors.map { it.lowercase() }.toSet()

        val colorAdded = afterColors.filter { it.lowercase() !in beforeLower }
        val colorRemoved = beforeColors.filter { it.lowercase() !in afterLower }
        val colorRetained = afterColors.filter { it.lowercase() in beforeLower }

        val boundsChanged = boundsChanged(b.bounds, a.bounds)

        val summary = buildSummary(
            bytesDelta = bytesDelta,
            bytesDeltaPercent = bytesDeltaPercent,
            pathCountDelta = pathCountDelta,
            commandCountDelta = commandCountDelta,
            colorAdded = colorAdded,
            colorRemoved = colorRemoved,
            warningDelta = warningDelta,
            boundsChanged = boundsChanged,
            beforePaths = b.pathCount,
        )

        return VectorVersionDiff(
            bytesDelta = bytesDelta,
            bytesDeltaPercent = bytesDeltaPercent,
            pathCountDelta = pathCountDelta,
            commandCountDelta = commandCountDelta,
            colorAdded = colorAdded,
            colorRemoved = colorRemoved,
            colorRetained = colorRetained,
            warningDelta = warningDelta,
            boundsChanged = boundsChanged,
            summary = summary,
        )
    }

    private fun colorList(m: VectorMetrics): List<String> = m.colorCounts.keys.toList()

    private fun boundsChanged(b: VectorBounds?, a: VectorBounds?): Boolean = when {
        b == null && a == null -> false
        b == null || a == null -> true
        else -> abs(b.minX - a.minX) > BOUNDS_EPS ||
            abs(b.minY - a.minY) > BOUNDS_EPS ||
            abs(b.maxX - a.maxX) > BOUNDS_EPS ||
            abs(b.maxY - a.maxY) > BOUNDS_EPS
    }

    private fun buildSummary(
        bytesDelta: Int,
        bytesDeltaPercent: Float?,
        pathCountDelta: Int,
        commandCountDelta: Int,
        colorAdded: List<String>,
        colorRemoved: List<String>,
        warningDelta: Int,
        boundsChanged: Boolean,
        beforePaths: Int,
    ): String {
        val structural = abs(pathCountDelta) >= 3 ||
            (beforePaths > 0 && abs(pathCountDelta).toFloat() / beforePaths >= 0.5f) ||
            colorAdded.size + colorRemoved.size >= 3

        val noChange = bytesDelta == 0 && pathCountDelta == 0 && commandCountDelta == 0 &&
            colorAdded.isEmpty() && colorRemoved.isEmpty() && warningDelta == 0 && !boundsChanged
        if (noChange) return "No structural changes between these versions."

        val parts = ArrayList<String>()
        when {
            bytesDelta < 0 -> parts += "Smaller by ${-bytesDelta} bytes${pct(bytesDeltaPercent)}"
            bytesDelta > 0 -> parts += "Larger by $bytesDelta bytes${pct(bytesDeltaPercent)}"
        }
        when {
            commandCountDelta < 0 -> parts += "${-commandCountDelta} fewer commands"
            commandCountDelta > 0 -> parts += "$commandCountDelta more commands"
        }
        when {
            pathCountDelta < 0 -> parts += "${-pathCountDelta} fewer paths"
            pathCountDelta > 0 -> parts += "$pathCountDelta more paths"
        }
        if (colorAdded.isNotEmpty()) parts += "added ${colorAdded.size} color(s)"
        if (colorRemoved.isNotEmpty()) parts += "removed ${colorRemoved.size} color(s)"
        if (warningDelta != 0) {
            parts += if (warningDelta < 0) "${-warningDelta} fewer warnings" else "$warningDelta more warnings"
        }
        if (boundsChanged) parts += "bounds shifted"

        val lead = if (structural) "Large structural change: " else ""
        return lead + parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }

    private fun pct(p: Float?): String =
        if (p == null) "" else " (${if (p > 0) "+" else ""}${(p).roundToInt()}%)"
}
