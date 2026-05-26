package com.aichat.sandbox.data.vector

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [VectorSummaryJson]: the compact, model-facing description of a
 * parsed vector. Verifies the wire shape, per-path style/geometry, downsampling,
 * the soft-cap drop behaviour, and determinism.
 */
class VectorSummaryJsonTest {

    private fun summarize(xml: String): VectorSummaryJson.Summary {
        val doc = AndroidVectorDrawableParser.parse(xml)
        val metrics = VectorMetricsAnalyzer.analyze(doc, xml)
        return VectorSummaryJson.summarize(doc, metrics)
    }

    private fun root(summary: VectorSummaryJson.Summary): JsonObject =
        JsonParser.parseString(summary.json).asJsonObject

    @Test
    fun summaryIncludesViewportMetricsAndPaths() {
        val xml = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="108dp" android:height="108dp"
                android:viewportWidth="108" android:viewportHeight="108">
                <path android:name="frame"
                    android:pathData="M16,16 L92,16 L92,92 L16,92 Z"
                    android:strokeColor="#2D2D2D" android:strokeWidth="2" />
            </vector>
        """.trimIndent()

        val root = root(summarize(xml))
        assertEquals(1, root.get("schema").asInt)
        assertEquals("android_vector_drawable", root.get("format").asString)

        val vp = root.getAsJsonObject("viewport")
        assertEquals(108.0, vp.get("viewportWidth").asDouble, 0.001)
        assertEquals(108.0, vp.get("viewportHeight").asDouble, 0.001)

        val metrics = root.getAsJsonObject("metrics")
        assertEquals(1, metrics.get("pathCount").asInt)
        assertTrue(metrics.getAsJsonObject("colors").has("#2D2D2D"))

        val paths = root.getAsJsonArray("paths")
        assertEquals(1, paths.size())
        assertEquals("p_001", paths[0].asJsonObject.get("id").asString)
    }

    @Test
    fun summaryIncludesStyleAndSampledPoints() {
        val xml = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="24dp" android:height="24dp"
                android:viewportWidth="24" android:viewportHeight="24">
                <path android:name="leaf_stem"
                    android:pathData="M2,2 L4,4 L6,6 L8,8"
                    android:fillColor="#00000000"
                    android:strokeColor="#109F5C" android:strokeWidth="1.2"
                    android:strokeLineCap="round" android:strokeLineJoin="round" />
            </vector>
        """.trimIndent()

        val path = root(summarize(xml)).getAsJsonArray("paths")[0].asJsonObject
        assertEquals("leaf_stem", path.get("name").asString)

        val style = path.getAsJsonObject("style")
        assertEquals("#109F5C", style.get("stroke").asString)
        assertEquals("#00000000", style.get("fill").asString)
        assertEquals(1.2, style.get("strokeWidth").asDouble, 0.001)
        assertEquals("round", style.get("lineCap").asString)
        assertEquals("round", style.get("lineJoin").asString)

        val sampled = path.getAsJsonArray("sampledPoints")
        assertTrue("expected sampled points", sampled.size() > 0)
        val bounds = path.getAsJsonArray("bounds")
        assertEquals(4, bounds.size())
        assertNotNull(path.getAsJsonObject("noise"))
    }

    @Test
    fun summaryDownsamplesLongPaths() {
        // Far more vertices than MAX_SAMPLED_POINTS_PER_PATH.
        val data = buildString {
            append("M0,0")
            for (i in 1..200) append(" L$i,${i % 50}")
        }
        val xml = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="256dp" android:height="256dp"
                android:viewportWidth="256" android:viewportHeight="256">
                <path android:pathData="$data" android:strokeColor="#000000" android:strokeWidth="1" />
            </vector>
        """.trimIndent()

        val path = root(summarize(xml)).getAsJsonArray("paths")[0].asJsonObject
        val sampled = path.getAsJsonArray("sampledPoints")
        assertTrue(
            "sampled points must be capped at ${VectorSummaryJson.MAX_SAMPLED_POINTS_PER_PATH}",
            sampled.size() <= VectorSummaryJson.MAX_SAMPLED_POINTS_PER_PATH,
        )
        // commandCount still reflects the real path size, not the sample.
        assertTrue(path.get("commandCount").asInt > VectorSummaryJson.MAX_SAMPLED_POINTS_PER_PATH)
    }

    @Test
    fun summaryDropsPathsWhenOverSoftCap() {
        val sb = StringBuilder()
        sb.append(
            """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="512dp" android:height="512dp"
                android:viewportWidth="512" android:viewportHeight="512">
            """.trimIndent(),
        )
        // A few very large paths (high command counts) ...
        val bigData = buildString {
            append("M0,0")
            for (i in 1..400) append(" L${i % 100},${(i * 3) % 100}")
        }
        repeat(3) {
            sb.append("\n<path android:name=\"big\" android:pathData=\"$bigData\" ")
            sb.append("android:strokeColor=\"#112233\" android:strokeWidth=\"1\" />")
        }
        // ... plus many tiny paths to push the document past the soft cap.
        repeat(1200) { i ->
            sb.append("\n<path android:name=\"t$i\" android:pathData=\"M0,0 L1,1 L2,0\" ")
            sb.append("android:strokeColor=\"#445566\" android:strokeWidth=\"1\" />")
        }
        sb.append("\n</vector>")

        val summary = summarize(sb.toString())
        assertTrue("expected some paths to be dropped", summary.droppedPathIds.isNotEmpty())
        assertTrue(
            "encoded summary must fit the soft cap",
            summary.json.toByteArray(Charsets.UTF_8).size <= VectorSummaryJson.MAX_JSON_BYTES,
        )
        assertTrue(
            "a drop warning should be emitted",
            summary.warnings.any { it.code == VectorWarning.Codes.SUMMARY_PATHS_DROPPED },
        )
        // The big paths (largest command counts) are the parser's first three.
        assertTrue(
            "largest paths should be dropped first",
            summary.droppedPathIds.containsAll(listOf("p_001", "p_002", "p_003")),
        )
    }

    @Test
    fun summaryIsDeterministic() {
        val xml = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="48dp" android:height="48dp"
                android:viewportWidth="48" android:viewportHeight="48">
                <path android:pathData="M2,2 C4,4 6,6 8,8" android:strokeColor="#109F5C" android:strokeWidth="1" />
                <path android:pathData="M10,10 L20,20 L30,10 Z" android:fillColor="#D62828" />
            </vector>
        """.trimIndent()

        assertEquals(summarize(xml).json, summarize(xml).json)
    }
}
