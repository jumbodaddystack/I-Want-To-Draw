package com.aichat.sandbox.data.ink

import androidx.ink.brush.InputToolType
import com.aichat.sandbox.data.model.BrushPreset
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Phase I0 — headless JVM round-trip tests for the [InkInterop] seam.
 *
 * These run on the host JVM against ink's `-jvm` artifacts (which bundle the
 * `linux-x86_64/libink.so` native core), so no emulator or Android framework
 * stubs are needed.
 */
class InkInteropTest {

    private val tol = 1e-3f

    // ---- helpers ----------------------------------------------------------

    /** Build a v1 sample array [x,y,pressure,tilt]*. */
    private fun v1Samples(n: Int, seed: Int): FloatArray {
        val r = Random(seed)
        val out = FloatArray(n * StrokeCodec.FLOATS_PER_SAMPLE)
        var b = 0
        repeat(n) {
            out[b] = r.nextFloat() * 2000f - 1000f       // x in world units
            out[b + 1] = r.nextFloat() * 2000f - 1000f   // y
            out[b + 2] = r.nextFloat()                   // pressure 0..1
            out[b + 3] = r.nextFloat() * (Math.PI.toFloat() / 2f) // tilt 0..π/2
            b += StrokeCodec.FLOATS_PER_SAMPLE
        }
        return out
    }

    /**
     * Build a v2 sample array [x,y,pressure,tilt,t]* with strictly increasing,
     * integral, recording-relative timestamps starting at [originMs].
     */
    private fun v2Samples(n: Int, seed: Int, originMs: Int): FloatArray {
        val r = Random(seed)
        val out = FloatArray(n * StrokeCodec.FLOATS_PER_SAMPLE_V2)
        var b = 0
        var t = originMs
        repeat(n) {
            out[b] = r.nextFloat() * 2000f - 1000f
            out[b + 1] = r.nextFloat() * 2000f - 1000f
            out[b + 2] = r.nextFloat()
            out[b + 3] = r.nextFloat() * (Math.PI.toFloat() / 2f)
            out[b + 4] = t.toFloat()
            t += 5 + r.nextInt(10) // monotonic, integral ms
            b += StrokeCodec.FLOATS_PER_SAMPLE_V2
        }
        return out
    }

    // ---- v1: position / pressure / tilt round-trip ------------------------

    @Test
    fun v1PositionPressureTiltRoundTrip() {
        val samples = v1Samples(n = 64, seed = 1)
        val payload = StrokeCodec.encode(samples)

        val conv = InkInterop.toInputBatch(payload)
        // v1 carries no recording origin.
        assertNull(conv.recordingOriginMillis)
        assertEquals(64, conv.batch.size)
        assertTrue(conv.batch.hasPressure())
        assertTrue(conv.batch.hasTilt())

        val restored = InkInterop.fromInputBatch(conv.batch, conv.recordingOriginMillis)
        // v1 in -> v1 out (no version byte, plain float buffer).
        assertTrue(!StrokeCodec.isV2(restored))
        val out = StrokeCodec.decode(restored)
        assertEquals(samples.size, out.size)
        for (i in samples.indices) {
            assertEquals("sample $i", samples[i], out[i], tol)
        }
    }

    // ---- v2: two-clock timestamp round-trip -------------------------------

    @Test
    fun v2TwoClockTimestampRoundTrip() {
        val originMs = 1000
        val samples = v2Samples(n = 48, seed = 7, originMs = originMs)
        val payload = StrokeCodec.encodeV2(samples)

        val conv = InkInterop.toInputBatch(payload)
        // Recording origin is the first sample's recording-relative time.
        assertEquals(originMs.toLong(), conv.recordingOriginMillis)
        // Inside ink, the first input must be stroke-relative t = 0.
        assertEquals(0L, conv.batch.get(0).elapsedTimeMillis)
        // ... and the last input is later than the first.
        assertTrue(conv.batch.get(47).elapsedTimeMillis > 0L)

        val restored = InkInterop.fromInputBatch(conv.batch, conv.recordingOriginMillis)
        assertTrue(StrokeCodec.isV2(restored))
        val out = StrokeCodec.decodeWithT(restored)
        assertEquals(samples.size, out.size)
        var b = 0
        var i = 0
        while (i < 48) {
            assertEquals("x $i", samples[b], out[b], tol)
            assertEquals("y $i", samples[b + 1], out[b + 1], tol)
            assertEquals("pressure $i", samples[b + 2], out[b + 2], tol)
            assertEquals("tilt $i", samples[b + 3], out[b + 3], tol)
            // Recording-relative timestamps restored exactly (integral ms).
            assertEquals("t $i", samples[b + 4], out[b + 4], 0f)
            b += StrokeCodec.FLOATS_PER_SAMPLE_V2
            i++
        }
    }

