package com.aichat.sandbox.data.vector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [VectorSceneCompiler]: each primitive compiles into a valid path,
 * the compiled XML re-parses through [AndroidVectorDrawableParser], the viewport
 * and style survive, and empty/invalid objects degrade gracefully into a safe
 * vector plus warnings.
 */
class VectorSceneCompilerTest {

    private val vp = VectorViewport(108f, 108f, 108f, 108f)

    private fun style(
        stroke: String? = "#2D2D2D",
        fill: String? = null,
        width: Float? = 2f,
    ) = VectorSceneStyle(strokeColor = stroke, fillColor = fill, strokeWidth = width)

    private fun scene(
        vararg objects: VectorSceneObject,
        viewport: VectorViewport = vp,
    ) = VectorScene(
        schema = 1,
        viewport = viewport,
        styleIntent = "test",
        objects = objects.toList(),
    )

    private fun onlyPathCommands(result: VectorSceneCompileResult): List<PathCommand> {
        val reparsed = AndroidVectorDrawableParser.parse(result.xml)
        return reparsed.allPaths().single().commands.orEmpty()
    }

    @Test
    fun compileEmptySceneProducesSafeVectorWithWarning() {
        val result = VectorSceneCompiler.compile(scene())
        assertEquals(0, result.metrics.pathCount)
        assertTrue(result.warnings.any { it.code == VectorWarning.Codes.SCENE_EMPTY })
        // Safe vector: re-parses with no structural error.
        val reparsed = AndroidVectorDrawableParser.parse(result.xml)
        assertFalse(reparsed.warnings.any { it.code == VectorWarning.Codes.MALFORMED_XML })
    }

    @Test
    fun compileLineToPath() {
        val result = VectorSceneCompiler.compile(
            scene(VectorSceneObject.Line("l", 10f, 10f, 90f, 90f, style())),
        )
        assertEquals(1, result.metrics.pathCount)
        val commands = onlyPathCommands(result)
        assertTrue(commands.first() is PathCommand.MoveTo)
        assertTrue(commands.any { it is PathCommand.LineTo })
    }

    @Test
    fun compileRectToPath() {
        val result = VectorSceneCompiler.compile(
            scene(VectorSceneObject.Rect("r", 10f, 10f, 50f, 40f, 0f, style(fill = "#F8F8F8"))),
        )
        val commands = onlyPathCommands(result)
        assertEquals(3, commands.count { it is PathCommand.LineTo })
        assertTrue(commands.any { it is PathCommand.Close })
    }

    @Test
    fun compileRoundedRectToPath() {
        val result = VectorSceneCompiler.compile(
            scene(VectorSceneObject.Rect("r", 10f, 10f, 50f, 40f, 8f, style(fill = "#F8F8F8"))),
        )
        assertTrue(result.xml.contains("A")) // rounded corners use arc commands
        val commands = onlyPathCommands(result)
        assertTrue(commands.any { it is PathCommand.ArcTo })
        assertTrue(commands.any { it is PathCommand.Close })
    }

    @Test
    fun compileEllipseToPath() {
        val result = VectorSceneCompiler.compile(
            scene(VectorSceneObject.Ellipse("e", 54f, 54f, 12f, 10f, 0f, style(fill = "#FF9F1C"))),
        )
        val commands = onlyPathCommands(result)
        assertEquals(2, commands.count { it is PathCommand.ArcTo })
    }

    @Test
    fun compilePolygonToPath() {
        val pts = listOf(VectorPoint(27f, 23f), VectorPoint(39f, 6f), VectorPoint(52f, 23f))
        val result = VectorSceneCompiler.compile(
            scene(VectorSceneObject.Polygon("p", pts, closed = true, style(fill = "#F8F8F8"))),
        )
        val commands = onlyPathCommands(result)
        assertTrue(commands.first() is PathCommand.MoveTo)
        assertEquals(2, commands.count { it is PathCommand.LineTo })
        assertTrue(commands.any { it is PathCommand.Close })
    }

    @Test
    fun compilePolylineToOpenPath() {
        val pts = listOf(VectorPoint(2f, 2f), VectorPoint(10f, 4f), VectorPoint(18f, 2f))
        val result = VectorSceneCompiler.compile(
            scene(VectorSceneObject.Polygon("pl", pts, closed = false, style())),
        )
        val commands = onlyPathCommands(result)
        assertFalse("open polyline must not close", commands.any { it is PathCommand.Close })
    }

    @Test
    fun compilePathObjectPreservesPathData() {
        val data = "M10,10 C12,12 14,14 20,20"
        val result = VectorSceneCompiler.compile(
            scene(VectorSceneObject.Path("pa", data, style(fill = "#00000000"))),
        )
        assertTrue("verbatim path data should survive", result.xml.contains(data))
    }

    @Test
    fun compiledXmlParsesAgain() {
        val result = VectorSceneCompiler.compile(
            scene(
                VectorSceneObject.Line("l", 10f, 10f, 90f, 90f, style()),
                VectorSceneObject.Rect("r", 10f, 10f, 50f, 40f, 6f, style(fill = "#F8F8F8")),
                VectorSceneObject.Ellipse("e", 54f, 54f, 12f, 10f, 0f, style(fill = "#FF9F1C")),
            ),
        )
        val reparsed = AndroidVectorDrawableParser.parse(result.xml)
        assertFalse(reparsed.warnings.any { it.code == VectorWarning.Codes.MALFORMED_XML })
        assertEquals(3, reparsed.allPaths().size)
    }

    @Test
    fun compilerPreservesViewport() {
        val viewport = VectorViewport(64f, 48f, 64f, 48f)
        val result = VectorSceneCompiler.compile(
            scene(
                VectorSceneObject.Line("l", 5f, 5f, 30f, 30f, style()),
                viewport = viewport,
            ),
        )
        assertEquals(viewport, result.document.viewport)
        assertTrue(result.xml.contains("android:viewportWidth=\"64\""))
        assertTrue(result.xml.contains("android:viewportHeight=\"48\""))
    }

    @Test
    fun compilerAppliesStyle() {
        val s = VectorSceneStyle(
            strokeColor = "#109F5C",
            fillColor = "#FF9F1C",
            strokeWidth = 1.2f,
            strokeLineCap = "round",
            strokeLineJoin = "round",
        )
        val result = VectorSceneCompiler.compile(
            scene(VectorSceneObject.Ellipse("e", 54f, 54f, 8f, 8f, 0f, s)),
        )
        val path = result.document.allPaths().single()
        assertEquals("#109F5C", path.style.strokeColor)
        assertEquals("#FF9F1C", path.style.fillColor)
        assertEquals(1.2f, path.style.strokeWidth!!, 0.001f)
        assertEquals("round", path.style.strokeLineCap)
    }

    @Test
    fun compilerSkipsRejectedOrInvalidObjects() {
        // A path object that cannot be compiled (no commands) is skipped with a
        // warning; the valid line still compiles.
        val result = VectorSceneCompiler.compile(
            scene(
                VectorSceneObject.Line("l", 10f, 10f, 90f, 90f, style()),
                VectorSceneObject.Path("bad", "1 2 3 4", style()),
            ),
        )
        assertEquals(1, result.metrics.pathCount)
        assertTrue(result.warnings.any { it.code == VectorWarning.Codes.SCENE_MALFORMED_PATH })
    }
}
