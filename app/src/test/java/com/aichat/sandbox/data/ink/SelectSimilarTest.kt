package com.aichat.sandbox.data.ink

import com.aichat.sandbox.ui.components.notes.StrokeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase **I7** — the local select-similar ranker (pure JVM, no ink, no model).
 *
 * Verifies the magic-wand proposal: similar strokes are selected with the target,
 * dissimilar ones are left out, the threshold gates inclusion, and the order is
 * deterministic (target first, then descending similarity, stable on ties).
 */
class SelectSimilarTest {

    private val stride = StrokeCodec.FLOATS_PER_SAMPLE

    private fun encode(points: List<Pair<Float, Float>>): ByteArray {
        val out = FloatArray(points.size * stride)
        points.forEachIndexed { i, (x, y) ->
            out[i * stride] = x
            out[i * stride + 1] = y
            out[i * stride + 2] = 0.6f
            out[i * stride + 3] = 0.1f
        }
        return StrokeCodec.encode(out)
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 16): ByteArray {
        val pts = ArrayList<Pair<Float, Float>>(n)
        for (i in 0 until n) {
            val t = i.toFloat() / (n - 1)
            pts.add((x0 + (x1 - x0) * t) to (y0 + (y1 - y0) * t))
        }
        return encode(pts)
    }

    private fun square(x: Float, y: Float, size: Float): ByteArray =
        encode(listOf(x to y, x + size to y, x + size to y + size, x to y + size, x to y))

    private fun candidate(id: String, payload: ByteArray, tool: String = "pen", color: Int = 0xFF000000.toInt(), width: Float = 4f) =
        SelectSimilar.Candidate(id, StrokeSimilarity.featuresOf(payload, tool, color, width)!!)

    @Test
    fun selectsSimilarLinesAndExcludesLoops() {
        val candidates = listOf(
            candidate("target", line(0f, 0f, 100f, 0f)),
            candidate("line2", line(200f, 50f, 320f, 50f)),   // similar line elsewhere
            candidate("line3", line(0f, 0f, 40f, 0f)),         // similar line, smaller
            candidate("loop1", square(0f, 0f, 100f)),          // dissimilar
            candidate("loop2", square(500f, 500f, 60f)),       // dissimilar
        )
        val selected = SelectSimilar.selectSimilar("target", candidates)
        assertTrue("includes the tapped stroke", "target" in selected)
        assertTrue("includes a similar line", "line2" in selected)
        assertTrue("includes the smaller similar line", "line3" in selected)
        assertFalse("excludes a loop", "loop1" in selected)
        assertFalse("excludes a loop", "loop2" in selected)
        assertEquals("target leads the selection", "target", selected.first())
    }

    @Test
    fun thresholdGatesInclusion() {
        val candidates = listOf(
            candidate("target", line(0f, 0f, 100f, 0f), color = 0xFF000000.toInt()),
            candidate("redline", line(0f, 0f, 100f, 0f), color = 0xFFFF0000.toInt()),
        )
        // A high threshold rejects the colour-mismatched line; a low one keeps it.
        assertFalse("strict threshold excludes the recoloured line",
            "redline" in SelectSimilar.selectSimilar("target", candidates, threshold = 0.97f))
        assertTrue("loose threshold keeps it",
            "redline" in SelectSimilar.selectSimilar("target", candidates, threshold = 0.5f))
    }

    @Test
    fun rankingIsDescendingAndDeterministic() {
        val candidates = listOf(
            candidate("target", line(0f, 0f, 100f, 0f)),
            candidate("near", line(0f, 0f, 95f, 5f)),
            candidate("far", square(0f, 0f, 100f)),
        )
        val target = candidates.first { it.id == "target" }.features
        val ranked = SelectSimilar.rank("target", target, candidates)
        assertEquals("target excluded from ranking", listOf("near", "far"), ranked.map { it.id })
        assertTrue("descending by score", ranked[0].score >= ranked[1].score)
        // Re-run is identical.
        assertEquals(ranked, SelectSimilar.rank("target", target, candidates))
    }

    @Test
    fun unknownTargetReturnsItselfOnly() {
        val candidates = listOf(candidate("a", line(0f, 0f, 10f, 0f)))
        assertEquals(listOf("ghost"), SelectSimilar.selectSimilar("ghost", candidates))
    }
}
