package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 2 — end-to-end faithful optimization over whole VectorDrawable docs. */
class VectorDrawableOptimizerTest {

    private fun vector(body: String): String = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="108dp" android:height="108dp"
            android:viewportWidth="108" android:viewportHeight="108">
        $body
        </vector>
    """.trimIndent()

    private fun pathNamed(doc: VectorDocument, name: String): VectorPath =
        doc.allPaths().first { it.name == name }

    @Test
    fun optimizeSimplifiesNoisyStrokePath() {
        val xml = vector(
            """
            <path android:name="stroke"
                android:pathData="M0,0 L0.1,0.1 L0.2,0.2 L10,10"
                android:fillColor="#00000000"
                android:strokeColor="#000000"
                android:strokeWidth="1"/>
            """.trimIndent(),
        )
        val result = VectorDrawableOptimizer.optimize(xml)
        val path = pathNamed(result.document, "stroke")
        assertEquals("M0,0L10,10", path.pathData)
        assertEquals(2, path.commands?.size)
        assertEquals(1, result.report.simplifiedPathCount)
        assertEquals(0, result.report.removedPathCount)
        assertTrue(result.report.after.commandCount < result.report.before.commandCount)
    }

    @Test
    fun optimizePreservesViewportAndStyle() {
        val xml = vector(
            """
            <group android:name="layer" android:translateX="4" android:translateY="6">
                <path android:name="stroke"
                    android:pathData="M0,0 L0.1,0.1 L0.2,0.2 L10,10"
                    android:fillColor="#00000000"
                    android:strokeColor="#112233"
                    android:strokeWidth="1.5"
                    android:strokeLineCap="round"/>
            </group>
            """.trimIndent(),
        )
        val result = VectorDrawableOptimizer.optimize(xml)

        val vp = result.document.viewport
        assertEquals(108f, vp.viewportWidth)
        assertEquals(108f, vp.viewportHeight)
        assertEquals(108f, vp.widthDp)
        assertEquals(108f, vp.heightDp)

        val group = result.document.allGroups().first { it.name == "layer" }
        assertEquals(4f, group.translateX)
        assertEquals(6f, group.translateY)

        val style = pathNamed(result.document, "stroke").style
        assertEquals("#00000000", style.fillColor)
        assertEquals("#112233", style.strokeColor)
        assertEquals(1.5f, style.strokeWidth)
        assertEquals("round", style.strokeLineCap)
    }

    @Test
    fun optimizeDoesNotSimplifyFilledPathByDefault() {
        val xml = vector(
            """
            <path android:name="fill"
                android:pathData="M0,0 L5,0 L10,0 L10,5 L10,10 L5,10 L0,10 Z"
                android:fillColor="#FF0000"/>
            """.trimIndent(),
        )
        val result = VectorDrawableOptimizer.optimize(xml)
        assertEquals(0, result.report.simplifiedPathCount)
        assertEquals(0, result.report.removedPathCount)
        // 1 move + 6 line + close = 8 commands, untouched.
        assertEquals(8, pathNamed(result.document, "fill").commands?.size)
    }

    @Test
    fun optimizeSimplifiesFilledPathWhenEnabled() {
        val xml = vector(
            """
            <path android:name="fill"
                android:pathData="M0,0 L5,0 L10,0 L10,5 L10,10 L5,10 L0,10 Z"
                android:fillColor="#FF0000"/>
            """.trimIndent(),
        )
        val result = VectorDrawableOptimizer.optimize(
            xml,
            VectorOptimizeOptions(simplifyFills = true),
        )
        assertEquals(1, result.report.simplifiedPathCount)
        val path = pathNamed(result.document, "fill")
        assertEquals("M0,0L10,0L10,10L0,10Z", path.pathData)
        // 1 move + 3 line + close = 5 commands.
        assertEquals(5, path.commands?.size)
    }

    @Test
    fun optimizeRemovesTinyStrokePathWhenEnabled() {
        val xml = vector(
            """
            <path android:name="keep"
                android:pathData="M0,0 L0.1,0.1 L10,10"
                android:strokeColor="#000000" android:strokeWidth="1"/>
            <path android:name="tiny"
                android:pathData="M5,5 L5.004,5"
                android:strokeColor="#000000" android:strokeWidth="1"/>
            """.trimIndent(),
        )
        val result = VectorDrawableOptimizer.optimize(xml)
        assertEquals(1, result.report.removedPathCount)
        assertEquals(1, result.document.allPaths().size)
        assertEquals("keep", result.document.allPaths().single().name)
        assertTrue(
            result.report.warnings.any {
                it.code == VectorWarning.Codes.OPTIMIZER_REMOVED_TINY_PATH
            },
        )
    }

    @Test
    fun optimizeKeepsTinyStrokePathWhenDisabled() {
        val xml = vector(
            """
            <path android:name="tiny"
                android:pathData="M5,5 L5.004,5"
                android:strokeColor="#000000" android:strokeWidth="1"/>
            """.trimIndent(),
        )
        val result = VectorDrawableOptimizer.optimize(
            xml,
            VectorOptimizeOptions(removeTinyPaths = false),
        )
        assertEquals(0, result.report.removedPathCount)
        assertEquals(1, result.document.allPaths().size)
    }

    @Test
    fun optimizeSkipsMalformedPathWithoutCrashing() {
        val xml = vector(
            """
            <path android:name="bad"
                android:pathData="???"
                android:strokeColor="#000000" android:strokeWidth="1"/>
            """.trimIndent(),
        )
        val result = VectorDrawableOptimizer.optimize(xml)
        assertEquals(1, result.report.unsupportedPathCount)
        assertEquals(0, result.report.simplifiedPathCount)
        // The unparsed path is preserved verbatim.
        assertEquals("???", pathNamed(result.document, "bad").pathData)
        assertTrue(
            result.report.warnings.any {
                it.code == VectorWarning.Codes.OPTIMIZER_SKIPPED_UNPARSED_PATH
            },
        )
    }

    @Test
    fun optimizedXmlParsesAgain() {
        val xml = vector(
            """
            <path android:name="stroke"
                android:pathData="M0,0 L0.1,0.1 L0.2,0.2 L10,10"
                android:fillColor="#00000000"
                android:strokeColor="#000000" android:strokeWidth="1"/>
            <path android:name="fill"
                android:pathData="M0,0 L10,0 L10,10 L0,10 Z"
                android:fillColor="#FF0000"/>
            <path android:name="curve"
                android:pathData="M0,0 C0,10 10,10 10,0"
                android:strokeColor="#00FF00" android:strokeWidth="1"/>
            """.trimIndent(),
        )
        val result = VectorDrawableOptimizer.optimize(xml)
        val reparsed = AndroidVectorDrawableParser.parse(result.xml)
        assertFalse(
            reparsed.warnings.any { it.code == VectorWarning.Codes.MALFORMED_XML },
        )
        assertEquals(3, reparsed.allPaths().size)
        // Every path still has parseable geometry after the round trip.
        assertTrue(reparsed.allPaths().all { it.commands?.isNotEmpty() == true })
    }

    @Test
    fun reportIncludesBeforeAndAfterMetrics() {
        val xml = vector(
            """
            <path android:name="stroke"
                android:pathData="M0,0 L0.1,0.1 L0.2,0.2 L0.3,0.3 L10,10"
                android:fillColor="#00000000"
                android:strokeColor="#000000" android:strokeWidth="1"/>
            """.trimIndent(),
        )
        val result = VectorDrawableOptimizer.optimize(xml)
        val report = result.report
        assertNotNull(report.before)
        assertNotNull(report.after)
        assertEquals(report.before.pathCount, report.after.pathCount)
        assertTrue(report.before.commandCount > report.after.commandCount)
        assertTrue(report.before.xmlBytes > 0)
        assertTrue(report.after.xmlBytes > 0)
    }

    @Test
    fun optimizeDocumentOverloadUsesProvidedOriginalXml() {
        val xml = vector(
            """
            <path android:name="stroke"
                android:pathData="M0,0 L0.1,0.1 L0.2,0.2 L10,10"
                android:strokeColor="#000000" android:strokeWidth="1"/>
            """.trimIndent(),
        )
        val doc = AndroidVectorDrawableParser.parse(xml)
        val result = VectorDrawableOptimizer.optimize(doc, originalXml = xml)
        assertEquals(xml.toByteArray(Charsets.UTF_8).size, result.report.before.xmlBytes)
        assertEquals(1, result.report.simplifiedPathCount)
    }
}
