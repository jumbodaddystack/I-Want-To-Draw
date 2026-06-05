package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 (sub-feature 2) — gradients/advanced fills round-trip through both
 * writers and parsers, and non-gradient documents stay byte-identical.
 */
class VectorFillRoundTripTest {

    private fun docWithFill(fill: VectorFill): VectorDocument = VectorDocument(
        viewport = VectorViewport(24f, 24f, 24f, 24f),
        root = VectorGroup(
            id = "root",
            children = listOf(
                VectorNode.PathNode(
                    VectorPath(
                        id = "p_001",
                        pathData = "M0 0 L24 0 L24 24 Z",
                        commands = PathDataParser.parse("M0 0 L24 0 L24 24 Z").commands,
                        style = VectorStyle(fill = fill),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun linearGradient_writesAaptAttr_andReparsesToEqualStops() {
        val fill = VectorFill.Linear(
            x1 = 0f, y1 = 0f, x2 = 24f, y2 = 24f,
            stops = listOf(GradientStop(0f, "#FF0000"), GradientStop(1f, "#0000FF")),
            tileMode = "clamp",
        )
        val xml = AndroidVectorDrawableWriter.write(docWithFill(fill))

        assertTrue("declares aapt ns", xml.contains("xmlns:aapt=\"http://schemas.android.com/aapt\""))
        assertTrue("nests aapt:attr", xml.contains("<aapt:attr name=\"android:fillColor\">"))
        assertTrue("linear gradient", xml.contains("android:type=\"linear\""))
        assertTrue("start coord", xml.contains("android:startX=\"0\""))
        assertTrue("end coord", xml.contains("android:endX=\"24\""))
        assertTrue("stop item", xml.contains("android:offset=\"0\" android:color=\"#FF0000\""))
        // No scalar fillColor attribute when a gradient is present.
        assertFalse("no scalar fill attr", xml.contains("android:fillColor=\""))

        val reparsed = AndroidVectorDrawableParser.parse(xml).allPaths().single()
        assertEquals(fill, reparsed.style.fill)
        assertNull(reparsed.style.fillColor)
    }

    @Test
    fun radialGradient_writesSvgDefs_andReparsesToEqualStops() {
        val fill = VectorFill.Radial(
            cx = 12f, cy = 12f, radius = 10f,
            stops = listOf(GradientStop(0f, "#FFFFFF"), GradientStop(1f, "#000000")),
        )
        val svg = VectorSvgWriter.write(docWithFill(fill))

        assertTrue("defs block", svg.contains("<defs>"))
        assertTrue("radial gradient", svg.contains("<radialGradient id=\"grad0\""))
        assertTrue("userSpaceOnUse", svg.contains("gradientUnits=\"userSpaceOnUse\""))
        assertTrue("center", svg.contains("cx=\"12\""))
        assertTrue("radius", svg.contains("r=\"10\""))
        assertTrue("stop", svg.contains("<stop offset=\"0\" stop-color=\"#FFFFFF\"/>"))
        assertTrue("url ref", svg.contains("fill=\"url(#grad0)\""))

        val reparsed = VectorSvgParser.parse(svg).allPaths().single()
        assertEquals(fill, reparsed.style.fill)
    }

    @Test
    fun linearGradient_reparsesThroughSvg() {
        val fill = VectorFill.Linear(
            x1 = 2f, y1 = 4f, x2 = 20f, y2 = 22f,
            stops = listOf(GradientStop(0f, "#112233"), GradientStop(0.5f, "#445566"), GradientStop(1f, "#778899")),
        )
        val svg = VectorSvgWriter.write(docWithFill(fill))
        assertTrue(svg.contains("<linearGradient id=\"grad0\""))
        val reparsed = VectorSvgParser.parse(svg).allPaths().single()
        assertEquals(fill, reparsed.style.fill)
    }

    @Test
    fun sweepGradient_svg_fallsBackToFirstStop_withWarning() {
        val fill = VectorFill.Sweep(
            cx = 12f, cy = 12f,
            stops = listOf(GradientStop(0f, "#FF0000"), GradientStop(1f, "#00FF00")),
        )
        val result = VectorSvgWriter.writeWithWarnings(docWithFill(fill))

        assertFalse("no sweep primitive emitted", result.svg.contains("sweep"))
        assertTrue("falls back to first stop", result.svg.contains("fill=\"#FF0000\""))
        assertTrue(
            "warns",
            result.warnings.any { it.code == VectorWarning.Codes.SVG_GRADIENT_UNSUPPORTED },
        )
    }

    @Test
    fun sweepGradient_roundTripsThroughAndroid() {
        val fill = VectorFill.Sweep(
            cx = 12f, cy = 12f,
            stops = listOf(GradientStop(0f, "#FF0000"), GradientStop(1f, "#00FF00")),
        )
        val xml = AndroidVectorDrawableWriter.write(docWithFill(fill))
        assertTrue(xml.contains("android:type=\"sweep\""))
        assertTrue(xml.contains("android:centerX=\"12\""))
        val reparsed = AndroidVectorDrawableParser.parse(xml).allPaths().single()
        assertEquals(fill, reparsed.style.fill)
    }

    @Test
    fun solidFill_unchanged_existingDocumentsByteIdentical() {
        val source = """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="24dp" android:height="24dp"
                android:viewportWidth="24" android:viewportHeight="24">
                <path android:pathData="M0 0 L24 0 L24 24 Z" android:fillColor="#3366CC" android:fillAlpha="0.5"/>
            </vector>
        """.trimIndent()
        val parsed = AndroidVectorDrawableParser.parse(source)
        assertNull("no gradient parsed", parsed.allPaths().single().style.fill)

        val xml = AndroidVectorDrawableWriter.write(parsed)
        assertFalse("no aapt namespace leaked", xml.contains("aapt"))
        assertFalse("no gradient leaked", xml.contains("<gradient"))
        assertTrue("scalar fill preserved", xml.contains("android:fillColor=\"#3366CC\""))

        val svg = VectorSvgWriter.write(parsed)
        assertFalse("no defs leaked", svg.contains("<defs>"))
        assertFalse("no gradient leaked", svg.contains("Gradient"))
        assertTrue("scalar fill preserved", svg.contains("fill=\"#3366CC\""))
    }

    @Test
    fun stopOpacity_roundTripsThroughSvg() {
        val fill = VectorFill.Linear(
            x1 = 0f, y1 = 0f, x2 = 24f, y2 = 0f,
            // 50%-alpha red (#80FF0000) and an opaque blue.
            stops = listOf(GradientStop(0f, "#80FF0000"), GradientStop(1f, "#0000FF")),
        )
        val svg = VectorSvgWriter.write(docWithFill(fill))
        assertTrue("emits stop-opacity", svg.contains("stop-opacity="))
        val reparsed = VectorSvgParser.parse(svg).allPaths().single().style.fill
        reparsed as VectorFill.Linear
        // First stop keeps its ~50% alpha (0x80 == 128) after the opacity round trip;
        // the opaque stop survives as a plain 6-digit color (SVG drops the FF alpha).
        assertEquals("#80FF0000", reparsed.stops[0].color.uppercase())
        assertEquals("#0000FF", reparsed.stops[1].color.uppercase())
    }
}
