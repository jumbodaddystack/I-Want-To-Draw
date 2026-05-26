package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 7 — per-path catalog for the manual editing UI. */
class VectorPathCatalogTest {

    private fun vector(body: String): String = """
        <vector xmlns:android="http://schemas.android.com/apk/res/android"
            android:width="108dp" android:height="108dp"
            android:viewportWidth="108" android:viewportHeight="108">
        $body
        </vector>
    """.trimIndent()

    private fun catalog(xml: String): List<VectorPathCatalogEntry> =
        VectorPathCatalog.catalog(AndroidVectorDrawableParser.parse(xml))

    @Test
    fun catalogListsPathsDepthFirst() {
        val entries = catalog(
            vector(
                """
                <path android:name="root1" android:pathData="M0,0 L1,1"/>
                <group android:name="layer">
                    <path android:name="inner1" android:pathData="M2,2 L3,3"/>
                    <path android:name="inner2" android:pathData="M4,4 L5,5"/>
                </group>
                <path android:name="root2" android:pathData="M6,6 L7,7"/>
                """.trimIndent(),
            ),
        )
        assertEquals(4, entries.size)
        assertEquals(listOf("root1", "inner1", "inner2", "root2"), entries.map { it.name })
        assertEquals(listOf(0, 1, 2, 3), entries.map { it.index })
        // Root-level paths have no parent group; the inner ones share one.
        assertNull(entries[0].groupId)
        assertNotNull(entries[1].groupId)
        assertEquals(entries[1].groupId, entries[2].groupId)
        assertNull(entries[3].groupId)
    }

    @Test
    fun catalogIncludesStyleAndBounds() {
        val entries = catalog(
            vector(
                """
                <path android:name="p" android:pathData="M10,10 L20,30"
                    android:fillColor="#FF0000" android:strokeColor="#00FF00"
                    android:strokeWidth="2.5"/>
                """.trimIndent(),
            ),
        )
        val e = entries.single()
        assertEquals("#FF0000", e.fillColor)
        assertEquals("#00FF00", e.strokeColor)
        assertEquals(2.5f, e.strokeWidth)
        val bounds = e.bounds!!
        assertEquals(10f, bounds.minX)
        assertEquals(10f, bounds.minY)
        assertEquals(20f, bounds.maxX)
        assertEquals(30f, bounds.maxY)
    }

    @Test
    fun catalogIncludesCommandCounts() {
        val entries = catalog(
            vector(
                """<path android:name="p" android:pathData="M0,0 L1,1 L2,2 Z"/>""",
            ),
        )
        // 1 move + 2 line + close = 4 commands.
        assertEquals(4, entries.single().commandCount)
    }

    @Test
    fun catalogHandlesUnparsedPaths() {
        val entries = catalog(
            vector(
                """<path android:name="bad" android:pathData="???" android:fillColor="#FF0000"/>""",
            ),
        )
        val e = entries.single()
        assertEquals(0, e.commandCount)
        assertNull(e.bounds)
    }

    @Test
    fun catalogNeverThrows() {
        // Malformed XML yields a safe empty document; catalog must be empty.
        assertTrue(catalog("not even xml").isEmpty())
        assertTrue(catalog(vector("")).isEmpty())
    }
}
