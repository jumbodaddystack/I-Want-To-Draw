package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 8 — pure preview-model builder coverage (no Android graphics). */
class VectorPreviewBuilderTest {

    private fun vector(body: String): String = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="48dp" android:height="96dp"
            android:viewportWidth="24" android:viewportHeight="48">
        $body
        </vector>
    """.trimIndent()

    private fun build(xml: String): VectorPreviewModel =
        VectorPreviewBuilder.build(AndroidVectorDrawableParser.parse(xml))

    @Test
    fun buildPreviewModelIncludesViewport() {
        val model = build(vector("""<path android:name="p" android:pathData="M0,0 L10,10"/>"""))
        assertEquals(24f, model.viewport.viewportWidth)
        assertEquals(48f, model.viewport.viewportHeight)
    }

    @Test
    fun buildPreviewModelIncludesParsedPaths() {
        val model = build(
            vector(
                """
                <path android:name="a" android:pathData="M0,0 L10,10"/>
                <group android:name="layer">
                    <path android:name="b" android:pathData="M2,2 L4,4 L6,2 Z"/>
                </group>
                """.trimIndent(),
            ),
        )
        assertEquals(2, model.paths.size)
        // Depth-first order: root path then the grouped path.
        assertEquals(listOf("a", "b"), model.paths.map { it.name })
        assertTrue(model.paths.all { it.commands.isNotEmpty() })
        assertNotNull(model.paths[0].bounds)
    }

    @Test
    fun buildPreviewModelPreservesStyle() {
        val model = build(
            vector(
                """
                <path android:name="p" android:pathData="M0,0 L10,10"
                    android:fillColor="#112233" android:fillAlpha="0.5"
                    android:strokeColor="#445566" android:strokeAlpha="0.8"
                    android:strokeWidth="2.5" android:strokeLineCap="round"
                    android:strokeLineJoin="bevel" android:fillType="evenOdd"/>
                """.trimIndent(),
            ),
        )
        val style = model.paths.single().style
        assertEquals("#112233", style.fillColor)
        assertEquals(0.5f, style.fillAlpha)
        assertEquals("#445566", style.strokeColor)
        assertEquals(0.8f, style.strokeAlpha)
        assertEquals(2.5f, style.strokeWidth)
        assertEquals("round", style.strokeLineCap)
        assertEquals("bevel", style.strokeLineJoin)
        assertEquals("evenOdd", style.fillType)
    }

    @Test
    fun buildPreviewModelSkipsUnparsedPathsWithWarning() {
        val model = build(
            vector(
                """
                <path android:name="good" android:pathData="M0,0 L10,10"/>
                <path android:name="bad" android:pathData="???" android:fillColor="#FF0000"/>
                """.trimIndent(),
            ),
        )
        assertEquals(1, model.paths.size)
        assertEquals("good", model.paths.single().name)
        assertTrue(
            model.warnings.any { it.code == VectorWarning.Codes.PREVIEW_SKIPPED_UNPARSED_PATH },
        )
    }

    @Test
    fun buildPreviewModelHandlesEmptyDocument() {
        // Structurally invalid XML yields a safe empty document.
        val empty = VectorPreviewBuilder.build(AndroidVectorDrawableParser.parse("not xml at all"))
        assertTrue(empty.paths.isEmpty())
        assertTrue(empty.warnings.any { it.code == VectorWarning.Codes.PREVIEW_EMPTY })

        // A well-formed vector with no paths is also empty + flagged.
        val noPaths = build(vector(""))
        assertTrue(noPaths.paths.isEmpty())
        assertTrue(noPaths.warnings.any { it.code == VectorWarning.Codes.PREVIEW_EMPTY })
    }

    @Test
    fun buildPreviewModelSurfacesUnsupportedFeatures() {
        val model = build(
            vector(
                """
                <path android:name="p" android:pathData="M0,0 L10,10"/>
                <clip-path android:pathData="M0,0 L24,0 L24,48 L0,48 Z"/>
                """.trimIndent(),
            ),
        )
        assertEquals(1, model.paths.size)
        assertTrue(
            model.warnings.any { it.code == VectorWarning.Codes.PREVIEW_UNSUPPORTED_FEATURE },
        )
    }

    @Test
    fun buildPreviewModelIsDeterministic() {
        val xml = vector(
            """
            <path android:name="a" android:pathData="M0,0 C1,1 2,2 3,3"
                android:fillColor="#FF0000"/>
            <path android:name="b" android:pathData="M5,5 a4,4 0 1,0 8,0"
                android:strokeColor="#00FF00" android:strokeWidth="1"/>
            """.trimIndent(),
        )
        val first = build(xml)
        val second = build(xml)
        assertEquals(first, second)
        assertFalse(first.paths.isEmpty())
    }
}
