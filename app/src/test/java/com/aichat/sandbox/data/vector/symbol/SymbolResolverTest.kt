package com.aichat.sandbox.data.vector.symbol

import com.aichat.sandbox.data.vector.PathDataParser
import com.aichat.sandbox.data.vector.VectorDocument
import com.aichat.sandbox.data.vector.VectorGroup
import com.aichat.sandbox.data.vector.VectorNode
import com.aichat.sandbox.data.vector.VectorPath
import com.aichat.sandbox.data.vector.VectorStyle
import com.aichat.sandbox.data.vector.VectorViewport
import com.aichat.sandbox.data.vector.VectorWarning
import com.aichat.sandbox.data.vector.allPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 (sub-feature 3) — `SymbolResolver.expand` is the pure engine behind
 * reusable vector symbols: it turns instances into plain groups+paths (so every
 * existing consumer keeps working) and makes "edit the master → every instance
 * updates" fall out of re-running expansion against an updated library.
 */
class SymbolResolverTest {

    private val EPS = 1e-4f

    private fun path(id: String, data: String, fill: String? = "#FF0000"): VectorNode.PathNode =
        VectorNode.PathNode(
            VectorPath(
                id = id,
                pathData = data,
                commands = PathDataParser.parse(data).commands,
                style = VectorStyle(fillColor = fill),
            ),
        )

    private fun symbol(id: String, vararg children: VectorNode): VectorSymbol =
        VectorSymbol(
            id = id,
            name = "sym-$id",
            viewport = VectorViewport(24f, 24f, 24f, 24f),
            root = VectorGroup(id = "sroot", children = children.toList()),
        )

    private fun host(vararg children: VectorNode): VectorDocument =
        VectorDocument(
            viewport = VectorViewport(24f, 24f, 24f, 24f),
            root = VectorGroup(id = "root", children = children.toList()),
        )

    private fun groupById(doc: VectorDocument, id: String): VectorGroup =
        (doc.root.children.first { it.id == id } as VectorNode.GroupNode).group

    @Test
    fun expand_singleInstance_producesGroupWithSymbolChildren() {
        val lib = mapOf("star" to symbol("star", path("body", "M2 2 L10 10")))
        val doc = host(VectorNode.InstanceNode(SymbolInstance(id = "i1", symbolId = "star")))

        val out = SymbolResolver.expand(doc, lib)

        val group = out.root.children.single() as VectorNode.GroupNode
        assertEquals("i1", group.id)
        val leaf = group.group.children.single() as VectorNode.PathNode
        assertEquals("i1/body", leaf.path.id)
        assertEquals("M2 2 L10 10", leaf.path.pathData)
        // No InstanceNode survives into the expanded tree.
        assertTrue(out.allPaths().map { it.id }.contains("i1/body"))
    }

    @Test
    fun expand_namespacesChildIdsUniquely() {
        val lib = mapOf("star" to symbol("star", path("body", "M2 2 L10 10")))
        val doc = host(
            VectorNode.InstanceNode(SymbolInstance(id = "i1", symbolId = "star")),
            VectorNode.InstanceNode(SymbolInstance(id = "i2", symbolId = "star")),
        )

        val ids = SymbolResolver.expand(doc, lib).allPaths().map { it.id }

        assertEquals(listOf("i1/body", "i2/body"), ids)
        assertEquals(ids.size, ids.toSet().size) // all unique
    }

    @Test
    fun expand_appliesInstanceTransformAndStyleOverride() {
        val lib = mapOf("star" to symbol("star", path("body", "M2 2 L10 10", fill = "#FF0000")))
        val doc = host(
            VectorNode.InstanceNode(
                SymbolInstance(
                    id = "i1",
                    symbolId = "star",
                    translateX = 5f,
                    translateY = 7f,
                    scaleX = 2f,
                    scaleY = 2f,
                    rotation = 90f,
                    styleOverride = VectorStyle(fillColor = "#00FF00"),
                ),
            ),
        )

        val out = SymbolResolver.expand(doc, lib)

        val group = groupById(out, "i1")
        assertEquals(5f, group.translateX!!, EPS)
        assertEquals(7f, group.translateY!!, EPS)
        assertEquals(2f, group.scaleX!!, EPS)
        assertEquals(2f, group.scaleY!!, EPS)
        assertEquals(90f, group.rotation!!, EPS)
        // Override folded onto the leaf path; original geometry untouched.
        val leaf = group.children.single() as VectorNode.PathNode
        assertEquals("#00FF00", leaf.path.style.fillColor)
        assertEquals("M2 2 L10 10", leaf.path.pathData)
    }

    @Test
    fun expand_masterEdit_propagatesToAllInstances() {
        val doc = host(
            VectorNode.InstanceNode(SymbolInstance(id = "i1", symbolId = "star")),
            VectorNode.InstanceNode(SymbolInstance(id = "i2", symbolId = "star")),
        )

        val v1 = mapOf("star" to symbol("star", path("body", "M2 2 L10 10")))
        val before = SymbolResolver.expand(doc, v1)
        assertTrue(before.allPaths().all { it.pathData == "M2 2 L10 10" })

        // Edit the master once; re-expand the *same* host document.
        val v2 = mapOf("star" to symbol("star", path("body", "M0 0 L5 5")))
        val after = SymbolResolver.expand(doc, v2)
        assertEquals(2, after.allPaths().size)
        assertTrue(after.allPaths().all { it.pathData == "M0 0 L5 5" })
    }

    @Test
    fun expand_unresolvedSymbol_dropsInstanceWithWarning() {
        val doc = host(VectorNode.InstanceNode(SymbolInstance(id = "i1", symbolId = "missing")))

        val out = SymbolResolver.expand(doc, emptyMap())

        assertTrue(out.root.children.isEmpty())
        assertTrue(out.warnings.any { it.code == VectorWarning.Codes.SYMBOL_UNRESOLVED })
    }

    @Test
    fun expand_cyclicSymbol_isDroppedWithoutInfiniteLoop() {
        // Symbol "a" instances itself.
        val symA = symbol(
            "a",
            path("leaf", "M0 0 L1 1"),
            VectorNode.InstanceNode(SymbolInstance(id = "inner", symbolId = "a")),
        )
        val doc = host(VectorNode.InstanceNode(SymbolInstance(id = "i1", symbolId = "a")))

        val out = SymbolResolver.expand(doc, mapOf("a" to symA))

        // The real leaf survives; the cyclic placement is dropped + flagged.
        assertEquals(listOf("i1/leaf"), out.allPaths().map { it.id })
        assertTrue(out.warnings.any { it.code == VectorWarning.Codes.SYMBOL_CYCLE })
    }

    @Test
    fun expand_noInstancesEmptyLibrary_returnsSameDocumentUnchanged() {
        val doc = host(path("plain", "M0 0 L4 4"))
        val out = SymbolResolver.expand(doc, emptyMap())
        // Fast-path identity: a symbol-free document is byte-identical (the regression contract).
        assertSame(doc, out)
        assertNull(out.allPaths().single().style.fill)
    }
}
