package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Exercises [VectorSceneParser]: fenced/plain extraction, viewport fallback,
 * per-primitive validation, color/geometry rejection with reasons, numeric
 * clamping, and graceful handling of malformed replies (never throws).
 */
class VectorSceneParserTest {

    private val fallback = VectorViewport(108f, 108f, 108f, 108f)

    private fun parse(raw: String) = VectorSceneParser.parse(raw, fallback)

    @Test
    fun parseFencedVectorScene() {
        val raw = """
            Here is the scene:
            ```vector-scene
            {
              "schema": 1,
              "viewport": { "widthDp": 108, "heightDp": 108,
                "viewportWidth": 108, "viewportHeight": 108 },
              "styleIntent": "clean hand-drawn icon",
              "objects": [
                { "id": "roof", "type": "polygon",
                  "points": [[27, 23], [39, 6], [52, 23]], "closed": true,
                  "stroke": "#2D2D2D", "fill": "#F8F8F8", "strokeWidth": 1.2 }
              ]
            }
            ```
        """.trimIndent()

        val scene = parse(raw).getOrThrow()
        assertEquals(1, scene.schema)
        assertEquals("clean hand-drawn icon", scene.styleIntent)
        assertEquals(108f, scene.viewport.viewportWidth, 0.001f)
        assertEquals(1, scene.objects.size)
        assertTrue(scene.objects.single() is VectorSceneObject.Polygon)
        assertTrue(scene.rejected.isEmpty())
    }

    @Test
    fun parsePlainJsonFallback() {
        val raw = """
            { "viewport": { "viewportWidth": 24, "viewportHeight": 24 },
              "objects": [ { "id": "l", "type": "line",
                "x0": 2, "y0": 2, "x1": 20, "y1": 20, "stroke": "#FF0000" } ] }
        """.trimIndent()

        val scene = parse(raw).getOrThrow()
        assertEquals(24f, scene.viewport.viewportWidth, 0.001f)
        assertEquals(1, scene.objects.size)
        assertTrue(scene.objects.single() is VectorSceneObject.Line)
    }

    @Test
    fun parseMissingViewportUsesFallback() {
        val raw = """
            { "styleIntent": "x", "objects": [ { "id": "l", "type": "line",
              "x0": 1, "y0": 1, "x1": 5, "y1": 5, "stroke": "#000000" } ] }
        """.trimIndent()

        val scene = parse(raw).getOrThrow()
        assertEquals(fallback, scene.viewport)
        assertTrue("missing viewport should be noted", scene.rejected.any { it.raw == "viewport" })
    }

    @Test
    fun parseLineRectEllipsePolygonAndPath() {
        val raw = """
            { "viewport": { "viewportWidth": 108, "viewportHeight": 108 },
              "objects": [
                { "id": "l", "type": "line", "x0": 10, "y0": 10, "x1": 90, "y1": 90,
                  "stroke": "#2D2D2D", "strokeWidth": 1.2 },
                { "id": "r", "type": "rect", "x": 10, "y": 10, "width": 50, "height": 40,
                  "stroke": "#2D2D2D", "fill": "#F8F8F8" },
                { "id": "e", "type": "ellipse", "cx": 54, "cy": 54, "rx": 10, "ry": 8,
                  "stroke": "#D62828", "fill": "#FF9F1C", "strokeWidth": 1 },
                { "id": "p", "type": "polygon", "points": [[27,23],[39,6],[52,23]],
                  "stroke": "#2D2D2D", "fill": "#F8F8F8" },
                { "id": "pa", "type": "path", "pathData": "M10,10 C12,12 14,14 20,20",
                  "stroke": "#109F5C", "fill": "#00000000", "strokeWidth": 1.2 }
              ] }
        """.trimIndent()

        val scene = parse(raw).getOrThrow()
        assertEquals(5, scene.objects.size)
        assertTrue(scene.objects[0] is VectorSceneObject.Line)
        assertTrue(scene.objects[1] is VectorSceneObject.Rect)
        assertTrue(scene.objects[2] is VectorSceneObject.Ellipse)
        assertTrue(scene.objects[3] is VectorSceneObject.Polygon)
        assertTrue(scene.objects[4] is VectorSceneObject.Path)
        assertTrue(scene.rejected.isEmpty())
    }

