package com.aichat.sandbox.data.vector

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Phase 7 — deterministic, advisory quality scores for a vector version.
 *
 * Each sub-score is in `0.0` (poor) .. `1.0` (excellent) and is derived purely
 * from the deterministic [VectorMetrics] + [VectorDocument] foundation: there is
 * no raster rendering and no AI. Scores are explainable — every meaningful
 * penalty contributes a human-readable line to [VectorQualityScores.notes].
 *
 * @property cleanliness How free of point noise/duplicate/zero-length geometry.
 * @property faithfulness Structural similarity to the supplied original, or null
 *   when no original is available to compare against.
 * @property iconReadiness How icon-like the result is (sane viewport, few colors,
 *   bounds inside the viewport, no malformed paths).
 * @property fileEfficiency How lean the XML is for the structure it represents.
 * @property maintainability How easy the result is to hand-edit (few, named paths,
 *   limited colors, low command complexity).
 * @property overall Weighted blend of the above.
 * @property notes Advisory, human-readable explanations.
 */
data class VectorQualityScores(
    val cleanliness: Float,
    val faithfulness: Float?,
    val iconReadiness: Float,
    val fileEfficiency: Float,
    val maintainability: Float,
    val overall: Float,
    val notes: List<String>,
)

/** A single version's inputs for scoring/diffing: its XML, parsed doc, metrics. */
data class VectorVersionQualityInput(
    val xml: String,
    val document: VectorDocument,
    val metrics: VectorMetrics,
)

object VectorQualityScorer {

    // Overall blend weights (faithfulness redistributed proportionally if absent).
    private const val W_CLEANLINESS = 0.25f
    private const val W_ICON = 0.25f
    private const val W_EFFICIENCY = 0.20f
    private const val W_MAINTAINABILITY = 0.15f
    private const val W_FAITHFULNESS = 0.15f

    fun score(
        original: VectorVersionQualityInput?,
        candidate: VectorVersionQualityInput,
    ): VectorQualityScores {
        val notes = ArrayList<String>()
        val m = candidate.metrics

        val cleanliness = scoreCleanliness(m, notes)
        val iconReadiness = scoreIconReadiness(candidate, notes)
        val fileEfficiency = scoreFileEfficiency(m, notes)
        val maintainability = scoreMaintainability(candidate, notes)
        val faithfulness = original?.let { scoreFaithfulness(it, candidate, notes) }

        if (faithfulness == null) {
            notes += "No original to compare against; faithfulness omitted."
        }

        val overall = blendOverall(
            cleanliness = cleanliness,
            iconReadiness = iconReadiness,
            fileEfficiency = fileEfficiency,
            maintainability = maintainability,
            faithfulness = faithfulness,
        )

        return VectorQualityScores(
            cleanliness = clamp01(cleanliness),
            faithfulness = faithfulness?.let { clamp01(it) },
            iconReadiness = clamp01(iconReadiness),
            fileEfficiency = clamp01(fileEfficiency),
            maintainability = clamp01(maintainability),
            overall = clamp01(overall),
            notes = notes,
        )
    }

    // ---- cleanliness ----

    private fun scoreCleanliness(m: VectorMetrics, notes: MutableList<String>): Float {
        val paths = m.pathCount.coerceAtLeast(1)
        val commands = m.commandCount.coerceAtLeast(1)

        val tiny = ratio(m.tinySegmentEstimate, commands)
        val dup = ratio(m.duplicateCoordinateEstimate, commands)
        val zero = ratio(m.zeroLengthPathCount, paths)
        val unsupported = ratio(m.unsupportedPathCount, paths)
        val warn = (m.warnings.size * 0.04f).coerceIn(0f, 0.4f)
        val cmdPerPath = commands.toFloat() / paths
        val complexity = ((cmdPerPath - 12f) / 120f).coerceIn(0f, 0.5f)

        if (m.tinySegmentEstimate > 0) notes += "${m.tinySegmentEstimate} tiny segment(s) add noise."
        if (m.duplicateCoordinateEstimate > 0) {
            notes += "${m.duplicateCoordinateEstimate} duplicate coordinate(s) detected."
        }
        if (m.zeroLengthPathCount > 0) notes += "${m.zeroLengthPathCount} zero-length path(s)."
        if (cmdPerPath > 40f) {
            notes += "High command density (~${cmdPerPath.toInt()} commands/path)."
        }

        return 1f - (tiny * 1.2f + dup * 1.2f + zero * 0.6f + unsupported * 0.8f + warn + complexity)
    }

    // ---- icon readiness ----

