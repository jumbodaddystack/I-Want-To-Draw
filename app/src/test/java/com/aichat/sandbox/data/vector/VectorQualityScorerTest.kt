package com.aichat.sandbox.data.vector

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 7 — deterministic, explainable quality scoring. */
class VectorQualityScorerTest {

    private fun vector(body: String): String = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="108dp" android:height="108dp"
            android:viewportWidth="108" android:viewportHeight="108">
        $body
        </vector>
    """.trimIndent()

    private fun input(xml: String): VectorVersionQualityInput {
        val document = AndroidVectorDrawableParser.parse(xml)
        val metrics = VectorMetricsAnalyzer.analyze(document, xml)
        return VectorVersionQualityInput(xml, document, metrics)
    }

    /** A long, nearly-colinear stroke made of many tiny segments. */
    private fun noisyStroke(steps: Int): String {
        val sb = StringBuilder("M0,0")
        for (k in 1..steps) sb.append("L").append(k * 0.1f).append(",0")
        return """
            <path android:name="noisy" android:pathData="$sb"
                android:strokeColor="#2D2D2D" android:strokeWidth="1"/>
        """.trimIndent()
    }

    private val cleanXml = vector(
        """
        <path android:name="frame" android:pathData="M16,16 L92,16 L92,92 L16,92 Z"
            android:strokeColor="#2D2D2D" android:strokeWidth="2"/>
        """.trimIndent(),
    )

    @Test
    fun scoreCleanVectorHigherThanNoisyVector() {
        val clean = VectorQualityScorer.score(null, input(cleanXml))
        val noisy = VectorQualityScorer.score(null, input(vector(noisyStroke(60))))
        assertTrue(
            "clean ${clean.cleanliness} should beat noisy ${noisy.cleanliness}",
            clean.cleanliness > noisy.cleanliness,
        )
    }

    @Test
    fun scoreSimplifiedVersionImprovesFileEfficiency() {
        val originalXml = vector(noisyStroke(80))
        val original = input(originalXml)
        val optimized = VectorDrawableOptimizer.optimize(originalXml)
        val candidate = VectorVersionQualityInput(
            optimized.xml,
            optimized.document,
            optimized.report.after,
        )
        val before = VectorQualityScorer.score(null, original).fileEfficiency
        val after = VectorQualityScorer.score(original, candidate).fileEfficiency
        assertTrue("efficiency should improve: $before -> $after", after > before)
    }

    @Test
    fun scoreDestructiveRedrawLowersFaithfulness() {
        val originalXml = vector(
            """
            <path android:name="a" android:pathData="M0,0 L0.1,0 L0.2,0 L0.3,0 L20,0"
                android:strokeColor="#109F5C" android:strokeWidth="1"/>
            <path android:name="b" android:pathData="M0,10 L0.1,10 L0.2,10 L30,10"
                android:strokeColor="#D62828" android:strokeWidth="1"/>
            <path android:name="c" android:pathData="M0,20 L40,20"
                android:strokeColor="#2D2D2D" android:strokeWidth="1"/>
            """.trimIndent(),
        )
        val original = input(originalXml)

        // Faithful optimize: same colors, same path count, fewer commands.
        val optimized = VectorDrawableOptimizer.optimize(originalXml)
        val faithful = VectorVersionQualityInput(
            optimized.xml, optimized.document, optimized.report.after,
        )

        // Destructive redraw: different palette and path structure.
        val redraw = input(
            vector(
                """
                <path android:name="x" android:pathData="M10,10 L90,90"
                    android:strokeColor="#0000FF" android:strokeWidth="3"/>
                """.trimIndent(),
            ),
        )

        val faithfulScore = VectorQualityScorer.score(original, faithful).faithfulness
        val redrawScore = VectorQualityScorer.score(original, redraw).faithfulness
        assertNotNull(faithfulScore)
        assertNotNull(redrawScore)
        assertTrue(
            "redraw faithfulness $redrawScore should be below optimize $faithfulScore",
            redrawScore!! < faithfulScore!!,
        )
    }

    @Test
    fun scoreHandlesMissingOriginal() {
        val scores = VectorQualityScorer.score(null, input(cleanXml))
        assertNull(scores.faithfulness)
        assertTrue(scores.overall in 0f..1f)
        assertTrue(scores.cleanliness in 0f..1f)
    }

    @Test
    fun scoreNeverThrowsOnMalformedMetrics() {
        val brokenMetrics = VectorMetrics(
            xmlBytes = -1,
            pathCount = 0,
            groupCount = 0,
            commandCount = 0,
            parsedCommandCount = 0,
            unsupportedPathCount = 5,
            estimatedPointCount = 0,
            colorCounts = emptyMap(),
            strokePathCount = -3,
            fillPathCount = 0,
            zeroLengthPathCount = 10,
            tinySegmentEstimate = 99,
            duplicateCoordinateEstimate = 99,
            bounds = VectorBounds(Float.NaN, Float.NaN, Float.NaN, Float.NaN),
            warnings = emptyList(),
        )
        val document = AndroidVectorDrawableParser.parse(cleanXml)
        val scores = VectorQualityScorer.score(
            original = null,
            candidate = VectorVersionQualityInput("", document, brokenMetrics),
        )
        // Every score must be a finite value in range.
        listOf(
            scores.cleanliness, scores.iconReadiness,
            scores.fileEfficiency, scores.maintainability, scores.overall,
        ).forEach { assertTrue("score $it out of range", it in 0f..1f) }
    }

    @Test
    fun scoreIncludesUsefulNotes() {
        val noisy = VectorQualityScorer.score(null, input(vector(noisyStroke(60))))
        assertTrue("notes should explain the score", noisy.notes.isNotEmpty())
    }
}
