package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Exercises [VectorEditPlanParser]: fenced/plain extraction, validation against
 * known path IDs and colors, numeric clamping, and graceful handling of unknown
 * ops and malformed replies (never throws).
 */
class VectorEditPlanParserTest {

    private val knownPathIds = setOf("p_001", "p_002", "p_003")
    private val knownColors = setOf("#109F5C", "#2D2D2D")

    private fun parse(raw: String) =
        VectorEditPlanParser.parse(raw, knownPathIds, knownColors)

    @Test
    fun parseFencedVectorEditPlan() {
        val raw = """
            Here is the plan:
            ```vector-edit-plan
            {
              "schema": 1,
              "mode": "tune_up",
              "summary": "Simplify and normalize.",
              "operations": [
                { "op": "simplify_paths", "target": { "colors": ["#109F5C"], "strokedOnly": true },
                  "tolerance": 0.25, "minPathLength": 0.01, "simplifyFills": false },
                { "op": "restyle_paths", "target": { "colors": ["#109F5C"], "strokedOnly": true },
                  "strokeWidth": 1.2, "lineCap": "round", "lineJoin": "round" }
              ]
            }
            ```
        """.trimIndent()

        val plan = parse(raw).getOrThrow()
        assertEquals(VectorEditPlan.Mode.TUNE_UP, plan.mode)
        assertEquals("Simplify and normalize.", plan.summary)
        assertEquals(2, plan.operations.size)
        assertTrue(plan.operations[0] is VectorEditOperation.SimplifyPaths)
        assertTrue(plan.operations[1] is VectorEditOperation.RestylePaths)
        assertTrue(plan.rejected.isEmpty())
    }

    @Test
    fun parsePlainJsonFallback() {
        val raw = """
            { "schema": 1, "operations": [
              { "op": "remove_paths", "target": { "pathIds": ["p_002"] } } ] }
        """.trimIndent()

        val plan = parse(raw).getOrThrow()
        assertEquals(1, plan.operations.size)
        val op = plan.operations.single()
        assertTrue(op is VectorEditOperation.RemovePaths)
        assertEquals(listOf("p_002"), op.target.pathIds)
    }

    @Test
    fun parseRejectsUnknownPathIds() {
        val raw = """
            { "operations": [
              { "op": "remove_paths", "target": { "pathIds": ["p_999"] } } ] }
        """.trimIndent()

        val plan = parse(raw).getOrThrow()
        assertTrue("op with only unknown ids must be rejected", plan.operations.isEmpty())
        assertEquals(1, plan.rejected.size)
        assertTrue(plan.rejected.single().reason.contains("unknown path id"))
    }

    @Test
    fun parseRejectsMalformedColors() {
        val raw = """
            { "operations": [
              { "op": "recolor_paths", "target": { "all": true }, "strokeColor": "blue" } ] }
        """.trimIndent()

        val plan = parse(raw).getOrThrow()
        assertTrue(plan.operations.isEmpty())
        assertTrue(plan.rejected.single().reason.contains("malformed color"))
    }

    @Test
    fun parseClampsUnsafeNumbers() {
        val raw = """
            { "operations": [
              { "op": "simplify_paths", "target": { "pathIds": ["p_001"] },
                "tolerance": 999, "minPathLength": 999 },
              { "op": "restyle_paths", "target": { "pathIds": ["p_001"] }, "strokeWidth": 999 } ] }
        """.trimIndent()

        val plan = parse(raw).getOrThrow()
        assertEquals(2, plan.operations.size)
        val simplify = plan.operations[0] as VectorEditOperation.SimplifyPaths
        assertEquals(4f, simplify.tolerance, 0.0001f)
        assertEquals(10f, simplify.minPathLength!!, 0.0001f)
        val restyle = plan.operations[1] as VectorEditOperation.RestylePaths
        assertEquals(64f, restyle.strokeWidth!!, 0.0001f)
    }

    @Test
    fun parseStoresUnknownOpsAsRejected() {
        val raw = """
            { "operations": [
              { "op": "frobnicate", "target": { "all": true } },
              { "op": "transform_paths", "target": { "all": true } },
              { "op": "remove_paths", "target": { "pathIds": ["p_001"] } } ] }
        """.trimIndent()

        val plan = parse(raw).getOrThrow()
        assertEquals(1, plan.operations.size)
        assertEquals(2, plan.rejected.size)
        assertTrue(plan.rejected.any { it.reason.contains("unknown op") })
        assertTrue(plan.rejected.any { it.reason.contains("unsupported in Phase 4") })
    }

    @Test
    fun parseEmptyOperationsPlan() {
        val raw = """{ "schema": 1, "mode": "tune_up", "summary": "nothing to do", "operations": [] }"""
        val plan = parse(raw).getOrThrow()
        assertTrue(plan.isEmpty)
        assertEquals("nothing to do", plan.summary)
    }

    @Test
    fun parseMalformedReplyReturnsFailureNotThrow() {
        assertTrue(parse("not json at all").isFailure)
        assertTrue(parse("{{{ broken").isFailure)
        assertTrue(parse("").isFailure)

        // Fuzz: random malformations must never throw.
        val rng = Random(1234)
        val alphabet = "{}[]\":,abc#p_001 \n0123.-"
        repeat(500) {
            val len = rng.nextInt(0, 80)
            val s = buildString { repeat(len) { append(alphabet[rng.nextInt(alphabet.length)]) } }
            // Should return a Result, never throw.
            VectorEditPlanParser.parse(s, knownPathIds, knownColors)
        }
    }
}
