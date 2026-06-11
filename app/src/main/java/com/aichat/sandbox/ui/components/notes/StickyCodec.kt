package com.aichat.sandbox.ui.components.notes

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sub-phase 11.1 — sticky-note binary wire format.
 *
 * A sticky is an axis-aligned rounded rect with a preset fill and a UTF-8
 * body the renderer lays out with an auto-shrinking font. One `"sticky"`
 * kind (not a `group(rect, text)` composite) so inline editing, auto-fit
 * font, and single-item semantics in `VectorCanvasJson` stay intact —
 * locked in the master plan's cross-cutting decisions.
 *
 * Layout (little-endian):
 * ```
 * [version:u8=1]
 * [minX:f] [minY:f] [maxX:f] [maxY:f]
 * [fillArgb:i32]
 * [fontSize:f]
 * [bodyLen:i32] [body:utf8]
 * [gradient]?                13.2: optional [FillStyle] gradient block
 * ```
 *
 * Future trailing fields append after the gradient block and decode via
 * `buf.hasRemaining()` — the ShapeCodec strokeStyle convention.
 */
object StickyCodec {

    /** [com.aichat.sandbox.data.model.NoteItem.kind] value for sticky notes. */
    const val KIND: String = "sticky"

    const val VERSION: Byte = 1

    /** World-unit edge of a freshly dropped sticky. */
    const val DEFAULT_SIZE_WORLD: Float = 160f

    /** Base font size for new stickies; the renderer shrinks to fit. */
    const val DEFAULT_FONT_SIZE: Float = 22f

    /** Inner padding between the rect edge and the laid-out text. */
    const val TEXT_INSET_WORLD: Float = 12f

    /** Corner radius of the rendered rect. */
    const val CORNER_RADIUS_WORLD: Float = 8f

    /**
     * The 8 preset fills (classic sticky palette). Raw ARGB ints so the
     * codec — and the tests — stay JVM-pure.
     */
    val PRESET_FILLS: List<Int> = listOf(
        0xFFFFF59D.toInt(), // yellow
        0xFFFFCC80.toInt(), // orange
        0xFFF8BBD0.toInt(), // pink
        0xFFEF9A9A.toInt(), // red
        0xFFA5D6A7.toInt(), // green
        0xFF90CAF9.toInt(), // blue
        0xFFCE93D8.toInt(), // purple
        0xFFE0E0E0.toInt(), // grey
    )

    data class StickyPayload(
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float,
        val fillArgb: Int,
        val fontSize: Float,
        val body: String,
        val gradient: FillStyle.Gradient? = null,
    ) {
        val width: Float get() = maxX - minX
        val height: Float get() = maxY - minY
    }

    /** Build the payload for a sticky centred on (`centerX`, `centerY`). */
    fun newAt(
        centerX: Float,
        centerY: Float,
        fillArgb: Int,
        size: Float = DEFAULT_SIZE_WORLD,
        body: String = "",
    ): StickyPayload = StickyPayload(
        minX = centerX - size / 2f,
        minY = centerY - size / 2f,
        maxX = centerX + size / 2f,
        maxY = centerY + size / 2f,
        fillArgb = fillArgb,
        fontSize = DEFAULT_FONT_SIZE,
        body = body,
    )

    fun encode(payload: StickyPayload): ByteArray {
        val bodyBytes = payload.body.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer
            .allocate(1 + 4 * 4 + 4 + 4 + 4 + bodyBytes.size + FillStyle.byteSize(payload.gradient))
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(VERSION)
        buf.putFloat(payload.minX)
        buf.putFloat(payload.minY)
        buf.putFloat(payload.maxX)
        buf.putFloat(payload.maxY)
        buf.putInt(payload.fillArgb)
        buf.putFloat(payload.fontSize)
        buf.putInt(bodyBytes.size)
        buf.put(bodyBytes)
        FillStyle.encode(buf, payload.gradient)
        return buf.array()
    }

    fun decode(payload: ByteArray): StickyPayload {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val version = buf.get()
        require(version == VERSION) { "StickyCodec: unknown version $version" }
        val minX = buf.float
        val minY = buf.float
        val maxX = buf.float
        val maxY = buf.float
        val fillArgb = buf.int
        val fontSize = buf.float
        val bodyLen = buf.int
        require(bodyLen >= 0 && bodyLen <= buf.remaining()) {
            "StickyCodec: body length $bodyLen exceeds remaining ${buf.remaining()}"
        }
        val bodyBytes = ByteArray(bodyLen)
        buf.get(bodyBytes)
        // 13.2 — optional trailing gradient block. Bytes past it are future
        // trailing fields — ignored on read, dropped on the next re-encode
        // (acceptable: this build can't have produced them, so it can't be
        // asked to preserve them faithfully).
        val gradient = FillStyle.decode(buf)
        return StickyPayload(
            minX = minX, minY = minY, maxX = maxX, maxY = maxY,
            fillArgb = fillArgb,
            fontSize = fontSize,
            body = String(bodyBytes, Charsets.UTF_8),
            gradient = gradient,
        )
    }

    /** Returns [payload] with [body] swapped — the inline editor's commit path. */
    fun withBody(payload: StickyPayload, body: String): StickyPayload =
        payload.copy(body = body)

    /**
     * Apply a [StrokeTransform]-layout affine to the sticky. Stickies stay
     * axis-aligned: the rect corners map through the matrix and the result
     * is their envelope (rotation degrades to its bounding box, mirroring
     * [ImageItemCodec.transform]); the font scales with the matrix's
     * geometric-mean scale so the text keeps its proportion.
     */
    fun transform(payload: StickyPayload, matrix: FloatArray): StickyPayload {
        val corners = floatArrayOf(
            payload.minX, payload.minY,
            payload.maxX, payload.minY,
            payload.maxX, payload.maxY,
            payload.minX, payload.maxY,
        )
        val t = StrokeTransform.applyToPoints(matrix, corners)
        var minX = t[0]; var minY = t[1]
        var maxX = t[0]; var maxY = t[1]
        var i = 2
        while (i < t.size) {
            if (t[i] < minX) minX = t[i] else if (t[i] > maxX) maxX = t[i]
            if (t[i + 1] < minY) minY = t[i + 1] else if (t[i + 1] > maxY) maxY = t[i + 1]
            i += 2
        }
        val sx = kotlin.math.hypot(matrix[0], matrix[3])
        val sy = kotlin.math.hypot(matrix[1], matrix[4])
        val scale = kotlin.math.sqrt((sx * sy).coerceAtLeast(0f))
        return payload.copy(
            minX = minX, minY = minY, maxX = maxX, maxY = maxY,
            fontSize = (payload.fontSize * scale).coerceAtLeast(1f),
        )
    }

    /** World-space `[minX, minY, maxX, maxY]`. */
    fun boundsOf(payload: StickyPayload): FloatArray =
        floatArrayOf(payload.minX, payload.minY, payload.maxX, payload.maxY)
}