    private fun scoreIconReadiness(input: VectorVersionQualityInput, notes: MutableList<String>): Float {
        val m = input.metrics
        val vp = input.document.viewport
        var penalty = 0f

        val w = vp.viewportWidth
        val h = vp.viewportHeight
        if (w <= 0f || h <= 0f) {
            penalty += 0.4f
            notes += "Viewport is degenerate; not icon-ready."
        } else {
            if (w < 8f || h < 8f || w > 1024f || h > 1024f) {
                penalty += 0.15f
                notes += "Viewport size (${trim(w)}x${trim(h)}) is unusual for an icon."
            }
            val aspect = max(w, h) / min(w, h)
            if (aspect > 4f) {
                penalty += 0.15f
                notes += "Extreme aspect ratio for an icon."
            }
            // Bounds well outside the viewport hurt icon readiness.
            m.bounds?.let { b ->
                val overflow = max(
                    max(-b.minX, -b.minY),
                    max(b.maxX - w, b.maxY - h),
                )
                val span = max(w, h)
                if (overflow > 0f && span > 0f) {
                    val rel = (overflow / span).coerceIn(0f, 1f)
                    penalty += (rel * 0.4f).coerceAtMost(0.3f)
                    notes += "Geometry extends outside the viewport."
                }
            }
        }

        val colorCount = m.colorCounts.size
        if (colorCount > 8) {
            penalty += ((colorCount - 8) / 16f).coerceIn(0f, 0.25f)
            notes += "$colorCount colors is a lot for a clean icon."
        }
        if (m.pathCount > 40) {
            penalty += ((m.pathCount - 40) / 200f).coerceIn(0f, 0.25f)
        }
        if (m.unsupportedPathCount > 0) {
            penalty += ratio(m.unsupportedPathCount, m.pathCount.coerceAtLeast(1)) * 0.4f
            notes += "${m.unsupportedPathCount} unsupported path(s) reduce icon readiness."
        }
        penalty += (m.warnings.size * 0.03f).coerceIn(0f, 0.2f)

        return 1f - penalty
    }

    // ---- file efficiency ----

    private fun scoreFileEfficiency(m: VectorMetrics, notes: MutableList<String>): Float {
        val paths = m.pathCount.coerceAtLeast(1)
        val commands = m.commandCount.coerceAtLeast(1)
        val cmdPerPath = commands.toFloat() / paths
        val bytesPerPath = m.xmlBytes.toFloat() / paths

        val complexity = (cmdPerPath / 120f).coerceIn(0f, 0.6f)
        val bytesPenalty = (bytesPerPath / 6000f).coerceIn(0f, 0.4f)
        val noise = (ratio(m.tinySegmentEstimate, commands) + ratio(m.duplicateCoordinateEstimate, commands)) * 0.3f

        if (bytesPerPath > 4000f) {
            notes += "Large XML footprint (~${bytesPerPath.toInt()} bytes/path)."
        }

        return 1f - (complexity + bytesPenalty + noise)
    }

    // ---- maintainability ----

    private fun scoreMaintainability(input: VectorVersionQualityInput, notes: MutableList<String>): Float {
        val m = input.metrics
        val allPaths = input.document.allPaths()
        val paths = m.pathCount.coerceAtLeast(1)
        val commands = m.commandCount.coerceAtLeast(1)

        val named = allPaths.count { !it.name.isNullOrBlank() }
        val namedRatio = if (allPaths.isEmpty()) 1f else named.toFloat() / allPaths.size
        val cmdPerPath = commands.toFloat() / paths

        val pathPenalty = (m.pathCount / 120f).coerceIn(0f, 0.4f)
        val colorPenalty = ((m.colorCounts.size - 6) / 20f).coerceIn(0f, 0.2f)
        val complexity = (cmdPerPath / 150f).coerceIn(0f, 0.4f)
        val warn = (m.warnings.size * 0.03f).coerceIn(0f, 0.2f)

        if (namedRatio < 0.5f && allPaths.isNotEmpty()) {
            notes += "Few paths are named; harder to hand-edit."
        }

        return 0.6f + namedRatio * 0.4f - (pathPenalty + colorPenalty + complexity + warn)
    }

    // ---- faithfulness ----

