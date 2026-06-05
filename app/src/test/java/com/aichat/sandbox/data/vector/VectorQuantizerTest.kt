package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3 — grid-quantize is lossless, idempotent, clamped, and kind-preserving. */
class VectorQuantizerTest {

    private fun docOf(pathData: String, vp: Float = 24f): VectorDocument = VectorDocument(
        viewport = VectorViewport(vp, vp, vp, vp),
        root = VectorGroup(
            id = "root",
            children = listOf(
                VectorNode.PathNode(
                    VectorPath(
                        id = "p",
                        pathData = pathData,
                        commands = PathDataParser.parse(pathData).commands,
                        style = VectorStyle(fillColor = "#000000"),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun quantize_lands_all_coords_on_integers() {
        val doc = docOf("M2.4 3.6 L20.1 7.9 C8.2,9.7 4.4,12.3 6.6,18.8 Z")
        val q = VectorQuantizer.quantize(doc)
        val data = AndroidVectorDrawableWriter.write(q)
        // Every numeric token in the emitted pathData should be an integer.
        val nums = Regex("-?\\d+(\\.\\d+)?").findAll(pathDataAttr(data))
        for (m in nums) {
            assertTrue("non-integer coord: ${m.value}", !m.value.contains('.'))
        }
    }

    @Test
    fun quantize_is_idempotent() {
        val doc = docOf("M2.4 3.6 L20.1 7.9 C8.2,9.7 4.4,12.3 6.6,18.8 Z")
        val once = VectorQuantizer.quantize(doc)
        val twice = VectorQuantizer.quantize(once)
        assertEquals(once.allPaths().single().commands, twice.allPaths().single().commands)
    }

    @Test
    fun quantize_clamps_into_viewport() {
        val doc = docOf("M23.9 -0.6 L25 30")
        val cmds = VectorQuantizer.quantize(doc).allPaths().single().commands!!
        val move = cmds[0] as PathCommand.MoveTo
        // 23.9 rounds to 24 (clamped to the 24 box), -0.6 rounds to -1 → clamped to 0.
        assertEquals(24f, move.x, EPS)
        assertEquals(0f, move.y, EPS)
        val line = cmds[1] as PathCommand.LineTo
        // 25 clamps to 24, 30 clamps to 24.
        assertEquals(24f, line.x, EPS)
        assertEquals(24f, line.y, EPS)
    }

    @Test
    fun quantize_preserves_command_kinds_and_passes_unparsed() {
        // One parseable path + one unparsed (null commands) path.
        val doc = VectorDocument(
            viewport = VectorViewport(24f, 24f, 24f, 24f),
            root = VectorGroup(
                id = "root",
                children = listOf(
                    VectorNode.PathNode(
                        VectorPath(
                            id = "p1",
                            pathData = "M1.2 1.2 H10.7 V9.3 Z",
                            commands = PathDataParser.parse("M1.2 1.2 H10.7 V9.3 Z").commands,
                            style = VectorStyle(),
                        ),
                    ),
                    VectorNode.PathNode(
                        VectorPath(
                            id = "p2",
                            pathData = "garbage-data",
                            commands = null,
                            style = VectorStyle(),
                        ),
                    ),
                ),
            ),
        )
        val q = VectorQuantizer.quantize(doc)
        val p1 = q.allPaths().first { it.id == "p1" }.commands!!
        assertTrue(p1[0] is PathCommand.MoveTo)
        val h = p1[1] as PathCommand.HorizontalTo
        assertEquals(11f, h.x, EPS) // 10.7 → 11 on its single axis
        val v = p1[2] as PathCommand.VerticalTo
        assertEquals(9f, v.y, EPS) // 9.3 → 9
        assertTrue(p1[3] is PathCommand.Close)
        // Unparsed path passes through verbatim.
        val p2 = q.allPaths().first { it.id == "p2" }
        assertEquals(null, p2.commands)
        assertEquals("garbage-data", p2.pathData)
    }

    @Test
    fun quantize_step_supports_device_pixel_grid() {
        // On a 48 viewport, a 24dp device grid → step = 48/24 = 2.
        val doc = docOf("M3 5 L9 21", vp = 48f)
        val cmds = VectorQuantizer.quantize(doc, step = 2f).allPaths().single().commands!!
        val move = cmds[0] as PathCommand.MoveTo
        assertEquals(4f, move.x, EPS) // 3 → nearest multiple of 2 = 4
        assertEquals(4f, move.y, EPS) // 5 → 4
        val line = cmds[1] as PathCommand.LineTo
        assertEquals(8f, line.x, EPS) // 9 → 8
        assertEquals(20f, line.y, EPS) // 21 → 20
    }

    private fun pathDataAttr(xml: String): String =
        Regex("android:pathData=\"([^\"]*)\"").find(xml)!!.groupValues[1]

    companion object {
        private const val EPS = 1e-4f
    }
}
