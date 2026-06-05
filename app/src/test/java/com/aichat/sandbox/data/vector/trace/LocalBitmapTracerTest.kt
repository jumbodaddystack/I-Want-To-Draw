package com.aichat.sandbox.data.vector.trace

import com.aichat.sandbox.data.vector.PathCommand
import com.aichat.sandbox.data.vector.VectorWarning
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 5 (sub-feature 5a) — the deterministic local tracer (pure JVM). */
class LocalBitmapTracerTest {

    private val tracer = LocalBitmapTracer()
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    /** Min/max of all MoveTo/LineTo/CubicTo endpoints across a path's commands. */
    private fun bbox(cmds: List<PathCommand>): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        fun acc(x: Float, y: Float) {
            minX = minOf(minX, x); minY = minOf(minY, y)
            maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
        }
        for (c in cmds) when (c) {
            is PathCommand.MoveTo -> acc(c.x, c.y)
            is PathCommand.LineTo -> acc(c.x, c.y)
            is PathCommand.CubicTo -> acc(c.x, c.y)
            else -> {}
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    @Test
    fun trace_solidSquare_yieldsSingleClosedPathWithin1pxOfCorners() = runBlocking {
        val w = 16; val h = 16
        val px = IntArray(w * h) { white }
        for (y in 3..12) for (x in 3..12) px[y * w + x] = black
        val result = tracer.trace(px, w, h, TraceOptions(mode = TraceMode.OUTLINE))

        assertEquals(1, result.paths.size)
        val cmds = result.paths.single().commands!!
        assertTrue("outline should be closed", cmds.last() is PathCommand.Close)
        val box = bbox(cmds)
        assertEquals(3f, box[0], 1.5f)  // minX
        assertEquals(3f, box[1], 1.5f)  // minY
        assertEquals(12f, box[2], 1.5f) // maxX
        assertEquals(12f, box[3], 1.5f) // maxY
    }

    @Test
    fun trace_centerlineOfThinLine_yieldsOpenPath() = runBlocking {
        val w = 20; val h = 12
        val px = IntArray(w * h) { white }
        // A 1-px-tall horizontal line.
        for (x in 2..17) px[6 * w + x] = black
        val result = tracer.trace(px, w, h, TraceOptions(mode = TraceMode.CENTERLINE))

        assertTrue(result.paths.isNotEmpty())
        val cmds = result.paths.first().commands!!
        assertTrue("centerline should be open", cmds.none { it is PathCommand.Close })
        val box = bbox(cmds)
        // Spans most of the line horizontally near y = 6.
        assertTrue("should span the line", box[2] - box[0] >= 12f)
        assertEquals(6f, box[1], 1.5f)
    }

    @Test
    fun trace_emptyBitmap_yieldsNoPathsWithWarning() = runBlocking {
        val w = 8; val h = 8
        val px = IntArray(w * h) { white }
        val result = tracer.trace(px, w, h, TraceOptions())
        assertTrue(result.paths.isEmpty())
        assertEquals(VectorWarning.Codes.TRACE_EMPTY, result.warnings.single().code)
    }
}
