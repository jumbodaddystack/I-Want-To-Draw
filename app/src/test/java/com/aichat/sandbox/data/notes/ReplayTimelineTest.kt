package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase I8 — pure-JVM coverage of the replay frame timeline (a). Ordering,
 * teaching-pace durations driven by the v2 `t` span, partial-stroke prefixes,
 * and frame sampling. No Android / ink classes touched.
 */
class ReplayTimelineTest {

    private val v1 = StrokeCodec.FLOATS_PER_SAMPLE
    private val v2 = StrokeCodec.FLOATS_PER_SAMPLE_V2

    /** v2 stroke whose sample timestamps are [times] (recording-relative ms). */
    private fun v2Stroke(zIndex: Int, times: List<Float>): NoteItem {
        val out = FloatArray(times.size * v2)
        times.forEachIndexed { i, t ->
            out[i * v2] = i.toFloat()       // x marches right
            out[i * v2 + 1] = 0f
            out[i * v2 + 2] = 0.7f
            out[i * v2 + 3] = 0.1f
            out[i * v2 + 4] = t
        }
        return item(zIndex, StrokeCodec.encodeV2(out))
    }

    private fun v1Stroke(zIndex: Int, sampleCount: Int): NoteItem {
        val out = FloatArray(sampleCount * v1)
        for (i in 0 until sampleCount) {
            out[i * v1] = i.toFloat()
            out[i * v1 + 2] = 1f
        }
        return item(zIndex, StrokeCodec.encode(out))
    }

    private fun item(zIndex: Int, payload: ByteArray, kind: String = NoteItem.KIND_STROKE) = NoteItem(
        noteId = "n",
        zIndex = zIndex,
        kind = kind,
        tool = "pen",
        colorArgb = 0xFF000000.toInt(),
        baseWidthPx = 3f,
        payload = payload,
    )

    private fun visibleSampleCount(item: NoteItem): Int =
        StrokeCodec.decode(item.payload).size / v1

    @Test
    fun emptyTimelineIsZeroLength() {
        val t = ReplayTimeline.build(emptyList())
        assertEquals(0L, t.totalDurationMs)
        assertTrue(t.itemsAt(0).isEmpty())
        assertEquals(1, t.framePositions(30).size)
        assertEquals(0L, t.framePositions(30)[0])
    }

    @Test
    fun segmentsAreOrderedByZIndexAndContiguousWithGaps() {
        val a = v2Stroke(zIndex = 0, times = listOf(1000f, 1100f, 1200f))
        val b = v2Stroke(zIndex = 1, times = listOf(2000f, 2300f))
        // Pass out of order; build must sort by zIndex.
        val t = ReplayTimeline.build(listOf(b, a))
        assertEquals(listOf(0, 1), t.segments.map { it.item.zIndex })
        // Monotonic, non-overlapping, with a gap between consecutive segments.
        assertEquals(0L, t.segments[0].startMs)
        assertTrue(t.segments[0].endMs <= t.segments[1].startMs)
        assertEquals(t.totalDurationMs, t.segments.last().endMs)
    }

    @Test
    fun v2DurationTracksRealSpanClampedAndV1UsesDefault() {
        val cfg = ReplayTimeline.Config()
        // A 300 ms real span sits inside [min,max] → used as-is.
        val mid = v2Stroke(0, listOf(0f, 150f, 300f))
        // A 5 ms flick is floored to strokeMinMs.
        val flick = v2Stroke(0, listOf(0f, 5f))
        // A 9 s slow mark is capped to strokeMaxMs.
        val slow = v2Stroke(0, listOf(0f, 9000f))
        assertEquals(300L, ReplayTimeline.build(listOf(mid), cfg).segments[0].durationMs)
        assertEquals(cfg.strokeMinMs, ReplayTimeline.build(listOf(flick), cfg).segments[0].durationMs)
        assertEquals(cfg.strokeMaxMs, ReplayTimeline.build(listOf(slow), cfg).segments[0].durationMs)
        assertEquals(
            cfg.defaultStrokeMs,
            ReplayTimeline.build(listOf(v1Stroke(0, 10)), cfg).segments[0].durationMs,
        )
    }

    @Test
    fun itemsAtHidesFutureRevealsPartialAndCompletes() {
        val s = v2Stroke(0, listOf(0f, 100f, 200f, 300f))
        val t = ReplayTimeline.build(listOf(s))
        val seg = t.segments[0]
        // Before start: nothing.
        assertTrue(t.itemsAt(seg.startMs - 1).isEmpty())
        // Mid-draw: a strict, non-empty prefix.
        val mid = t.itemsAt((seg.startMs + seg.endMs) / 2)
        assertEquals(1, mid.size)
        val midCount = visibleSampleCount(mid[0])
        assertTrue("mid prefix is partial", midCount in 1..3)
        // Fully drawn at/after the segment end.
        assertEquals(4, visibleSampleCount(t.itemsAt(seg.endMs)[0]))
        assertEquals(4, visibleSampleCount(t.itemsAt(t.totalDurationMs)[0]))
    }

    @Test
    fun partialRevealIsMonotonicInPosition() {
        val s = v2Stroke(0, listOf(0f, 50f, 100f, 150f, 200f, 250f))
        val t = ReplayTimeline.build(listOf(s))
        var last = 0
        for (p in 0..t.totalDurationMs step 5) {
            val items = t.itemsAt(p)
            val c = if (items.isEmpty()) 0 else visibleSampleCount(items[0])
            assertTrue("sample count never decreases ($last -> $c at $p)", c >= last)
            last = c
        }
        assertEquals(6, last)
    }

    @Test
    fun pausedStrokeFrontLoadsItsReveal() {
        // Five samples bunched early (t 0..40), then a long pause to a 6th at
        // t=1000. Halfway through the segment we should already see the early
        // cluster but not the post-pause endpoint — the v2 `t` lane paces it.
        val s = v2Stroke(0, listOf(0f, 10f, 20f, 30f, 40f, 1000f))
        val t = ReplayTimeline.build(listOf(s))
        val seg = t.segments[0]
        val half = t.itemsAt((seg.startMs + seg.endMs) / 2)
        val count = visibleSampleCount(half[0])
        assertTrue("early cluster shown, endpoint withheld (got $count)", count in 1..5)
    }

    @Test
    fun nonStrokeItemPopsInFullyAtStart() {
        val shape = item(0, ByteArray(0), kind = NoteItem.KIND_SHAPE)
        val t = ReplayTimeline.build(listOf(shape))
        assertTrue(t.itemsAt(t.segments[0].startMs - 1).isEmpty())
        assertEquals(1, t.itemsAt(t.segments[0].startMs).size)
        assertEquals(1, t.itemsAt(t.totalDurationMs).size)
    }

    @Test
    fun framePositionsSpanZeroToTotalMonotonically() {
        val s = v2Stroke(0, listOf(0f, 500f, 1000f))
        val t = ReplayTimeline.build(listOf(s))
        val frames = t.framePositions(30)
        assertEquals(0L, frames.first())
        assertEquals(t.totalDurationMs, frames.last())
        for (i in 1 until frames.size) assertTrue(frames[i] >= frames[i - 1])
    }

    @Test
    fun speedScalesDurationDown() {
        val s = v2Stroke(0, listOf(0f, 400f))
        val base = ReplayTimeline.build(listOf(s)).totalDurationMs
        val fast = ReplayTimeline.build(listOf(s), ReplayTimeline.Config(speed = 2f)).totalDurationMs
        assertTrue("2x speed is shorter ($fast < $base)", fast < base)
    }
}
