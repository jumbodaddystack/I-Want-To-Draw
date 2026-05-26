package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 7 — batch restyle by color / path group. */
class VectorBatchRestyleTest {

    private fun vector(body: String): String = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="108dp" android:height="108dp"
            android:viewportWidth="108" android:viewportHeight="108">
        $body
        </vector>
    """.trimIndent()

    private val xml = vector(
        """
        <path android:name="greenStroke" android:pathData="M0,0 L10,10"
            android:strokeColor="#109F5C" android:strokeWidth="2"/>
        <path android:name="redStroke" android:pathData="M0,5 L20,5"
            android:strokeColor="#D62828" android:strokeWidth="2"/>
        <path android:name="blueFill" android:pathData="M0,0 L10,0 L10,10 L0,10 Z"
            android:fillColor="#0000FF"/>
        """.trimIndent(),
    )

    private fun parse(): VectorDocument = AndroidVectorDrawableParser.parse(xml)

    private fun pathByName(doc: VectorDocument, name: String): VectorPath =
        doc.allPaths().first { it.name == name }

    private fun idOf(doc: VectorDocument, name: String): String =
        VectorPathCatalog.catalog(doc).first { it.name == name }.id

    @Test
    fun restyleAllStroked() {
        val doc = parse()
        val result = VectorBatchRestyleApplier.apply(
            doc, xml,
            VectorBatchRestyle(VectorBatchRestyle.Target.AllStroked, strokeWidth = 1.2f),
        )
        assertEquals(1.2f, pathByName(result.document, "greenStroke").style.strokeWidth)
        assertEquals(1.2f, pathByName(result.document, "redStroke").style.strokeWidth)
        // The fill-only path has no stroke to touch.
        assertEquals(2, result.editedPathCount)
    }

    @Test
    fun restyleAllFilled() {
        val doc = parse()
        val result = VectorBatchRestyleApplier.apply(
            doc, xml,
            VectorBatchRestyle(VectorBatchRestyle.Target.AllFilled, fillColor = "#00FF00"),
        )
        assertEquals("#00FF00", pathByName(result.document, "blueFill").style.fillColor)
        assertEquals(1, result.editedPathCount)
    }

    @Test
    fun restyleByColor() {
        val doc = parse()
        val result = VectorBatchRestyleApplier.apply(
            doc, xml,
            VectorBatchRestyle(
                VectorBatchRestyle.Target.ByColor(listOf("#109F5C")),
                strokeColor = "#000000",
            ),
        )
        assertEquals("#000000", pathByName(result.document, "greenStroke").style.strokeColor)
        // The other stroke keeps its color.
        assertEquals("#D62828", pathByName(result.document, "redStroke").style.strokeColor)
    }

    @Test
    fun restyleByPathIds() {
        val doc = parse()
        val redId = idOf(doc, "redStroke")
        val result = VectorBatchRestyleApplier.apply(
            doc, xml,
            VectorBatchRestyle(VectorBatchRestyle.Target.ByPathIds(listOf(redId)), strokeWidth = 3f),
        )
        assertEquals(3f, pathByName(result.document, "redStroke").style.strokeWidth)
        assertEquals(2f, pathByName(result.document, "greenStroke").style.strokeWidth)
    }

    @Test
    fun batchRestyleNoMatchesWarns() {
        val doc = parse()
        val result = VectorBatchRestyleApplier.apply(
            doc, xml,
            VectorBatchRestyle(VectorBatchRestyle.Target.ByColor(listOf("#ABCDEF")), strokeWidth = 1f),
        )
        assertTrue(result.warnings.any { it.code == VectorWarning.Codes.MANUAL_EDIT_NO_MATCHING_PATHS })
        assertEquals(0, result.editedPathCount)
    }

    @Test
    fun batchRestyleResultXmlParsesAgain() {
        val doc = parse()
        val result = VectorBatchRestyleApplier.apply(
            doc, xml,
            VectorBatchRestyle(VectorBatchRestyle.Target.AllStroked, strokeColor = "#222222", strokeWidth = 1.5f),
        )
        val reparsed = AndroidVectorDrawableParser.parse(result.xml)
        assertFalse(reparsed.warnings.any { it.code == VectorWarning.Codes.MALFORMED_XML })
        assertEquals(3, reparsed.allPaths().size)
    }
}
