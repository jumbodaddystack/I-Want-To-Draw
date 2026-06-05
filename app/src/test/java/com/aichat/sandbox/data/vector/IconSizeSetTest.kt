package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Test

/** Phase 3 — multi-size derivation is a pure uniform scale + optional optical trim. */
class IconSizeSetTest {

    private fun master(edge: Float, pathData: String, strokeWidth: Float? = null): VectorDocument =
        VectorDocument(
            viewport = VectorViewport(edge, edge, edge, edge),
            root = VectorGroup(
                id = "root",
                children = listOf(
                    VectorNode.PathNode(
                        VectorPath(
                            id = "p",
                            pathData = pathData,
                            commands = PathDataParser.parse(pathData).commands,
                            style = VectorStyle(fillColor = "#000000", strokeWidth = strokeWidth),
                        ),
                    ),
                ),
            ),
        )

    private fun firstMove(doc: VectorDocument): PathCommand.MoveTo =
        doc.allPaths().single().commands!!.first() as PathCommand.MoveTo

    @Test
    fun derive_24_keeps_geometry() {
        val m = master(24f, "M4 4 L20 20")
        val out = IconSizeSet(m).derive(IconTarget.MATERIAL_24)
        assertEquals(24f, out.viewport.viewportWidth, EPS)
        val move = firstMove(out)
        assertEquals(4f, move.x, EPS)
        assertEquals(4f, move.y, EPS)
    }

    @Test
    fun derive_48_doubles_viewport_and_coords_and_stroke() {
        val m = master(24f, "M4 4 L20 20", strokeWidth = 1.5f)
        val out = IconSizeSet(m).derive(IconTarget.MEDIUM_48)
        assertEquals(48f, out.viewport.viewportWidth, EPS)
        assertEquals(48f, out.viewport.heightDp, EPS)
        val move = firstMove(out)
        assertEquals(8f, move.x, EPS)
        assertEquals(8f, move.y, EPS)
        assertEquals(3f, out.allPaths().single().style.strokeWidth!!, EPS)
    }

    @Test
    fun derive_108_scales_correctly() {
        val m = master(24f, "M0 0 L24 24")
        val out = IconSizeSet(m).derive(IconTarget.ADAPTIVE_108)
        assertEquals(108f, out.viewport.viewportWidth, EPS)
        val line = out.allPaths().single().commands!![1] as PathCommand.LineTo
        assertEquals(108f, line.x, EPS) // 24 * (108/24) = 108
        assertEquals(108f, line.y, EPS)
    }

    @Test
    fun optical_adjust_scales_about_center() {
        val m = master(24f, "M0 12 L24 12") // spans the full width at mid-height
        val set = IconSizeSet(
            m,
            adjust = mapOf(IconTarget.MATERIAL_24 to OpticalAdjust(scale = 0.9f)),
        )
        val out = set.derive(IconTarget.MATERIAL_24)
        val move = firstMove(out)
        // Centre is (12,12). x=0 moves inward 10% of half-span: 12 - 12*0.9 = 1.2.
        assertEquals(1.2f, move.x, EPS)
        // The mid-height y=12 stays fixed (it is the centre).
        assertEquals(12f, move.y, EPS)
        val line = out.allPaths().single().commands!![1] as PathCommand.LineTo
        assertEquals(22.8f, line.x, EPS) // 12 + 12*0.9
    }

    @Test
    fun deriveAll_emits_one_doc_per_target_with_correct_viewport() {
        val m = master(24f, "M2 2 L22 22")
        val all = IconSizeSet(m).deriveAll()
        assertEquals(3, all.size)
        assertEquals(24f, all[IconTarget.MATERIAL_24]!!.viewport.viewportWidth, EPS)
        assertEquals(48f, all[IconTarget.MEDIUM_48]!!.viewport.viewportWidth, EPS)
        assertEquals(108f, all[IconTarget.ADAPTIVE_108]!!.viewport.viewportWidth, EPS)
    }

    companion object {
        private const val EPS = 1e-3f
    }
}
