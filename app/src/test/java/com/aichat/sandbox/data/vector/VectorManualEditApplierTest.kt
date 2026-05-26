package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 7 — deterministic manual per-path editing. */
class VectorManualEditApplierTest {

    private fun vector(body: String): String = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="108dp" android:height="108dp"
            android:viewportWidth="108" android:viewportHeight="108">
        $body
        </vector>
    """.trimIndent()

    private fun parse(xml: String): VectorDocument = AndroidVectorDrawableParser.parse(xml)

    private fun idOf(doc: VectorDocument, name: String): String =
        VectorPathCatalog.catalog(doc).first { it.name == name }.id

    private fun pathByName(doc: VectorDocument, name: String): VectorPath =
        doc.allPaths().first { it.name == name }

    private val twoPathsXml = vector(
        """
        <path android:name="keep" android:pathData="M0,0 L10,10"
            android:strokeColor="#000000" android:strokeWidth="1"/>
        <path android:name="drop" android:pathData="M0,5 L20,5"
            android:strokeColor="#000000" android:strokeWidth="1"/>
        """.trimIndent(),
    )

    @Test
    fun deletePathsRemovesOnlySelectedPaths() {
        val doc = parse(twoPathsXml)
        val dropId = idOf(doc, "drop")
        val result = VectorManualEditApplier.apply(
            doc, twoPathsXml, listOf(VectorManualEdit.DeletePaths(listOf(dropId))),
        )
        assertEquals(1, result.deletedPathCount)
        assertEquals(1, result.document.allPaths().size)
        assertEquals("keep", result.document.allPaths().single().name)
    }

    @Test
    fun recolorPathsUpdatesSelectedStyles() {
        val doc = parse(twoPathsXml)
        val keepId = idOf(doc, "keep")
        val result = VectorManualEditApplier.apply(
            doc, twoPathsXml,
            listOf(VectorManualEdit.RecolorPaths(listOf(keepId), strokeColor = "#FF8800", fillColor = "#112233")),
        )
        val keep = pathByName(result.document, "keep")
        assertEquals("#FF8800", keep.style.strokeColor)
        assertEquals("#112233", keep.style.fillColor)
        // The other path is untouched.
        assertEquals("#000000", pathByName(result.document, "drop").style.strokeColor)
        assertEquals(1, result.editedPathCount)
    }

    @Test
    fun restylePathsClampsStrokeWidth() {
        val doc = parse(twoPathsXml)
        val keepId = idOf(doc, "keep")
        val tooWide = VectorManualEditApplier.apply(
            doc, twoPathsXml,
            listOf(VectorManualEdit.RestylePaths(listOf(keepId), strokeWidth = 1000f, lineCap = "round")),
        )
        assertEquals(VectorManualEditApplier.MAX_STROKE_WIDTH, pathByName(tooWide.document, "keep").style.strokeWidth)
        assertEquals("round", pathByName(tooWide.document, "keep").style.strokeLineCap)

        val tooThin = VectorManualEditApplier.apply(
            doc, twoPathsXml,
            listOf(VectorManualEdit.RestylePaths(listOf(keepId), strokeWidth = 0.001f)),
        )
        assertEquals(VectorManualEditApplier.MIN_STROKE_WIDTH, pathByName(tooThin.document, "keep").style.strokeWidth)
    }

    @Test
    fun simplifyPathsUsesExistingSimplifier() {
        val noisy = StringBuilder("M0,0")
        for (k in 1..40) noisy.append("L").append(k * 0.25f).append(",0")
        val xml = vector(
            """<path android:name="noisy" android:pathData="$noisy"
                android:strokeColor="#000000" android:strokeWidth="1"/>""",
        )
        val doc = parse(xml)
        val before = pathByName(doc, "noisy").commands!!.size
        val id = idOf(doc, "noisy")
        val result = VectorManualEditApplier.apply(
            doc, xml, listOf(VectorManualEdit.SimplifyPaths(listOf(id), tolerance = 0.25f)),
        )
        val after = pathByName(result.document, "noisy").commands!!.size
        assertTrue("simplify should reduce commands: $before -> $after", after < before)
    }

    @Test
    fun unknownPathProducesWarning() {
        val doc = parse(twoPathsXml)
        val result = VectorManualEditApplier.apply(
            doc, twoPathsXml, listOf(VectorManualEdit.DeletePaths(listOf("p_999"))),
        )
        assertTrue(result.warnings.any { it.code == VectorWarning.Codes.MANUAL_EDIT_UNKNOWN_PATH })
        assertEquals(0, result.deletedPathCount)
    }

    @Test
    fun invalidColorProducesWarning() {
        val doc = parse(twoPathsXml)
        val keepId = idOf(doc, "keep")
        val result = VectorManualEditApplier.apply(
            doc, twoPathsXml,
            listOf(VectorManualEdit.RecolorPaths(listOf(keepId), strokeColor = "not-a-color")),
        )
        assertTrue(result.warnings.any { it.code == VectorWarning.Codes.MANUAL_EDIT_INVALID_COLOR })
        // The invalid color is ignored; the original stroke survives.
        assertEquals("#000000", pathByName(result.document, "keep").style.strokeColor)
    }

    @Test
    fun emptyEditIsNoOpWarning() {
        val doc = parse(twoPathsXml)
        val result = VectorManualEditApplier.apply(doc, twoPathsXml, emptyList())
        assertTrue(result.warnings.any { it.code == VectorWarning.Codes.MANUAL_EDIT_EMPTY })
        assertEquals(twoPathsXml, result.xml)
        assertEquals(0, result.editedPathCount)
        assertEquals(0, result.deletedPathCount)
    }

    @Test
    fun resultXmlParsesAgain() {
        val doc = parse(twoPathsXml)
        val keepId = idOf(doc, "keep")
        val result = VectorManualEditApplier.apply(
            doc, twoPathsXml,
            listOf(VectorManualEdit.RecolorPaths(listOf(keepId), fillColor = "#FF0000")),
        )
        val reparsed = AndroidVectorDrawableParser.parse(result.xml)
        assertFalse(reparsed.warnings.any { it.code == VectorWarning.Codes.MALFORMED_XML })
        assertEquals(2, reparsed.allPaths().size)
    }

    @Test
    fun preservesViewportAndGroups() {
        val xml = vector(
            """
            <group android:name="layer" android:translateX="4" android:translateY="6">
                <path android:name="inner" android:pathData="M0,0 L10,10"
                    android:strokeColor="#000000" android:strokeWidth="1"/>
            </group>
            """.trimIndent(),
        )
        val doc = parse(xml)
        val id = idOf(doc, "inner")
        val result = VectorManualEditApplier.apply(
            doc, xml, listOf(VectorManualEdit.RecolorPaths(listOf(id), strokeColor = "#FF0000")),
        )
        val vp = result.document.viewport
        assertEquals(108f, vp.viewportWidth)
        assertEquals(108f, vp.viewportHeight)
        val group = result.document.allGroups().firstOrNull { it.name == "layer" }
        assertNotNull(group)
        assertEquals(4f, group!!.translateX)
        assertEquals(6f, group.translateY)
        assertEquals("#FF0000", pathByName(result.document, "inner").style.strokeColor)
    }
}
