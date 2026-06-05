package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 3 — the icon-set exporter pins the wire format against the existing parser. */
class IconSetExporterTest {

    private fun master(edge: Float, pathData: String): VectorDocument = VectorDocument(
        viewport = VectorViewport(edge, edge, edge, edge),
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
    fun exportSet_emits_one_artifact_per_size_and_format() {
        val spec = IconSetExporter.Spec(
            sizes = IconSizeSet(master(24f, "M4 4 L20 20 L4 20 Z")),
            formats = setOf(
                IconSetExporter.Format.ANDROID_VECTOR_DRAWABLE,
                IconSetExporter.Format.SVG,
            ),
        )
        val artifacts = IconSetExporter.exportSet(spec)
        // 3 sizes × 2 formats.
        assertEquals(6, artifacts.size)
        assertEquals(6, artifacts.map { it.filename }.distinct().size)
    }

    @Test
    fun exported_vector_drawable_reimports_geometry_stable() {
        val spec = IconSetExporter.Spec(
            sizes = IconSizeSet(master(24f, "M4 4 L20 20 L4 20 Z"), targets = listOf(IconTarget.MEDIUM_48)),
            formats = setOf(IconSetExporter.Format.ANDROID_VECTOR_DRAWABLE),
            quantize = true,
        )
        val artifact = IconSetExporter.exportSet(spec).single()
        val reparsed = AndroidVectorDrawableParser.parse(artifact.content)
        val expected = VectorQuantizer.quantize(spec.sizes.derive(IconTarget.MEDIUM_48))
        val a = reparsed.allPaths().single().commands!!
        val b = expected.allPaths().single().commands!!
        assertEquals(b.size, a.size)
        for (i in a.indices) {
            assertEquals(coords(b[i]), coords(a[i]))
        }
    }

    @Test
    fun quantized_export_has_only_integer_coordinates() {
        val spec = IconSetExporter.Spec(
            sizes = IconSizeSet(master(24f, "M4.3 4.7 L19.9 20.2 Z"), targets = listOf(IconTarget.MATERIAL_24)),
            formats = setOf(
                IconSetExporter.Format.ANDROID_VECTOR_DRAWABLE,
                IconSetExporter.Format.SVG,
            ),
        )
        for (artifact in IconSetExporter.exportSet(spec)) {
            val data = when (artifact.format) {
                IconSetExporter.Format.ANDROID_VECTOR_DRAWABLE ->
                    Regex("android:pathData=\"([^\"]*)\"").find(artifact.content)!!.groupValues[1]
                IconSetExporter.Format.SVG ->
                    Regex(" d=\"([^\"]*)\"").find(artifact.content)!!.groupValues[1]
            }
            for (m in Regex("-?\\d+(\\.\\d+)?").findAll(data)) {
                assertFalse("non-integer coord ${m.value} in ${artifact.filename}", m.value.contains('.'))
            }
        }
    }

    @Test
    fun svg_and_vd_describe_same_geometry() {
        val spec = IconSetExporter.Spec(
            sizes = IconSizeSet(master(24f, "M2 2 L22 2 L22 22 Z"), targets = listOf(IconTarget.MATERIAL_24)),
            formats = setOf(
                IconSetExporter.Format.ANDROID_VECTOR_DRAWABLE,
                IconSetExporter.Format.SVG,
            ),
        )
        val artifacts = IconSetExporter.exportSet(spec)
        val vd = artifacts.first { it.format == IconSetExporter.Format.ANDROID_VECTOR_DRAWABLE }
        val svg = artifacts.first { it.format == IconSetExporter.Format.SVG }
        val vdData = Regex("android:pathData=\"([^\"]*)\"").find(vd.content)!!.groupValues[1]
        val svgData = Regex(" d=\"([^\"]*)\"").find(svg.content)!!.groupValues[1]
        // Both writers format from the same commands, so the data strings match.
        assertEquals(vdData, svgData)
    }

    @Test
    fun exportBatch_emits_N_well_formed_documents() {
        val specA = IconSetExporter.Spec(
            sizes = IconSizeSet(master(24f, "M4 4 L20 20 Z")),
            formats = setOf(IconSetExporter.Format.ANDROID_VECTOR_DRAWABLE),
        )
        val specB = IconSetExporter.Spec(
            sizes = IconSizeSet(master(24f, "M0 0 L24 0 L24 24 Z")),
            formats = setOf(IconSetExporter.Format.ANDROID_VECTOR_DRAWABLE),
        )
        val artifacts = IconSetExporter.exportBatch(listOf("alpha" to specA, "beta" to specB))
        assertEquals(6, artifacts.size) // 2 masters × 3 sizes × 1 format
        for (artifact in artifacts) {
            val doc = AndroidVectorDrawableParser.parse(artifact.content)
            assertTrue(
                "unexpected warnings in ${artifact.filename}: ${doc.warnings}",
                doc.warnings.none { it.code.startsWith("MALFORMED") },
            )
        }
        // Batch filenames carry each master's name.
        assertTrue(artifacts.any { it.filename.startsWith("alpha_") })
        assertTrue(artifacts.any { it.filename.startsWith("beta_") })
    }

    private fun coords(c: PathCommand): List<Float> = when (c) {
        is PathCommand.MoveTo -> listOf(c.x, c.y)
        is PathCommand.LineTo -> listOf(c.x, c.y)
        is PathCommand.HorizontalTo -> listOf(c.x)
        is PathCommand.VerticalTo -> listOf(c.y)
        is PathCommand.CubicTo -> listOf(c.x1, c.y1, c.x2, c.y2, c.x, c.y)
        is PathCommand.SmoothCubicTo -> listOf(c.x2, c.y2, c.x, c.y)
        is PathCommand.QuadTo -> listOf(c.x1, c.y1, c.x, c.y)
        is PathCommand.SmoothQuadTo -> listOf(c.x, c.y)
        is PathCommand.ArcTo -> listOf(c.x, c.y)
        is PathCommand.Close -> emptyList()
    }
}
