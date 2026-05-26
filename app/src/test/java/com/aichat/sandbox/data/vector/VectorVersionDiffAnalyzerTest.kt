package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 7 — structural (non-raster) version diffing. */
class VectorVersionDiffAnalyzerTest {

    private fun vector(body: String): String = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="108dp" android:height="108dp"
            android:viewportWidth="108" android:viewportHeight="108">
        $body
        </vector>
    """.trimIndent()

    private fun input(xml: String): VectorVersionQualityInput {
        val document = AndroidVectorDrawableParser.parse(xml)
        return VectorVersionQualityInput(xml, document, VectorMetricsAnalyzer.analyze(document, xml))
    }

    @Test
    fun diffReportsByteCommandAndPathDeltas() {
        val before = input(
            vector(
                """
                <path android:pathData="M0,0 L1,1 L2,2 L3,3 L10,10"
                    android:strokeColor="#000000" android:strokeWidth="1"/>
                <path android:pathData="M0,5 L20,5"
                    android:strokeColor="#000000" android:strokeWidth="1"/>
                """.trimIndent(),
            ),
        )
        val after = input(
            vector(
                """
                <path android:pathData="M0,0 L10,10"
                    android:strokeColor="#000000" android:strokeWidth="1"/>
                """.trimIndent(),
            ),
        )
        val diff = VectorVersionDiffAnalyzer.diff(before, after)
        assertEquals(after.metrics.pathCount - before.metrics.pathCount, diff.pathCountDelta)
        assertEquals(after.metrics.commandCount - before.metrics.commandCount, diff.commandCountDelta)
        assertEquals(after.metrics.xmlBytes - before.metrics.xmlBytes, diff.bytesDelta)
        assertTrue("after is smaller", diff.bytesDelta < 0)
        assertTrue("fewer paths", diff.pathCountDelta < 0)
    }

    @Test
    fun diffReportsColorsAddedRemovedAndRetained() {
        val before = input(
            vector(
                """
                <path android:pathData="M0,0 L1,1" android:fillColor="#FF0000"/>
                <path android:pathData="M2,2 L3,3" android:fillColor="#00FF00"/>
                """.trimIndent(),
            ),
        )
        val after = input(
            vector(
                """
                <path android:pathData="M0,0 L1,1" android:fillColor="#00FF00"/>
                <path android:pathData="M2,2 L3,3" android:fillColor="#0000FF"/>
                """.trimIndent(),
            ),
        )
        val diff = VectorVersionDiffAnalyzer.diff(before, after)
        assertTrue("#0000FF added", diff.colorAdded.any { it.equals("#0000FF", true) })
        assertTrue("#FF0000 removed", diff.colorRemoved.any { it.equals("#FF0000", true) })
        assertTrue("#00FF00 retained", diff.colorRetained.any { it.equals("#00FF00", true) })
    }

    @Test
    fun diffHandlesMissingBounds() {
        val before = input(
            vector(
                """<path android:pathData="M0,0 L10,10" android:strokeColor="#000000" android:strokeWidth="1"/>""",
            ),
        )
        // Empty vector → no paths → null bounds.
        val after = input(vector(""))
        val diff = VectorVersionDiffAnalyzer.diff(before, after)
        assertTrue("bounds disappeared", diff.boundsChanged)
        assertTrue(diff.summary.isNotBlank())
    }

    @Test
    fun diffNoOpHasNeutralSummary() {
        val xml = vector(
            """<path android:pathData="M0,0 L10,10" android:strokeColor="#000000" android:strokeWidth="1"/>""",
        )
        val v = input(xml)
        val diff = VectorVersionDiffAnalyzer.diff(v, v)
        assertEquals(0, diff.bytesDelta)
        assertEquals(0, diff.pathCountDelta)
        assertFalse(diff.boundsChanged)
        assertTrue("neutral summary", diff.summary.contains("No structural changes"))
    }

    @Test
    fun diffRedrawSummarizesLargeStructuralChange() {
        val before = input(
            vector(
                """
                <path android:pathData="M0,0 L1,1" android:fillColor="#FF0000"/>
                <path android:pathData="M2,2 L3,3" android:fillColor="#00FF00"/>
                <path android:pathData="M4,4 L5,5" android:fillColor="#0000FF"/>
                <path android:pathData="M6,6 L7,7" android:fillColor="#FFFF00"/>
                """.trimIndent(),
            ),
        )
        val after = input(
            vector(
                """<path android:pathData="M10,10 L90,90" android:strokeColor="#123456" android:strokeWidth="2"/>""",
            ),
        )
        val diff = VectorVersionDiffAnalyzer.diff(before, after)
        assertTrue(
            "large change summary: ${diff.summary}",
            diff.summary.contains("Large structural change"),
        )
    }
}
