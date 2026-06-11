package com.aichat.sandbox.ui.components.notes

import java.nio.ByteBuffer

/**
 * Sub-phase 13.2 — shared gradient-fill wire format.
 *
 * A trailing optional block appended after the last existing optional field
 * of [ShapeCodec] (after `strokeStyle`), [PathCodec] (after `capJoin`) and
 * [StickyCodec] (after `body`):
 *
 * ```
 * [fillType:u8]              0 = solid/none (block ends here)
 *                            1 = linear, 2 = radial
 * [x0:f][y0:f][x1:f][y1:f]   geometry, normalized to the item's bounds
 *                            (linear: start → end; radial: x0,y0 = centre,
 *                            x1 = radius, y1 unused)
 * [stopCount:u8]
 * per stop: [offset:f][argb:i32]
 * ```
 *
 * Geometry is bounds-normalized (SVG `objectBoundingBox` semantics) so
 * gradients survive every affine transform without re-encoding, and the SVG
 * export maps 1:1. An absent block — or `fillType` 0 — decodes as "no
 * gradient", so every pre-Phase-13 payload round-trips and old builds
 * silently ignore the trailing bytes. The block is self-delimiting
 * (`stopCount`), so future trailing fields can still append after it.
 *
 * Pure JVM — shader construction lives in `GradientShaderFactory`.
 */
object FillStyle {

    const val TYPE_LINEAR: Int = 1
    const val TYPE_RADIAL: Int = 2

    data class Stop(val offset: Float, val argb: Int)

    data class Gradient(
        val type: Int,
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val stops: List<Stop>,
    ) {
        /** Legacy-fill fallback colour for old builds / non-gradient exporters. */
        val firstStopArgb: Int get() = stops.firstOrNull()?.argb ?: 0
    }

    /** Encoded byte size of the block (the lone fillType byte when null). */
    fun byteSize(gradient: Gradient?): Int =
        if (gradient == null) 1 else 1 + 4 * 4 + 1 + gradient.stops.size * (4 + 4)

    /** Append the block to [buf] — always writes at least the fillType byte. */
    fun encode(buf: ByteBuffer, gradient: Gradient?) {
        if (gradient == null) {
            buf.put(0)
            return
        }
        buf.put(gradient.type.toByte())
        buf.putFloat(gradient.x0)
        buf.putFloat(gradient.y0)
        buf.putFloat(gradient.x1)
        buf.putFloat(gradient.y1)
        buf.put(gradient.stops.size.toByte())
        for (stop in gradient.stops) {
            buf.putFloat(stop.offset)
            buf.putInt(stop.argb)
        }
    }

    /** Read the block from [buf]; absent or fillType 0 → null. */
    fun decode(buf: ByteBuffer): Gradient? {
        if (!buf.hasRemaining()) return null
        val type = buf.get().toInt() and 0xFF
        if (type == 0) return null
        val x0 = buf.float
        val y0 = buf.float
        val x1 = buf.float
        val y1 = buf.float
        val count = buf.get().toInt() and 0xFF
        val stops = ArrayList<Stop>(count)
        repeat(count) { stops += Stop(buf.float, buf.int) }
        return Gradient(type, x0, y0, x1, y1, stops)
    }

    /** A diagonal linear gradient between two colours — the preset builder. */
    fun linear(startArgb: Int, endArgb: Int): Gradient = Gradient(
        type = TYPE_LINEAR,
        x0 = 0f, y0 = 0f, x1 = 1f, y1 = 1f,
        stops = listOf(Stop(0f, startArgb), Stop(1f, endArgb)),
    )

    /** A centred radial gradient between two colours — the preset builder. */
    fun radial(centerArgb: Int, edgeArgb: Int): Gradient = Gradient(
        type = TYPE_RADIAL,
        x0 = 0.5f, y0 = 0.5f, x1 = 0.7f, y1 = 0f,
        stops = listOf(Stop(0f, centerArgb), Stop(1f, edgeArgb)),
    )
}
