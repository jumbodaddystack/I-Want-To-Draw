package com.aichat.sandbox.ui.components.notes

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stroke binary format (FIXED at Phase 1.3 — bumping requires Note.schemaVersion + migration):
 * FloatArray [x0,y0,p0,t0, x1,y1,p1,t1, …] flattened to little-endian bytes.
 * Four floats per sample: x, y, pressure, tilt. No per-sample timestamp.
 */
object StrokeCodec {
    const val FLOATS_PER_SAMPLE = 4
    private const val BYTES_PER_FLOAT = 4
    const val BYTES_PER_SAMPLE = FLOATS_PER_SAMPLE * BYTES_PER_FLOAT

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

    fun decode(payload: ByteArray): FloatArray {
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
}
