package com.aichat.sandbox.data.vector.edit

import com.aichat.sandbox.data.vector.PathDataParser
import com.aichat.sandbox.data.vector.VectorDocument
import com.aichat.sandbox.data.vector.VectorGroup
import com.aichat.sandbox.data.vector.VectorNode
import com.aichat.sandbox.data.vector.VectorPath
import com.aichat.sandbox.data.vector.VectorStyle
import com.aichat.sandbox.data.vector.VectorViewport
import com.aichat.sandbox.data.vector.allPaths
import com.aichat.sandbox.data.vector.replacePath
import org.junit.Assert.assertEquals
import org.junit.Test

/** Phase 1 — [replacePath] swaps a path in-place anywhere in the tree. */
class VectorDocumentReplacePathTest {

    private fun path(id: String, data: String) = VectorPath(
        id = id,
        pathData = data,
        commands = PathDataParser.parse(data).commands,
        style = VectorStyle(),
    )

    private fun docWithNestedPaths(): VectorDocument {
        val inner = VectorGroup(
            id = "g1",
            children = listOf(
                VectorNode.PathNode(path("a", "M0,0 L1,0")),
                VectorNode.PathNode(path("b", "M0,0 L2,0")),
            ),
        )
        val root = VectorGroup(
            id = "root",
            children = listOf(
                VectorNode.PathNode(path("c", "M0,0 L3,0")),
                VectorNode.GroupNode(inner),
            ),
        )
        return VectorDocument(
            viewport = VectorViewport(24f, 24f, 24f, 24f),
            root = root,
        )
    }

    @Test
    fun replacesNestedPathPreservingPositionAndSiblings() {
        val doc = docWithNestedPaths()
        val replacement = path("b", "M0,0 C1,1 2,2 3,3")
        val updated = doc.replacePath("b", replacement)

        val paths = updated.allPaths()
        assertEquals(listOf("c", "a", "b"), paths.map { it.id })
        assertEquals("M0,0 C1,1 2,2 3,3", paths.first { it.id == "b" }.pathData)
        // Untouched siblings keep their original data.
        assertEquals("M0,0 L1,0", paths.first { it.id == "a" }.pathData)
        assertEquals("M0,0 L3,0", paths.first { it.id == "c" }.pathData)
    }

    @Test
    fun unknownIdReturnsDocumentUnchanged() {
        val doc = docWithNestedPaths()
        val updated = doc.replacePath("does-not-exist", path("z", "M0,0"))
        assertEquals(doc, updated)
    }
}
