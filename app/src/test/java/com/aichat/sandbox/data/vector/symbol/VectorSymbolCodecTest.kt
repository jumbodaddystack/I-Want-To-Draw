package com.aichat.sandbox.data.vector.symbol

import com.aichat.sandbox.data.vector.PathDataParser
import com.aichat.sandbox.data.vector.VectorGroup
import com.aichat.sandbox.data.vector.VectorNode
import com.aichat.sandbox.data.vector.VectorPath
import com.aichat.sandbox.data.vector.VectorStyle
import com.aichat.sandbox.data.vector.VectorViewport
import com.aichat.sandbox.data.vector.allPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 (sub-feature 3) — `VectorSymbolCodec` is the pure boundary that lets a
 * symbol persist as a single string blob (one Room column) and rebuild exactly.
 * It reuses the editor's lossless `AndroidVectorDrawableWriter`/`Parser` pair, so
 * these tests run headless (no Android framework).
 */
class VectorSymbolCodecTest {

    private fun path(id: String, data: String, fill: String?): VectorNode.PathNode =
        VectorNode.PathNode(
            VectorPath(
                id = id,
                pathData = data,
                commands = PathDataParser.parse(data).commands,
                style = VectorStyle(fillColor = fill),
            ),
        )

    private fun symbol(
        id: String = "sym1",
        name: String = "Star",
        vararg children: VectorNode,
    ): VectorSymbol =
        VectorSymbol(
            id = id,
            name = name,
            viewport = VectorViewport(24f, 24f, 24f, 24f),
            root = VectorGroup(id = "sroot", children = children.toList()),
        )

    @Test
    fun encodeDecode_preservesViewportAndGeometry() {
        val original = symbol(
            children = arrayOf(
                path("a", "M2 2 L10 10 L2 10 Z", "#FF0000"),
                path("b", "M4 4 C6 4 8 6 8 8", "#0000FF"),
            ),
        )

        val blob = VectorSymbolCodec.encode(original)
        val decoded = VectorSymbolCodec.decode(original.id, original.name, blob)

        assertEquals(original.viewport, decoded.viewport)
        assertEquals(original.root.children.size, decoded.root.children.size)
        // Geometry survives: re-encoding the decoded symbol is byte-identical
        // (the strongest round-trip invariant for the stored blob).
        assertEquals(blob, VectorSymbolCodec.encode(decoded))
    }

    @Test
    fun decode_takesIdentityFromArgumentsNotBlob() {
        val blob = VectorSymbolCodec.encode(
            symbol(id = "authored", name = "Authored", children = arrayOf(path("a", "M0 0 L5 5", "#00FF00"))),
        )

        // A different row id/name must win — identity lives in the columns, not the blob.
        val decoded = VectorSymbolCodec.decode("row-id", "Renamed", blob)

        assertEquals("row-id", decoded.id)
        assertEquals("Renamed", decoded.name)
    }

    @Test
    fun roundTrip_keepsPathCommandStructureAndFill() {
        val data = "M2 2 L10 10 L2 10 Z"
        val original = symbol(children = arrayOf(path("a", data, "#FF0000")))

        val decoded = VectorSymbolCodec.decode("x", "y", VectorSymbolCodec.encode(original))

        val decodedPath = decoded.root.allPathNodes().single()
        // VectorDrawable reissues ids and may re-serialize path data, so compare
        // the re-parsed command structure + style rather than the raw string.
        val expected = PathDataParser.parse(data).commands.map { it.javaClass.simpleName }
        val actual = PathDataParser.parse(decodedPath.pathData).commands.map { it.javaClass.simpleName }
        assertEquals(expected, actual)
        assertTrue(decodedPath.style.fillColor!!.endsWith("FF0000", ignoreCase = true))
    }

    private fun VectorGroup.allPathNodes(): List<VectorPath> =
        children.flatMap {
            when (it) {
                is VectorNode.PathNode -> listOf(it.path)
                is VectorNode.GroupNode -> it.group.allPathNodes()
                is VectorNode.InstanceNode -> emptyList()
            }
        }
}
