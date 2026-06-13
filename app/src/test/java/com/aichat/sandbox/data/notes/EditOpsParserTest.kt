package com.aichat.sandbox.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Sub-phase 7.2 — parser correctness, drift tolerance, and the 1000-case
 * fuzz from the 7.6 safety checklist (no crashes, no uncaught exceptions).
 */
class EditOpsParserTest {

    @Test
    fun parsesEveryDocumentedOp() {
        val raw = """
            ```edit-ops
            { "schema": 1, "summary": "ok",
              "ops": [
                { "op": "transform",  "ids": ["s_001"], "matrix": [1,0,5, 0,1,3, 0,0,1] },
                { "op": "recolor",    "ids": ["s_002"], "color": "#FF8800" },
                { "op": "restyle",    "ids": ["s_003"], "width": 3.5, "opacity": 0.8 },
                { "op": "replace_with_shape", "id": "s_004",
                  "shape": { "type": "ellipse", "cx": 10, "cy": 10, "rx": 5, "ry": 3 } },
                { "op": "smooth",     "ids": ["s_005"], "amount": 0.5 },
                { "op": "simplify",   "ids": ["s_006"], "tolerance": 1.5 },
                { "op": "delete",     "ids": ["s_007"] },
                { "op": "set_layer",  "ids": ["s_008"], "layer": "L1" },
                { "op": "group",      "ids": ["s_009"] },
                { "op": "merge_paths","ids": ["s_010", "s_011"] }
              ]
            }
            ```
        """.trimIndent()
        val doc = EditOpsParser.parse(raw).getOrThrow()
        assertEquals(10, doc.ops.size)
        assertEquals("ok", doc.summary)
        assertTrue(doc.ops[0] is EditOp.Transform)
        assertTrue(doc.ops[1] is EditOp.Recolor)
        assertTrue(doc.ops[2] is EditOp.Restyle)
        assertTrue(doc.ops[3] is EditOp.ReplaceWithShape)
        assertTrue(doc.ops[4] is EditOp.Smooth)
        assertTrue(doc.ops[5] is EditOp.Simplify)
        assertTrue(doc.ops[6] is EditOp.Delete)
        assertTrue(doc.ops[7] is EditOp.SetLayer)
        assertTrue(doc.ops[8] is EditOp.Group)
        assertTrue(doc.ops[9] is EditOp.MergePaths)
    }

    @Test
    fun mergePathsRequiresAtLeastTwoIds() {
        val raw = """{ "summary": "", "ops": [
            { "op": "merge_paths", "ids": ["only_one"] }
        ]}"""
        val doc = EditOpsParser.parse(raw).getOrThrow()
        assertEquals(0, doc.ops.size)
        assertFalse(doc.rejected.isEmpty())
    }

    @Test
    fun toleratesProseAroundJsonBlock() {
        val raw = """
            Sure, here's the edit:

            ```edit-ops
            { "schema": 1, "summary": "x", "ops": [
              { "op": "delete", "ids": ["s_1"] }
            ] }
            ```

            Hope that helps!
        """.trimIndent()
        val doc = EditOpsParser.parse(raw).getOrThrow()
        assertEquals(1, doc.ops.size)
    }

    @Test
    fun acceptsUnfencedJson() {
        val raw = """
            { "summary": "ok", "ops": [ { "op": "delete", "ids": ["s_1"] } ] }
        """.trimIndent()
        val doc = EditOpsParser.parse(raw).getOrThrow()
        assertEquals(1, doc.ops.size)
    }

    @Test
    fun emptyReplyFailsCleanly() {
        val result = EditOpsParser.parse("")
        assertTrue(result.isFailure)
    }

    @Test
    fun unknownIdsDroppedWhenKnownSetProvided() {
        val raw = """
            { "summary": "", "ops": [
              { "op": "delete", "ids": ["s_real", "s_fake"] }
            ] }
        """.trimIndent()
        val doc = EditOpsParser.parse(raw, knownIds = setOf("s_real")).getOrThrow()
        assertEquals(1, doc.ops.size)
        val ids = (doc.ops[0] as EditOp.Delete).ids
        assertEquals(listOf("s_real"), ids)
    }

    @Test
    fun unknownLayerOnSetLayerRejected() {
        val raw = """
            { "summary": "", "ops": [
              { "op": "set_layer", "ids": ["s_1"], "layer": "Lz" }
            ] }
        """.trimIndent()
        val doc = EditOpsParser.parse(raw, knownIds = setOf("s_1"), knownLayers = setOf("L1")).getOrThrow()
        assertEquals(0, doc.ops.size)
        assertEquals(1, doc.rejected.size)
    }

    @Test
    fun matrixCanBeSixOrNine() {
        val six = """{ "summary": "", "ops": [
            { "op": "transform", "ids": ["s_1"], "matrix": [1,0,4, 0,1,8] }
        ]}"""
        val nine = """{ "summary": "", "ops": [
            { "op": "transform", "ids": ["s_1"], "matrix": [1,0,4, 0,1,8, 0,0,1] }
        ]}"""
        val a = (EditOpsParser.parse(six).getOrThrow().ops[0] as EditOp.Transform).matrix
        val b = (EditOpsParser.parse(nine).getOrThrow().ops[0] as EditOp.Transform).matrix
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun missingSummaryAndSchemaTolerated() {
        val raw = """{ "ops": [ { "op": "delete", "ids": ["s_1"] } ] }"""
        val doc = EditOpsParser.parse(raw).getOrThrow()
        assertEquals("", doc.summary)
        assertEquals(EditOpsDoc.SCHEMA, doc.schema)
    }

    @Test
    fun unknownOpsLandInRejectedList() {
        val raw = """{ "summary": "", "ops": [
            { "op": "frobnicate", "ids": ["s_1"] },
            { "op": "delete", "ids": ["s_1"] }
        ]}"""
        val doc = EditOpsParser.parse(raw).getOrThrow()
        assertEquals(1, doc.ops.size)
        assertEquals(1, doc.rejected.size)
    }

    @Test
    fun fuzzingNeverThrows() {
        val random = Random(42)
        val pieces = listOf(
            "```edit-ops", "```", "{", "}", "\"op\":", "\"transform\"",
            "\"ids\":", "\"s_1\"", "[", "]", ",", "matrix", "1.0", "null",
            ":", "\"", "\\", "\n", " ", "true", "false", "0", "9999",
            "color", "#FFAABB", "shape", "type", "ellipse",
        )
        repeat(1000) {
            val len = random.nextInt(0, 60)
            val sb = StringBuilder()
            repeat(len) { sb.append(pieces.random(random)) }
            // Must not throw — every call returns a Result.
            val result = EditOpsParser.parse(sb.toString())
            assertNotNull(result)
        }
    }

    @Test
    fun replaceWithShapeRequiresKnownSourceId() {
        val raw = """{ "summary": "", "ops": [
            { "op": "replace_with_shape", "id": "s_fake",
              "shape": { "type": "rect", "x0": 0, "y0": 0, "x1": 10, "y1": 10 } }
        ]}"""
        val doc = EditOpsParser.parse(raw, knownIds = setOf("s_real")).getOrThrow()
        assertEquals(0, doc.ops.size)
        assertFalse(doc.rejected.isEmpty())
    }
}