    @Test
    fun v1FedToInkGetsSyntheticUniformCadence() {
        val payload = StrokeCodec.encode(v1Samples(n = 5, seed = 3))
        val conv = InkInterop.toInputBatch(payload)
        // Synthesized 0, C, 2C, 3C, 4C cadence; never persisted.
        for (i in 0 until 5) {
            assertEquals(
                i * InkInterop.SYNTHETIC_CADENCE_MS,
                conv.batch.get(i).elapsedTimeMillis,
            )
        }
    }

    @Test
    fun emptyStrokeRoundTrips() {
        val v1 = InkInterop.toInputBatch(StrokeCodec.encode(FloatArray(0)))
        assertEquals(0, v1.batch.size)
        assertNull(v1.recordingOriginMillis)
        assertEquals(0, StrokeCodec.decode(InkInterop.fromInputBatch(v1.batch, null)).size)

        val v2 = InkInterop.toInputBatch(StrokeCodec.encodeV2(FloatArray(0)))
        assertEquals(0, v2.batch.size)
        assertNotNull(v2.recordingOriginMillis)
    }

    @Test
    fun pressureAndTiltClampedIntoInkValidRange() {
        // Out-of-range pressure (>1) and tilt (>π/2) must be clamped so ink
        // accepts the input rather than throwing.
        val samples = floatArrayOf(
            0f, 0f, 5f, 100f,   // wildly out of range
            10f, 10f, -3f, -1f, // negatives
        )
        val conv = InkInterop.toInputBatch(StrokeCodec.encode(samples))
        assertEquals(2, conv.batch.size)
        val p0 = conv.batch.get(0).pressure
        val t0 = conv.batch.get(0).tiltRadians
        assertTrue("pressure clamped", p0 in 0f..1f)
        assertTrue("tilt clamped", t0 in 0f..(Math.PI.toFloat() / 2f))
    }

    // ---- Stroke (mesh) round-trip -----------------------------------------

    @Test
    fun toStrokeThenFromStrokeRoundTrips() {
        val originMs = 250
        val samples = v2Samples(n = 32, seed = 11, originMs = originMs)
        val payload = StrokeCodec.encodeV2(samples)

        val preset = penPreset()
        val brush = InkInterop.toBrush(preset)
        val stroke = InkInterop.toStroke(payload, brush, InputToolType.STYLUS)
        assertEquals(32, stroke.inputs.size)
        // Native mesh geometry was produced.
        assertNotNull(stroke.shape)

        val restored = InkInterop.fromStroke(stroke, originMs.toLong())
        assertTrue(StrokeCodec.isV2(restored))
        val out = StrokeCodec.decodeWithT(restored)
        var b = 0
        while (b < samples.size) {
            assertEquals(samples[b], out[b], tol)         // x
            assertEquals(samples[b + 1], out[b + 1], tol) // y
            assertEquals(samples[b + 4], out[b + 4], 0f)  // t restored exactly
            b += StrokeCodec.FLOATS_PER_SAMPLE_V2
        }
    }

    // ---- BrushPreset -> Brush adapter -------------------------------------

    @Test
    fun toBrushFoldsOpacityIntoAlpha() {
        val preset = penPreset().copy(colorArgb = 0xFF112233.toInt(), opacity = 0.5f, baseWidthPx = 6f)
        val brush = InkInterop.toBrush(preset)
        // 0xFF alpha * 0.5 -> 0x80 (128), RGB preserved.
        assertEquals(0x80112233.toInt(), brush.colorIntArgb)
        assertEquals(6f, brush.size, 0f)
    }

    @Test
    fun toBrushMapsToolsToStockFamilies() {
        // Each known tool yields a valid brush (distinct stock families) without
        // throwing; size is carried through.
        for (tool in listOf("pen", "highlighter", "marker", "pencil", "unknown-tool")) {
            val brush = InkInterop.toBrush(penPreset().copy(tool = tool, baseWidthPx = 3f))
            assertEquals(3f, brush.size, 0f)
            assertNotNull(brush.family)
        }
    }

    @Test
    fun applyOpacityToArgbClampsAndPreservesRgb() {
        assertEquals(0x00AABBCC, InkInterop.applyOpacityToArgb(0xFFAABBCC.toInt(), 0f))
        assertEquals(0xFFAABBCC.toInt(), InkInterop.applyOpacityToArgb(0xFFAABBCC.toInt(), 1f))
        // Opacity above 1 clamps to full alpha.
        assertEquals(0xFFAABBCC.toInt(), InkInterop.applyOpacityToArgb(0xFFAABBCC.toInt(), 2f))
    }

    private fun penPreset() = BrushPreset(
        ownerScope = BrushPreset.SCOPE_APP,
        name = "Test Pen",
        tool = "pen",
        colorArgb = 0xFF000000.toInt(),
        baseWidthPx = 4f,
        opacity = 1f,
        taperStart = 0f,
        taperEnd = 0f,
        jitter = 0f,
        pressureCurveId = BrushPreset.CURVE_LINEAR,
        textureId = BrushPreset.TEXTURE_SMOOTH,
        ordinal = 0,
    )
}
