package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase I8 — pure-JVM coverage of the timelapse export framing (c). The plan
 * (frame positions, per-frame clipped item sets, shared viewport) is what runs
 * headless; the per-frame bitmap render + codec is device-only.
 */
class TimelapseFramePlanTest {

    private val v2 = StrokeCodec.FLOATS_PER_SAMPLE_V2

    private fun stroke(zIndex: Int, x0: Float, times: List<Float>): NoteItem {
        val out = FloatArray(times.size * v2)
        times.forEachIndexed { i, t ->
            out[i * v2] = x0 + i
            out[i * v2 + 1] = zIndex.toFloat() * 10f
            out[i * v2 + 2] = 1f
            out[i * v2 + 4] = t
        }
        return NoteItem(
            noteId = "n", zIndex = zIndex, kind = NoteItem.KIND_STROKE, tool = "pen",
            colorArgb = 0xFF000000.toInt(), baseWidthPx = 2f, payload = StrokeCodec.encodeV2(out),
        )
    }

    @Test
    fun emptyTimelineYieldsSingleFrameNoBounds() {
        val plan = TimelapseFramePlan.build(ReplayTimeline.build(emptyList()))
        assertEquals(1, plan.frameCount)
        assertEquals(0L, plan.frames[0].positionMs)
        assertTrue(plan.frames[0].items.isEmpty())
        assertNull(plan.bounds)
    }

    @Test
    fun framesSpanTheTimelineAndShareBounds() {
        val a = stroke(0, 0f, listOf(0f, 300f, 600f))
        val b = stroke(1, 100f, listOf(700f, 1000f))
        val timeline = ReplayTimeline.build(listOf(a, b))
        val plan = TimelapseFramePlan.build(timeline, fps = 30)

        assertEquals(30, plan.fps)
        assertEquals(timeline.totalDurationMs, plan.durationMs)
        assertEquals(0L, plan.frames.first().positionMs)
        assertEquals(timeline.totalDurationMs, plan.frames.last().positionMs)
        // Frame indices are dense and in order.
        plan.frames.forEachIndexed { i, f -> assertEquals(i, f.index) }
        // Shared viewport equals the union bounds of the whole note.
        val expected = NoteRasterizer.computeBounds(listOf(a, b))!!
        assertNotNull(plan.bounds)
        assertTrue(expected.contentEquals(plan.bounds))
    }

    @Test
    fun eachFrameMatchesTheTimelineState() {
        val timeline = ReplayTimeline.build(listOf(stroke(0, 0f, listOf(0f, 250f, 500f, 750f))))
        val plan = TimelapseFramePlan.build(timeline, fps = 24)
        for (f in plan.frames) {
            val expected = timeline.itemsAt(f.positionMs)
            assertEquals(expected.size, f.items.size)
        }
        // The final frame shows the fully-drawn stroke.
        assertEquals(1, plan.frames.last().items.size)
        assertEquals(
            4,
            StrokeCodec.decode(plan.frames.last().items[0].payload).size / StrokeCodec.FLOATS_PER_SAMPLE,
        )
    }

    @Test
    fun holdFramesRepeatTheFinalFrame() {
        val timeline = ReplayTimeline.build(listOf(stroke(0, 0f, listOf(0f, 400f))))
        val plain = TimelapseFramePlan.build(timeline, fps = 30, holdFrames = 0)
        val held = TimelapseFramePlan.build(timeline, fps = 30, holdFrames = 5)
        assertEquals(plain.frameCount + 5, held.frameCount)
        val tail = held.frames.takeLast(6)
        // All tail frames sit at the end position and show the same final items.
        tail.forEach { assertEquals(timeline.totalDurationMs, it.positionMs) }
        // Indices stay unique/dense even across the hold tail.
        held.frames.forEachIndexed { i, f -> assertEquals(i, f.index) }
    }
}