    @Test
    fun parsePolylineAsOpenPolygon() {
        val raw = """
            { "objects": [ { "id": "pl", "type": "polyline",
              "points": [[2,2],[10,4],[18,2]], "stroke": "#000000" } ] }
        """.trimIndent()

        val scene = parse(raw).getOrThrow()
        val poly = scene.objects.single() as VectorSceneObject.Polygon
        assertFalse("polyline should be an open polygon", poly.closed)
        assertEquals(3, poly.points.size)
    }

    @Test
    fun rejectUnsupportedObjectType() {
        val raw = """
            { "objects": [ { "id": "s", "type": "star", "stroke": "#000000",
              "points": [[1,1],[2,2]] } ] }
        """.trimIndent()

        val scene = parse(raw).getOrThrow()
        assertTrue(scene.objects.isEmpty())
        assertTrue(scene.rejected.any { it.reason.contains("unsupported type") })
    }

    @Test
    fun rejectMalformedColor() {
        val raw = """
            { "objects": [ { "id": "l", "type": "line", "x0": 1, "y0": 1, "x1": 5, "y1": 5,
              "stroke": "blue" } ] }
        """.trimIndent()

        val scene = parse(raw).getOrThrow()
        assertTrue(scene.objects.isEmpty())
        assertTrue(scene.rejected.any { it.reason.contains("malformed color") })
    }

    @Test
    fun rejectObjectWithNoFillOrStroke() {
        val raw = """
            { "objects": [ { "id": "l", "type": "line", "x0": 1, "y0": 1, "x1": 5, "y1": 5 } ] }
        """.trimIndent()

        val scene = parse(raw).getOrThrow()
        assertTrue(scene.objects.isEmpty())
        assertTrue(scene.rejected.any { it.reason.contains("no fill or stroke") })
    }

    @Test
    fun rejectMalformedPathData() {
        val raw = """
            { "objects": [ { "id": "pa", "type": "path", "pathData": "1 2 3 4",
              "stroke": "#000000" } ] }
        """.trimIndent()

        val scene = parse(raw).getOrThrow()
        assertTrue(scene.objects.isEmpty())
        assertTrue(scene.rejected.any { it.reason.contains("malformed pathData") })
    }

    @Test
    fun clampUnsafeStrokeWidthAndAlpha() {
        val raw = """
            { "objects": [ { "id": "l", "type": "line", "x0": 10, "y0": 10, "x1": 90, "y1": 90,
              "stroke": "#2D2D2D", "strokeWidth": 999, "strokeAlpha": 5, "fillAlpha": -1 } ] }
        """.trimIndent()

        val scene = parse(raw).getOrThrow()
        val line = scene.objects.single() as VectorSceneObject.Line
        assertEquals(64f, line.style.strokeWidth!!, 0.001f)
        assertEquals(1f, line.style.strokeAlpha!!, 0.001f)
        assertEquals(0f, line.style.fillAlpha!!, 0.001f)
    }

    @Test
    fun clampOutOfBoundsGeometryToViewport() {
        val raw = """
            { "viewport": { "viewportWidth": 108, "viewportHeight": 108 },
              "objects": [ { "id": "l", "type": "line", "x0": -50, "y0": -50,
                "x1": 200, "y1": 200, "stroke": "#000000" } ] }
        """.trimIndent()

        val scene = parse(raw).getOrThrow()
        val line = scene.objects.single() as VectorSceneObject.Line
        assertEquals(0f, line.x0, 0.001f)
        assertEquals(0f, line.y0, 0.001f)
        assertEquals(108f, line.x1, 0.001f)
        assertEquals(108f, line.y1, 0.001f)
    }

    @Test
    fun malformedReplyReturnsFailureNotThrow() {
        assertTrue(parse("not json at all").isFailure)
        assertTrue(parse("{{{ broken").isFailure)
        assertTrue(parse("").isFailure)

        // Fuzz: random malformations must never throw.
        val rng = Random(4321)
        val alphabet = "{}[]\":,abc#line0123.- \npathpoints"
        repeat(500) {
            val len = rng.nextInt(0, 90)
            val s = buildString { repeat(len) { append(alphabet[rng.nextInt(alphabet.length)]) } }
            VectorSceneParser.parse(s, fallback)
        }
    }
}
