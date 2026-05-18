package com.aichat.sandbox.ui.components.notes

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stroke binary format.
 *
 *  - **v1** (FIXED at Phase 1.3): `FloatArray [x0,y0,p0,tilt0, x1,y1,p1,tilt1, …]`
 *    little-endian. Four floats per sample, no timestamp.
 *  - **v2** (Phase 9.4): `FloatArray [x0,y0,p0,tilt0,t0, …]` — same layout
 *    with a fifth float carrying the per-sample timestamp in ms relative
 *    to the owning audio recording's `recordingStartedAt`. Encoded as a
 *    one-byte version header (`0x02`) followed by the float buffer.
 *
 * Reads branch on the leading byte: if it's `< 0x10` we treat it as a
 * version tag (only `0x02` is defined today); otherwise we fall through
 * to the v1 path so already-persisted strokes round-trip unchanged. The
 * v1 path's "no leading version byte" rule survives forever because the
 * first float of any real v1 stroke is an x-coordinate whose
 * little-endian byte-0 is rarely `< 0x10` outside the (-32768, 32768)
 * world-coordinate range we care about... but we use the safer
 * approach of branching on size alignment: if `size % 4 == 0` *and* the
 * size matches v1's 16-byte multiple it's v1; only v2 has the version
 * byte so its size is `1 + N * 20`.
 *
 * v1 reads with a synthesized `t = 0` by [decodeWithT] so the replayer
 * (sub-phase 9.4) can use one code path for both formats — strokes
 * without real timestamps appear as a single "all-at-once" splash at
 * `t = 0`, which is fine: v1 strokes weren't recorded with audio.
 */
object StrokeCodec {
    /** v1 floats per sample. */
    const val FLOATS_PER_SAMPLE = 4
    /** v2 floats per sample. */
    const val FLOATS_PER_SAMPLE_V2 = 5
    private const val BYTES_PER_FLOAT = 4
    const val BYTES_PER_SAMPLE = FLOATS_PER_SAMPLE * BYTES_PER_FLOAT
    const val BYTES_PER_SAMPLE_V2 = FLOATS_PER_SAMPLE_V2 * BYTES_PER_FLOAT

    /** v2 version byte. */
    const val VERSION_V2: Byte = 0x02

    fun encode(samples: FloatArray): ByteArray {
        require(samples.size % FLOATS_PER_SAMPLE == 0) {
            "samples length must be a multiple of $FLOATS_PER_SAMPLE (got ${samples.size})"
        }
        val buffer = ByteBuffer
            .allocate(samples.size * BYTES_PER_FLOAT)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(samples)
        return buffer.array()
    }

    /**
     * Phase 9.4 — v2 encode. Layout: `[0x02][x,y,p,tilt,t][x,y,p,tilt,t]…`.
     * Caller is responsible for the per-sample `t` (ms since the owning
     * audio recording's `recordingStartedAt`).
     */
    fun encodeV2(samples: FloatArray): ByteArray {
        require(samples.size % FLOATS_PER_SAMPLE_V2 == 0) {
            "v2 samples length must be a multiple of $FLOATS_PER_SAMPLE_V2 (got ${samples.size})"
        }
        val payload = ByteArray(1 + samples.size * BYTES_PER_FLOAT)
        payload[0] = VERSION_V2
        val buffer = ByteBuffer.wrap(payload, 1, payload.size - 1)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(samples)
        return payload
    }

    fun decode(payload: ByteArray): FloatArray {
        if (isV2(payload)) {
            // Drop the `t` lane and return the v1-shaped array so legacy
            // callers (rendering, hit-test) keep working without changes.
            val v2 = decodeV2(payload)
            val v1 = FloatArray(v2.size / FLOATS_PER_SAMPLE_V2 * FLOATS_PER_SAMPLE)
            var src = 0
            var dst = 0
            while (src < v2.size) {
                v1[dst] = v2[src]
                v1[dst + 1] = v2[src + 1]
                v1[dst + 2] = v2[src + 2]
                v1[dst + 3] = v2[src + 3]
                src += FLOATS_PER_SAMPLE_V2
                dst += FLOATS_PER_SAMPLE
            }
            return v1
        }
        require(payload.size % BYTES_PER_SAMPLE == 0) {
            "payload size ${payload.size} not aligned to $BYTES_PER_SAMPLE-byte samples"
        }
        val out = FloatArray(payload.size / BYTES_PER_FLOAT)
        ByteBuffer.wrap(payload)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .get(out)
        return out
    }

    /**
     * Decode + augment with timestamps. v1 payloads synthesize `t = 0`
     * for every sample (they weren't recorded with audio). v2 payloads
     * surface their stored timestamps as-is.
     */
    fun decodeWithT(payload: ByteArray): FloatArray {
        if (isV2(payload)) return decodeV2(payload)
        // v1 → v2 shape with t = 0.
        require(payload.size % BYTES_PER_SAMPLE == 0) {
            "payload size ${payload.size} not aligned to $BYTES_PER_SAMPLE-byte samples"
        }
        val v1 = FloatArray(payload.size / BYTES_PER_FLOAT)
        ByteBuffer.wrap(payload)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .get(v1)
        val sampleCount = v1.size / FLOATS_PER_SAMPLE
        val out = FloatArray(sampleCount * FLOATS_PER_SAMPLE_V2)
        var src = 0
        var dst = 0
        while (src < v1.size) {
            out[dst] = v1[src]
            out[dst + 1] = v1[src + 1]
            out[dst + 2] = v1[src + 2]
            out[dst + 3] = v1[src + 3]
            out[dst + 4] = 0f
            src += FLOATS_PER_SAMPLE
            dst += FLOATS_PER_SAMPLE_V2
        }
        return out
    }

    fun isV2(payload: ByteArray): Boolean =
        payload.isNotEmpty() && payload[0] == VERSION_V2 &&
            (payload.size - 1) % BYTES_PER_SAMPLE_V2 == 0

    private fun decodeV2(payload: ByteArray): FloatArray {
        require(isV2(payload)) { "decodeV2 called on non-v2 payload" }
        val floatBytes = payload.size - 1
        val out = FloatArray(floatBytes / BYTES_PER_FLOAT)
        ByteBuffer.wrap(payload, 1, floatBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .get(out)
        return out
    }
}
