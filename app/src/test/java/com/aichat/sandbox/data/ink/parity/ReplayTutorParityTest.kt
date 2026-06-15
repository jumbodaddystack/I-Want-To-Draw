package com.aichat.sandbox.data.ink.parity

import androidx.ink.brush.InputToolType
import com.aichat.sandbox.data.ink.InkInterop
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.notes.ReplayTimeline
import com.aichat.sandbox.data.notes.TutorGuide
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase **I8 — replay / draw-with-me (N4, idea #7)** (headless, ink-native slice).
 *
 * Replay ordering and partial-stroke animation are driven by the canonical v2
 * per-sample `t` lane. This test holds that timeline honest **against the real
 * ink engine** (`ink-*-jvm` + `libink.so`): it feeds the same canonical
 * payloads through [InkInterop] into real ink `StrokeInput`s and shows that
 *
 *  1. the replay draw-order agrees with ink's reconstructed recording-relative
 *     first-sample times (the v2 `t` lane that is already synced to audio);
 *  2. the partial-stroke prefix the timeline reveals at a mid-segment playhead
 *     is exactly the prefix of inputs ink would have drawn by that
 *     stroke-relative `elapsedTimeMillis`; and
 *  3. a tutor construction stroke is an ordinary canonical payload — ink builds
 *     a `Stroke` from it with every sample intact (so generated guide geometry
 *     rides the inviolable edit-ops/commit path, never a separate ink format).
 *
 * The felt replay/tutor animation, on-device smoothness, and actual video/GIF
 * encoding stay the device-only column in `docs/INK_I2_PARITY_GATE.md`.
 */
class ReplayTutorParityTest {

    private val v2 = StrokeCodec.FLOATS_PER_SAMPLE_V2

    /** v2 stroke with the given recording-relative sample timestamps. */
    private fun v2Stroke(zIndex: Int, times: List<Float>): NoteItem {
        val out = FloatArray(times.size * v2)
        times.forEachIndexed { i, t ->
            out[i * v2] = i.toFloat()
            out[i * v2 + 1] = 0f
            out[i * v2 + 2] = 0.7f
            out[i * v2 + 3] = 0.1f
            out[i * v2 + 4] = t
        }
        return NoteItem(
            noteId = "n", zIndex = zIndex, kind = NoteItem.KIND_STROKE, tool = "pen",
            colorArgb = 0xFF000000.toInt(), baseWidthPx = 4f, payload = StrokeCodec.encodeV2(out),
        )
    }

    @Test
    fun replayOrderAgreesWithInkRecordingRelativeTimes() {
        val first = v2Stroke(zIndex = 0, times = listOf(1000f, 1100f, 1200f))
        val second = v2Stroke(zIndex = 1, times = listOf(5000f, 5300f))

        // Replay timeline order (built from out-of-order input).
        val timeline = ReplayTimeline.build(listOf(second, first))
        val replayOrder = timeline.segments.map { it.item.zIndex }

        // Ink's view: recording origin restored by InkInterop is each stroke's
        // recording-relative first-sample time.
        val originFirst = InkInterop.toInputBatch(first.payload, InputToolType.STYLUS).recordingOriginMillis!!
        val originSecond = InkInterop.toInputBatch(second.payload, InputToolType.STYLUS).recordingOriginMillis!!

        assertEquals("replay plays z0 then z1", listOf(0, 1), replayOrder)
        assertTrue(
            "ink recording-relative times ascend in the same order ($originFirst < $originSecond)",
            originFirst < originSecond,
        )
    }

    @Test
    fun partialPrefixMatchesInkElapsedTimePrefix() {
        val times = listOf(0f, 40f, 80f, 600f, 640f) // a pause before the 4th sample
        val item = v2Stroke(0, times)
        val timeline = ReplayTimeline.build(listOf(item))
        val seg = timeline.segments[0]

        // Real ink inputs: stroke-relative elapsed times.
        val batch = InkInterop.toInputBatch(item.payload, InputToolType.STYLUS).batch
        val firstT = times.first()
        val realSpan = times.last() - firstT

        // Sweep several playhead fractions; the timeline's revealed prefix must
        // equal the number of ink inputs whose elapsedTimeMillis ≤ that fraction.
        for (f in listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.95f)) {
            val pos = seg.startMs + (f * (seg.endMs - seg.startMs)).toLong()
            val revealed = timeline.itemsAt(pos)
            val timelineCount = if (revealed.isEmpty()) 0
            else StrokeCodec.decode(revealed[0].payload).size / StrokeCodec.FLOATS_PER_SAMPLE

            val cutoff = (f * realSpan)
            var inkCount = 0
            for (i in 0 until batch.size) {
                if (batch.get(i).elapsedTimeMillis <= cutoff + 1e-3f) inkCount = i + 1 else break
            }
            assertEquals(
                "prefix at f=$f matches ink elapsed-time prefix",
                inkCount.coerceAtLeast(1),
                timelineCount,
            )
        }
    }

    @Test
    fun tutorStrokeIsCanonicalForInk() {
        // A model-authored construction stroke, reparented onto the guide layer.
        val raw = v2Stroke(0, listOf(0f, 100f, 200f, 300f))
        val reparented = TutorGuide.assignToGuide(listOf(raw), "guide").single()
        // Reparenting changes only the layer — the canonical payload is intact.
        assertTrue(raw.payload.contentEquals(reparented.payload))

        // Ink builds a real Stroke from the canonical payload with every sample.
        val brush = InkInterop.brushForTool("pen", reparented.colorArgb, reparented.baseWidthPx)
        val stroke = InkInterop.toStroke(reparented.payload, brush, InputToolType.STYLUS)
        assertEquals(4, stroke.inputs.size)

        // And it round-trips back to canonical lanes the AI edit-ops pipeline reads.
        val roundTrip = InkInterop.fromStroke(stroke, recordingOriginMillis = 0L)
        assertTrue("tutor payload round-trips through ink as v2", StrokeCodec.isV2(roundTrip))
    }
}
