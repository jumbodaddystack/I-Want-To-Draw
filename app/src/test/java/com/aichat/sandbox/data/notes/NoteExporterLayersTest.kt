package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 17.3 — structure-preserving export: the SVG / VectorDrawable
 * exporters group items by layer when the caller passes the layer list, and
 * stay flat (byte-identical to pre-17.3) when it doesn't.
 */
class NoteExporterLayersTest {

    private fun note() = Note(
        id = "n", title = "Layered", backgroundStyle = "plain", schemaVersion = 1,
        minX = 0f, minY = 0f, maxX = 100f, maxY = 100f, thumbnailPath = null, ocrText = null,
    )

    private fun rect(id: String, layerId: String?, z: Int) = NoteItem(
        id = id, noteId = "n", zIndex = z, kind = Shape.KIND, tool = "shape_rect",
        colorArgb = 0xFF000000.toInt(), baseWidthPx = 2f,
        payload = ShapeCodec.encode(Shape.Rect(0f, 0f, 40f, 40f)),
        layerId = layerId,
    )

    private fun layer(id: String, name: String, ordinal: Int, opacity: Int = 100, visible: Boolean = true) =
        NoteLayer(id = id, noteId = "n", name = name, opacityPercent = opacity, visible = visible, locked = false, ordinal = ordinal)

    // ── SVG ──────────────────────────────────────────────────────────────

    @Test
    fun svgWithoutLayersStaysFlat() {
        val svg = NoteSvgExporter.renderSvg(note(), listOf(rect("a", "L1", 0)))
        assertTrue("flat output keeps the items group", svg.contains("<g id=\"items\">"))
        assertFalse(svg.contains("id=\"layer-"))
    }

    @Test
    fun svgWithLayersEmitsAGroupPerLayerInOrdinalOrder() {
        val layers = listOf(
            layer("L0", "Base", ordinal = 0),
            layer("L1", "Top", ordinal = 1, opacity = 50),
        )
        val items = listOf(rect("a", "L0", 0), rect("b", "L1", 1))
        val svg = NoteSvgExporter.renderSvg(note(), items, layers = layers)

        assertTrue(svg.contains("<g id=\"layer-0\""))
        assertTrue(svg.contains("<g id=\"layer-1\""))
        assertFalse("no flat group when layered", svg.contains("<g id=\"items\">"))
        // Base (ordinal 0) renders before Top (ordinal 1).
        assertTrue(svg.indexOf("layer-0") < svg.indexOf("layer-1"))
        // Layer opacity is baked onto the translucent group only.
        assertTrue(svg.contains("<g id=\"layer-1\" opacity=\"0.5\">"))
        assertFalse("full-opacity layer carries no opacity attr",
            svg.contains("<g id=\"layer-0\" opacity"))
        // The layer name survives as a comment.
        assertTrue(svg.contains("<!-- layer: Base -->"))
    }

    @Test
    fun svgDropsHiddenLayersAndBucketsDefaultItemsOnTop() {
        val layers = listOf(
            layer("L0", "Visible", ordinal = 0),
            layer("L1", "Hidden", ordinal = 1, visible = false),
        )
        val items = listOf(
            rect("a", "L0", 0),
            rect("b", "L1", 1),       // hidden — dropped
            rect("c", null, 2),       // default bucket — on top
        )
        val svg = NoteSvgExporter.renderSvg(note(), items, layers = layers)
        assertTrue(svg.contains("<g id=\"layer-0\""))
        assertFalse("hidden layer is not emitted", svg.contains("<g id=\"layer-1\""))
        assertTrue(svg.contains("<g id=\"layer-default\""))
        // Default bucket renders after the named layer.
        assertTrue(svg.indexOf("layer-0") < svg.indexOf("layer-default"))
    }

    // ── VectorDrawable ───────────────────────────────────────────────────

    @Test
    fun vectorWithoutLayersStaysFlat() {
        val xml = NoteVectorDrawableExporter.renderVectorDrawable(listOf(rect("a", "L1", 0)), sizeDp = 24)
        assertFalse(xml.contains("<group android:name="))
    }

    @Test
    fun vectorWithLayersEmitsNamedGroups() {
        val layers = listOf(
            layer("L0", "Base", ordinal = 0),
            layer("L1", "Top", ordinal = 1, opacity = 40),
        )
        val items = listOf(rect("a", "L0", 0), rect("b", "L1", 1))
        val xml = NoteVectorDrawableExporter.renderVectorDrawable(items, sizeDp = 24, layers = layers)

        assertTrue(xml.contains("<group android:name=\"Base\">"))
        assertTrue(xml.contains("<group android:name=\"Top\">"))
        assertTrue(xml.indexOf("\"Base\"") < xml.indexOf("\"Top\""))
        // VD groups have no opacity, so the 40% layer bakes alpha onto the
        // path. The rect is stroke-only (no fill), so it lands on strokeAlpha.
        assertTrue("layer opacity baked into stroke alpha", xml.contains("android:strokeAlpha=\"0.4\""))
    }

    @Test
    fun vectorSkippedCountIsLayerIndependent() {
        // An image item (unsupported by VectorDrawable) sits on a layer; it
        // still counts as skipped so the dialog warning matches the flat
        // computation. A real (decodable) payload keeps bounds computation
        // happy without touching android.graphics.
        val image = NoteItem(
            id = "img", noteId = "n", zIndex = 0, kind = NoteItem.KIND_IMAGE, tool = null,
            colorArgb = 0xFF000000.toInt(), baseWidthPx = 1f,
            payload = com.aichat.sandbox.ui.components.notes.ImageItemCodec.encode(
                com.aichat.sandbox.ui.components.notes.ImageItemCodec.ImagePayload(
                    relativePath = "x.png", naturalWidth = 10f, naturalHeight = 10f,
                    minX = 0f, minY = 0f, maxX = 10f, maxY = 10f,
                ),
            ),
            layerId = "L0",
        )
        val layers = listOf(layer("L0", "Base", ordinal = 0))
        val flat = NoteVectorDrawableExporter.render(listOf(image, rect("a", "L0", 1)), sizeDp = 24)
        val layered = NoteVectorDrawableExporter.render(
            listOf(image, rect("a", "L0", 1)), sizeDp = 24, layers = layers,
        )
        assertEquals(1, flat.skippedCount)
        assertEquals(flat.skippedCount, layered.skippedCount)
    }
}
