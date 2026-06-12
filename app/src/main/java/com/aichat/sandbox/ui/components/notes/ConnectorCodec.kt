package com.aichat.sandbox.ui.components.notes

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sub-phase 11.2 — bound-connector binary wire format.
 *
 * A connector is a straight segment whose endpoints may be *bound* to other
 * items by id + anchor. Bound endpoints are recomputed from the target
 * item's current bounds **at render time** ([ConnectorResolver]) so dragging
 * a bound item never touches the connector payload (and never spams the
 * undo log). A binding whose target no longer exists silently falls back to
 * the stored fallback endpoint — that *is* the "delete unbinds, geometry
 * kept" rule, and undoing the delete restores the binding for free.
 *
 * Layout (little-endian):
 * ```
 * [version:u8=1]
 * [fromAnchor:u8] [toAnchor:u8]      ANCHOR_N/E/S/W/CENTER
 * [styleFlags:u8]                    bit0 = arrowhead at end, bit1 = at start
 * [strokeStyle:u8]                   ShapeCodec.STROKE_STYLE_* value
 * [x0:f] [y0:f] [x1:f] [y1:f]        fallback endpoints, world units
 * [fromIdLen:u16] [fromId:utf8]      length 0 = free (unbound) endpoint
 * [toIdLen:u16]   [toId:utf8]
 * [routeStyle:u8]                    14.2, optional — ROUTE_* value; absent
 *                                    decodes as ROUTE_STRAIGHT
 * ```
 *
 * Future trailing fields append after `routeStyle` and decode via
 * `buf.hasRemaining()`.
 */
object ConnectorCodec {

    /** [com.aichat.sandbox.data.model.NoteItem.kind] value for connectors. */
    const val KIND: String = "connector"

    const val VERSION: Byte = 1

    const val ANCHOR_N: Byte = 0
    const val ANCHOR_E: Byte = 1
    const val ANCHOR_S: Byte = 2
    const val ANCHOR_W: Byte = 3
    const val ANCHOR_CENTER: Byte = 4

    const val FLAG_ARROW_END: Int = 0x01
    const val FLAG_ARROW_START: Int = 0x02

    // 14.2 — route styles, drawn by [ConnectorRouter].
    const val ROUTE_STRAIGHT: Byte = 0
    const val ROUTE_ELBOW: Byte = 1
    const val ROUTE_CURVED: Byte = 2

    data class ConnectorPayload(
        val fromItemId: String?,
        val fromAnchor: Byte,
        val toItemId: String?,
        val toAnchor: Byte,
        /** Fallback start, used while unbound or when the binding target is gone. */
        val x0: Float,
        val y0: Float,
        /** Fallback end. */
        val x1: Float,
        val y1: Float,
        val arrowAtEnd: Boolean = true,
        val arrowAtStart: Boolean = false,
        val strokeStyle: Byte = ShapeCodec.STROKE_STYLE_SOLID,
        val routeStyle: Byte = ROUTE_STRAIGHT,
    )

    fun encode(payload: ConnectorPayload): ByteArray {
        val fromBytes = (payload.fromItemId ?: "").toByteArray(Charsets.UTF_8)
        val toBytes = (payload.toItemId ?: "").toByteArray(Charsets.UTF_8)
        require(fromBytes.size <= 65_535 && toBytes.size <= 65_535) {
            "ConnectorCodec: item id too long"
        }
        val buf = ByteBuffer
            .allocate(1 + 4 + 4 * 4 + 2 + fromBytes.size + 2 + toBytes.size + 1)
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(VERSION)
        buf.put(payload.fromAnchor)
        buf.put(payload.toAnchor)
        var flags = 0
        if (payload.arrowAtEnd) flags = flags or FLAG_ARROW_END
        if (payload.arrowAtStart) flags = flags or FLAG_ARROW_START
        buf.put(flags.toByte())
        buf.put(payload.strokeStyle)
        buf.putFloat(payload.x0)
        buf.putFloat(payload.y0)
        buf.putFloat(payload.x1)
        buf.putFloat(payload.y1)
        buf.putShort(fromBytes.size.toShort())
        buf.put(fromBytes)
        buf.putShort(toBytes.size.toShort())
        buf.put(toBytes)
        buf.put(payload.routeStyle)
        return buf.array()
    }

    fun decode(payload: ByteArray): ConnectorPayload {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val version = buf.get()
        require(version == VERSION) { "ConnectorCodec: unknown version $version" }
        val fromAnchor = buf.get()
        val toAnchor = buf.get()
        val flags = buf.get().toInt()
        val strokeStyle = buf.get()
        val x0 = buf.float
        val y0 = buf.float
        val x1 = buf.float
        val y1 = buf.float
        val fromLen = buf.short.toInt() and 0xFFFF
        val fromBytes = ByteArray(fromLen)
        buf.get(fromBytes)
        val toLen = buf.short.toInt() and 0xFFFF
        val toBytes = ByteArray(toLen)
        buf.get(toBytes)
        // 14.2 — optional trailing routeStyle; pre-14.2 payloads end here.
        val routeStyle = if (buf.hasRemaining()) buf.get() else ROUTE_STRAIGHT
        // Bytes past `routeStyle` are future trailing fields — ignored.
        return ConnectorPayload(
            fromItemId = String(fromBytes, Charsets.UTF_8).ifEmpty { null },
            fromAnchor = fromAnchor,
            toItemId = String(toBytes, Charsets.UTF_8).ifEmpty { null },
            toAnchor = toAnchor,
            x0 = x0, y0 = y0, x1 = x1, y1 = y1,
            arrowAtEnd = flags and FLAG_ARROW_END != 0,
            arrowAtStart = flags and FLAG_ARROW_START != 0,
            strokeStyle = strokeStyle,
            routeStyle = routeStyle,
        )
    }

    /**
     * Apply a [StrokeTransform]-layout affine. Only the fallback endpoints
     * move — bound endpoints follow their items, which transform separately.
     */
    fun transform(payload: ConnectorPayload, matrix: FloatArray): ConnectorPayload {
        val pts = StrokeTransform.applyToPoints(
            matrix,
            floatArrayOf(payload.x0, payload.y0, payload.x1, payload.y1),
        )
        return payload.copy(x0 = pts[0], y0 = pts[1], x1 = pts[2], y1 = pts[3])
    }
}
