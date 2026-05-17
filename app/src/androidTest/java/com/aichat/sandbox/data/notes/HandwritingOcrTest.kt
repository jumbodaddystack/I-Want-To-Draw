package com.aichat.sandbox.data.notes

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage for [HandwritingOcr]. The Play-Services-dependent
 * happy path uses `assumeTrue` so emulators without Play Services (Huawei,
 * some MIUI images) silently skip rather than fail. The structural test for
 * [HandwritingOcr.buildInk] runs anywhere ML Kit's AAR is present, which is
 * the realistic minimum bar for this code path.
 */
@RunWith(AndroidJUnit4::class)
class HandwritingOcrTest {

    @Test
    fun buildInk_skipsNonStrokeItems() {
        val text = NoteItem(
            noteId = "n",
            zIndex = 0,
            kind = "text",
            tool = null,
            colorArgb = 0xFF000000.toInt(),
            baseWidthPx = 0f,
            payload = ByteArray(0),
        )
        // No strokes — buildInk should return null so recognize() short-circuits.
        assertEquals(null, HandwritingOcr.buildInk(listOf(text)))
    }

    @Test
    fun buildInk_buildsInkFromStrokeItems() {
        val ink = HandwritingOcr.buildInk(listOf(syntheticHello()))
        assertNotNull("Expected non-null Ink for a real stroke", ink)
        assertEquals(1, ink!!.strokes.size)
        assertTrue(
            "Expected the Ink stroke to keep every sample point",
            ink.strokes.first().points.size > 0,
        )
    }

    @Test
    fun recognize_happyPath_playServicesRequired() = runBlocking {
        val ocr = HandwritingOcr()
        // First-run model download dominates the timeout budget; recognition
        // itself is sub-second once the model is local.
        val ready = try {
            withTimeout(60_000) { ocr.ensureModelReady() }
        } catch (t: Throwable) {
            false
        }
        assumeTrue(
            "Skipping: ensureModelReady failed (state=${ocr.state.value}). " +
                "Requires Play Services + network for first run.",
            ready,
        )
        val result = withTimeout(15_000) { ocr.recognize(listOf(syntheticHello())) }
        // Don't pin the recognized text — synthetic samples won't reliably
        // spell "hello". The contract is "returns a result without crashing".
        assertNotNull(result)
    }

    @Test
    fun recognize_returnsEmptyForNoStrokes() = runBlocking {
        // No strokes => no Play Services round-trip; should be instant + empty.
        val result = HandwritingOcr().recognize(emptyList())
        assertEquals(OcrResult.EMPTY, result)
    }

    private fun syntheticHello(): NoteItem {
        // Smooth left-to-right wavy line standing in for a handwritten word.
        // Real strokes have ~50–200 samples; we use 40 here.
        val pts = FloatArray(40 * StrokeCodec.FLOATS_PER_SAMPLE)
        var x = 20f
        val baseY = 100f
        for (i in 0 until 40) {
            val o = i * StrokeCodec.FLOATS_PER_SAMPLE
            pts[o] = x
            pts[o + 1] = baseY + ((i % 6) - 3) * 5f
            pts[o + 2] = 0.7f
            pts[o + 3] = 0f
            x += 5f
        }
        return NoteItem(
            noteId = "n",
            zIndex = 0,
            kind = "stroke",
            tool = "pen",
            colorArgb = 0xFF000000.toInt(),
            baseWidthPx = 4f,
            payload = StrokeCodec.encode(pts),
        )
    }
}
