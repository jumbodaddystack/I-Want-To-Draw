package com.aichat.sandbox.data.ink

/**
 * Phase **I7 — select-similar + snapping (N2, idea #8)**: the magic-wand
 * "select similar" ranker. Given the stroke the user tapped and the other
 * strokes on the note, return the ids that are geometrically + stylistically
 * similar enough to batch-recolor / restyle / delete together.
 *
 * Pure, local, deterministic (the N2 "geometry runs locally" half): it scores
 * each candidate against the target with [StrokeSimilarity] and keeps those at or
 * above a threshold. The result is the *proposal*; the optional AI step ("which of
 * these belong together") is layered on top by the caller via the existing
 * edit-ops pipeline and never gates this local path. `StrokeCodec` stays
 * canonical — everything here reads derived [StrokeSimilarity.Features].
 *
 * Determinism: ties (equal score) break by the candidate's input order, so the
 * selection is stable run-to-run regardless of map/hash iteration — the same
 * property the I6 [MeshHitTest.strokesInRegion] id mapping relies on.
 */
object SelectSimilar {

    /** Default minimum similarity for inclusion. Tuned for "same kind of mark". */
    const val DEFAULT_THRESHOLD = 0.82f

    /** A stroke considered for selection, with its precomputed features. */
    data class Candidate(val id: String, val features: StrokeSimilarity.Features)

    /** A scored candidate; [score] is the `[0,1]` similarity to the target. */
    data class Ranked(val id: String, val score: Float)

    /**
     * Score every candidate (except [targetId] itself) against [target], sorted
     * by descending score with stable input-order tie-breaking. The target is not
     * in the output — callers add it back when building a selection.
     */
    fun rank(
        targetId: String,
        target: StrokeSimilarity.Features,
        candidates: List<Candidate>,
        weights: StrokeSimilarity.Weights = StrokeSimilarity.Weights(),
    ): List<Ranked> {
        val scored = ArrayList<IndexedScore>(candidates.size)
        candidates.forEachIndexed { index, candidate ->
            if (candidate.id == targetId) return@forEachIndexed
            val score = StrokeSimilarity.similarity(target, candidate.features, weights)
            scored.add(IndexedScore(index, candidate.id, score))
        }
        // Descending score; stable on original index for equal scores.
        scored.sortWith(compareByDescending<IndexedScore> { it.score }.thenBy { it.index })
        return scored.map { Ranked(it.id, it.score) }
    }

    /**
     * Ids of strokes similar to [targetId] at or above [threshold], **including
     * the target itself** (so the result is a ready-to-use selection set). Order:
     * the target first, then matches by descending similarity. When the target
     * has no features (empty stroke) the result is just the target id.
     */
    fun selectSimilar(
        targetId: String,
        candidates: List<Candidate>,
        threshold: Float = DEFAULT_THRESHOLD,
        weights: StrokeSimilarity.Weights = StrokeSimilarity.Weights(),
    ): List<String> {
        val target = candidates.firstOrNull { it.id == targetId }?.features
            ?: return listOf(targetId)
        val out = ArrayList<String>()
        out.add(targetId)
        for (ranked in rank(targetId, target, candidates, weights)) {
            if (ranked.score >= threshold) out.add(ranked.id)
        }
        return out
    }

    private data class IndexedScore(val index: Int, val id: String, val score: Float)
}