    private fun scoreFaithfulness(
        original: VectorVersionQualityInput,
        candidate: VectorVersionQualityInput,
        notes: MutableList<String>,
    ): Float {
        val before = original.metrics
        val after = candidate.metrics
        val bvp = original.document.viewport
        val avp = candidate.document.viewport

        val components = ArrayList<Pair<Float, Float>>() // value, weight

        val viewportSim = if (close(bvp.viewportWidth, avp.viewportWidth) &&
            close(bvp.viewportHeight, avp.viewportHeight)
        ) 1f else {
            val wr = ratioSim(bvp.viewportWidth, avp.viewportWidth)
            val hr = ratioSim(bvp.viewportHeight, avp.viewportHeight)
            (wr + hr) / 2f
        }
        components += viewportSim to 0.15f

        val colorSim = jaccard(colorKeys(before), colorKeys(after))
        components += colorSim to 0.30f

        val pathSim = 1f - ratio(abs(after.pathCount - before.pathCount), before.pathCount.coerceAtLeast(1))
        components += pathSim to 0.25f

        val strokeFillSim = run {
            val s = ratioSim(before.strokePathCount.toFloat(), after.strokePathCount.toFloat())
            val f = ratioSim(before.fillPathCount.toFloat(), after.fillPathCount.toFloat())
            (s + f) / 2f
        }
        components += strokeFillSim to 0.10f

        if (before.bounds != null && after.bounds != null) {
            components += boundsIoU(before.bounds, after.bounds) to 0.15f
        }

        val cmdRatio = after.commandCount.toFloat() / before.commandCount.coerceAtLeast(1)
        val commandSanity = if (cmdRatio > 1.5f) {
            (1f - (cmdRatio - 1.5f) * 0.3f).coerceIn(0f, 1f)
        } else 1f
        components += commandSanity to 0.05f

        val totalWeight = components.sumOf { it.second.toDouble() }.toFloat()
        val value = if (totalWeight <= 0f) 1f else {
            components.sumOf { (it.first * it.second).toDouble() }.toFloat() / totalWeight
        }

        if (colorSim < 0.6f) notes += "Color palette changed substantially from the original."
        if (pathSim < 0.6f) notes += "Path structure changed substantially from the original."

        return value
    }

    private fun blendOverall(
        cleanliness: Float,
        iconReadiness: Float,
        fileEfficiency: Float,
        maintainability: Float,
        faithfulness: Float?,
    ): Float {
        var sum = clamp01(cleanliness) * W_CLEANLINESS +
            clamp01(iconReadiness) * W_ICON +
            clamp01(fileEfficiency) * W_EFFICIENCY +
            clamp01(maintainability) * W_MAINTAINABILITY
        var weight = W_CLEANLINESS + W_ICON + W_EFFICIENCY + W_MAINTAINABILITY
        if (faithfulness != null) {
            sum += clamp01(faithfulness) * W_FAITHFULNESS
            weight += W_FAITHFULNESS
        }
        return if (weight <= 0f) 0f else sum / weight
    }

    // ---- helpers ----

    private fun colorKeys(m: VectorMetrics): Set<String> =
        m.colorCounts.keys.map { it.lowercase() }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        val union = (a + b).size
        if (union == 0) return 1f
        val inter = a.count { it in b }
        return inter.toFloat() / union
    }

    private fun boundsIoU(a: VectorBounds, b: VectorBounds): Float {
        val ix0 = max(a.minX, b.minX)
        val iy0 = max(a.minY, b.minY)
        val ix1 = min(a.maxX, b.maxX)
        val iy1 = min(a.maxY, b.maxY)
        val iw = (ix1 - ix0).coerceAtLeast(0f)
        val ih = (iy1 - iy0).coerceAtLeast(0f)
        val inter = iw * ih
        val areaA = (a.maxX - a.minX).coerceAtLeast(0f) * (a.maxY - a.minY).coerceAtLeast(0f)
        val areaB = (b.maxX - b.minX).coerceAtLeast(0f) * (b.maxY - b.minY).coerceAtLeast(0f)
        val union = areaA + areaB - inter
        return if (union <= 0f) 1f else (inter / union).coerceIn(0f, 1f)
    }

    private fun ratio(part: Int, whole: Int): Float =
        if (whole <= 0) 0f else (part.toFloat() / whole).coerceIn(0f, 1f)

    /** Similarity of two non-negative magnitudes: 1 when equal, → 0 as they diverge. */
    private fun ratioSim(a: Float, b: Float): Float {
        val hi = max(abs(a), abs(b))
        if (hi <= 0f) return 1f
        return (1f - abs(a - b) / hi).coerceIn(0f, 1f)
    }

    private fun close(a: Float, b: Float, eps: Float = 0.5f): Boolean = abs(a - b) <= eps

    private fun clamp01(v: Float): Float = when {
        v.isNaN() -> 0f
        v < 0f -> 0f
        v > 1f -> 1f
        else -> v
    }

    private fun trim(v: Float): String =
        if (v == v.toLong().toFloat()) v.toLong().toString() else "%.1f".format(v)
}
