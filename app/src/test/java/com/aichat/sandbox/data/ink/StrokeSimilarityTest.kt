package com.aichat.sandbox.data.ink

import com.aichat.sandbox.ui.components.notes.StrokeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase **I7** — the pure-JVM stroke similarity metric (no ink, no model).
 *
 * Pins the properties select-similar relies on: identical strokes score ~1;
 * the shape descriptors are translation- and scale-invariant (so the same mark
 * drawn elsewhere / bigger still matches); shape and style differences each pull
 * the score down; and the reduction is deterministic.
 */
class StrokeSimilarityTest {

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
        encode(
            listOf(
                x to y, x + size to y, x + size to y + size, x to y + size, x to y,
            ),
        )

    private fun features(payload: ByteArray, tool: String = "pen", color: Int = 0xFF000000.toInt(), width: Float = 4f) =
        StrokeSimilarity.featuresOf(payload, tool, color, width)!!

    @Test
    fun identicalStrokesScoreOne() {
        val a = features(line(0f, 0f, 100f, 0f))
        val b = features(line(0f, 0f, 100f, 0f))
        assertEquals(1f, StrokeSimilarity.similarity(a, b), 1e-3f)
    }

    @Test
    fun shapeFeaturesAreTranslationAndScaleInvariant() {
        val base = features(line(0f, 0f, 100f, 0f))
        val moved = features(line(500f, 500f, 600f, 500f)) // translated
        val scaled = features(line(0f, 0f, 25f, 0f))       // 4× smaller
        assertEquals("translation-invariant", 1f, StrokeSimilarity.similarity(base, moved), 1e-3f)
        assertEquals("scale-invariant", 1f, StrokeSimilarity.similarity(base, scaled), 1e-3f)
    }

    @Test
    fun straightnessSeparatesLineFromLoop() {
        val lineF = features(line(0f, 0f, 100f, 0f))
        val loopF = features(square(0f, 0f, 100f))
        assertTrue("a line reads as straight", lineF.straightness > 0.95f)
        assertTrue("a closed loop reads as not straight", loopF.straightness < 0.1f)
        val lineVsLine = StrokeSimilarity.similarity(lineF, features(line(10f, 10f, 90f, 10f)))
        val lineVsLoop = StrokeSimilarity.similarity(lineF, loopF)
        assertTrue("line↔loop is less similar than line↔line", lineVsLoop < lineVsLine)
        assertTrue("line↔loop is clearly dissimilar", lineVsLoop < 0.75f)
    }

    @Test
    fun differentToolLowersSimilarity() {
        val pen = features(line(0f, 0f, 100f, 0f), tool = "pen")
        val highlighter = features(line(0f, 0f, 100f, 0f), tool = "highlighter")
        val same = StrokeSimilarity.similarity(pen, features(line(0f, 0f, 100f, 0f), tool = "pen"))
        val diff = StrokeSimilarity.similarity(pen, highlighter)
        assertTrue("different tool scores below identical", diff < same)
    }

    @Test
    fun colorAndWidthRefineScore() {
        val black = features(line(0f, 0f, 100f, 0f), color = 0xFF000000.toInt(), width = 4f)
        val red = features(line(0f, 0f, 100f, 0f), color = 0xFFFF0000.toInt(), width = 4f)
        val thick = features(line(0f, 0f, 100f, 0f), color = 0xFF000000.toInt(), width = 40f)
        assertTrue("colour difference lowers score", StrokeSimilarity.similarity(black, red) < 1f)
        assertTrue("width difference lowers score", StrokeSimilarity.similarity(black, thick) < 1f)
    }

    @Test
    fun emptyPayloadHasNoFeatures() {
        assertNull(StrokeSimilarity.featuresOf(StrokeCodec.encode(FloatArray(0)), "pen", 0, 4f))
    }

    @Test
    fun similarityIsSymmetricAndDeterministic() {
        val a = features(line(0f, 0f, 100f, 30f))
        val b = features(square(0f, 0f, 80f))
        val ab = StrokeSimilarity.similarity(a, b)
        val ba = StrokeSimilarity.similarity(b, a)
        assertEquals(ab, ba, 1e-6f)
        assertEquals(ab, StrokeSimilarity.similarity(a, b), 0f) // identical re-run
    }
}
