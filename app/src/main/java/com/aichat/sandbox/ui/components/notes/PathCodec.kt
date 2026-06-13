package com.aichat.sandbox.ui.components.notes

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sub-phase 12.1 — bezier-path binary wire format. Phase 16.1 adds
 * multi-subpath payloads + a fill rule so boolean results keep their holes
 * and imported vectors with holes render correctly.
 *
 * A path is one or more **subpaths**; each subpath is an ordered list of
 * anchors where segment *i* runs anchor *i* → anchor *i+1* as a cubic whose
 * control points are the anchors plus their **relative** handle deltas
 * (`c1 = aᵢ + outᵢ`, `c2 = aᵢ₊₁ + inᵢ₊₁`). A closed subpath adds the
 * wrap-around segment. Zero handles degrade a segment to a straight line,
 * so polygon-like paths carry no curvature cost. Stroke colour / width
 * travel on the enclosing [com.aichat.sandbox.data.model.NoteItem],
 * mirroring shapes.
 *
 * v1 layout (little-endian) — emitted for every single-subpath, non-zero
 * fill-rule payload so existing notes / undo logs / stamps stay
 * byte-identical:
 * ```
 * [version:u8=1]
 * [flags:u8]                 bit0 = closed
 * [count:u16]                anchor count
 * per anchor (25 bytes):
 *   [x:f] [y:f]
 *   [inDx:f] [inDy:f]        incoming handle, relative to the anchor
 *   [outDx:f] [outDy:f]      outgoing handle, relative to the anchor
 *   [type:u8]                0 = corner, 1 = smooth, 2 = symmetric
 * [fillArgb:i32]?            trailing optional; 0 = no fill
 * [strokeStyle:u8]?          ShapeCodec STROKE_STYLE_* value
 * [capJoin:u8]?              low nibble cap (0 butt / 1 round / 2 square),
 *                            high nibble join (0 miter / 1 round / 2 bevel)
 * [gradient]?                13.2: optional [FillStyle] gradient block
 * ```
 *
 * v2 layout (16.1) — emitted only when the payload has multiple subpaths
 * or an even-odd fill rule:
 * ```
 * [version:u8=2]
 * [flags:u8]                 bit0 = even-odd fill rule
 * [subpathCount:u16]
 * per subpath:
 *   [subFlags:u8]            bit0 = closed
 *   [count:u16]              anchor count
 *   count × 25-byte anchors  (identical record to v1)
 * [fillArgb:i32]? [strokeStyle:u8]? [capJoin:u8]? [gradient]?   (as v1)
 * ```
 *
 * Trailing fields follow the ShapeCodec strokeStyle convention: append
 * after the last anchor of the last subpath and decode via
 * `buf.hasRemaining()`, so every shorter payload decodes with defaults
 * (no fill, solid, round/round).
 */
object PathCodec {

    /** [com.aichat.sandbox.data.model.NoteItem.kind] value for path items. */
    const val KIND: String = "path"

    const val VERSION: Byte = 1

    /** 16.1 — multi-subpath + fill-rule stream. */
    const val VERSION_2: Byte = 2

    const val TYPE_CORNER: Byte = 0
    const val TYPE_SMOOTH: Byte = 1
    const val TYPE_SYMMETRIC: Byte = 2

    // 16.1 — fill rules. Non-zero matches android.graphics.Path's WINDING
    // default, which is what every pre-16.1 payload rendered with.
    const val FILL_RULE_NON_ZERO: Byte = 0
    const val FILL_RULE_EVEN_ODD: Byte = 1

    // 12.5 — capJoin nibbles. Defaults are round/round: paths come from pen
    // gestures and stroke conversion, where round terminals match the ink.
    const val CAP_BUTT: Int = 0
    const val CAP_ROUND: Int = 1
    const val CAP_SQUARE: Int = 2
    const val JOIN_MITER: Int = 0
    const val JOIN_ROUND: Int = 1
    const val JOIN_BEVEL: Int = 2

    const val DEFAULT_CAP_JOIN: Int = CAP_ROUND or (JOIN_ROUND shl 4)

    fun cap(capJoin: Int): Int = capJoin and 0x0F
    fun join(capJoin: Int): Int = (capJoin shr 4) and 0x0F
    fun capJoinOf(cap: Int, join: Int): Int = (cap and 0x0F) or ((join and 0x0F) shl 4)

    private const val FLAG_CLOSED: Int = 0x01
    private const val FLAG_EVEN_ODD: Int = 0x01
    private const val BYTES_PER_ANCHOR: Int = 6 * 4 + 1

    /** Flattening resolution — uniform-t samples per cubic segment. */
    const val FLATTEN_STEPS: Int = 16

    data class Anchor(
        val x: Float,
        val y: Float,
        val inDx: Float = 0f,
        val inDy: Float = 0f,
        val outDx: Float = 0f,
        val outDy: Float = 0f,
        val type: Byte = TYPE_CORNER,
    )

    /** 16.1 — one contour of a path. */
    data class Subpath(
        val anchors: List<Anchor>,
        val closed: Boolean,
    ) {
        /** Cubic-segment count, including the wrap-around segment when closed. */
        val segmentCount: Int
            get() = when {
                anchors.size < 2 -> 0
                closed -> anchors.size
                else -> anchors.size - 1
            }
    }

    data class PathPayload(
        val subpaths: List<Subpath>,
        val fillRule: Byte = FILL_RULE_NON_ZERO,
        val fillArgb: Int = 0,
        val strokeStyle: Byte = ShapeCodec.STROKE_STYLE_SOLID,
        val capJoin: Int = DEFAULT_CAP_JOIN,
        val gradient: FillStyle.Gradient? = null,
    ) {
        /** Single-subpath convenience constructor — the pre-16.1 shape. */
        constructor(
            anchors: List<Anchor>,
            closed: Boolean,
            fillArgb: Int = 0,
            strokeStyle: Byte = ShapeCodec.STROKE_STYLE_SOLID,
            capJoin: Int = DEFAULT_CAP_JOIN,
            gradient: FillStyle.Gradient? = null,
        ) : this(
            subpaths = listOf(Subpath(anchors, closed)),
            fillRule = FILL_RULE_NON_ZERO,
            fillArgb = fillArgb,
            strokeStyle = strokeStyle,
            capJoin = capJoin,
            gradient = gradient,
        )

        /**
         * First subpath's anchors — the only subpath for every pre-16.1
         * payload. Multi-subpath-aware consumers iterate [subpaths] instead.
         */
        val anchors: List<Anchor>
            get() = subpaths.firstOrNull()?.anchors ?: emptyList()

        /** First subpath's closed flag (see [anchors]). */
        val closed: Boolean
            get() = subpaths.firstOrNull()?.closed ?: false

        /** First subpath's segment count (see [anchors]). */
        val segmentCount: Int
            get() = subpaths.firstOrNull()?.segmentCount ?: 0

        /** Any subpath closes — gates fill rendering / export. */
        val anyClosed: Boolean
            get() = subpaths.any { it.closed }

        /**
         * Node-edit helper: rebuild the geometry of a **single-subpath**
         * payload, replacing all subpaths with one. Used where the result is
         * intentionally collapsed to a single contour (stroke→path
         * conversion, JSON round-trips).
         */
        fun withSingleSubpath(
            anchors: List<Anchor>,
            closed: Boolean = this.closed,
        ): PathPayload = copy(subpaths = listOf(Subpath(anchors, closed)))

        /**
         * Node-edit helper (17.2): rebuild the geometry of subpath [index],
         * preserving every other subpath and all style fields. The closed
         * flag defaults to the subpath's current value. This is the
         * multi-subpath generalization the node editor uses now that the
         * single-subpath gate is lifted.
         */
        fun withSubpath(
            index: Int,
            anchors: List<Anchor>,
            closed: Boolean = subpaths[index].closed,
        ): PathPayload = copy(
            subpaths = subpaths.toMutableList().also { it[index] = Subpath(anchors, closed) },
        )
    }

    fun encode(payload: PathPayload): ByteArray =
        if (payload.subpaths.size == 1 && payload.fillRule == FILL_RULE_NON_ZERO) {
            // Byte-identical to the pre-16.1 stream: every payload that
            // existed before v2 takes this branch, so persisted notes, undo
            // logs and stamps round-trip unchanged and `contentEquals`
            // no-op detection keeps working.
            encodeV1(payload)
        } else {
            encodeV2(payload)
        }

    private fun encodeV1(payload: PathPayload): ByteArray {
        val buf = ByteBuffer
            .allocate(
                1 + 1 + 2 + payload.anchors.size * BYTES_PER_ANCHOR + 4 + 1 + 1 +
                    FillStyle.byteSize(payload.gradient),
            )
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(VERSION)
        buf.put((if (payload.closed) FLAG_CLOSED else 0).toByte())
        buf.putShort(payload.anchors.size.toShort())
        putAnchors(buf, payload.anchors)
        putTrailing(buf, payload)
        return buf.array()
    }

    private fun encodeV2(payload: PathPayload): ByteArray {
        val anchorTotal = payload.subpaths.sumOf { it.anchors.size }
        val buf = ByteBuffer
            .allocate(
                1 + 1 + 2 + payload.subpaths.size * (1 + 2) +
                    anchorTotal * BYTES_PER_ANCHOR + 4 + 1 + 1 +
                    FillStyle.byteSize(payload.gradient),
            )
            .order(ByteOrder.LITTLE_ENDIAN)
        buf.put(VERSION_2)
        buf.put(
            (if (payload.fillRule == FILL_RULE_EVEN_ODD) FLAG_EVEN_ODD else 0).toByte()
        )
        buf.putShort(payload.subpaths.size.toShort())
        for (sub in payload.subpaths) {
            buf.put((if (sub.closed) FLAG_CLOSED else 0).toByte())
            buf.putShort(sub.anchors.size.toShort())
            putAnchors(buf, sub.anchors)
        }
        putTrailing(buf, payload)
        return buf.array()
    }

    private fun putAnchors(buf: ByteBuffer, anchors: List<Anchor>) {
        for (a in anchors) {
            buf.putFloat(a.x); buf.putFloat(a.y)
            buf.putFloat(a.inDx); buf.putFloat(a.inDy)
            buf.putFloat(a.outDx); buf.putFloat(a.outDy)
            buf.put(a.type)
        }
    }

    private fun putTrailing(buf: ByteBuffer, payload: PathPayload) {
        buf.putInt(payload.fillArgb)
        buf.put(payload.strokeStyle)
        buf.put(payload.capJoin.toByte())
        FillStyle.encode(buf, payload.gradient)
    }

    fun decode(payload: ByteArray): PathPayload {
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val version = buf.get()
        require(version == VERSION || version == VERSION_2) {
            "PathCodec: unknown version $version"
        }
        val flags = buf.get().toInt()
        val subpaths: List<Subpath>
        val fillRule: Byte
        if (version == VERSION) {
            subpaths = listOf(Subpath(readAnchors(buf), (flags and FLAG_CLOSED) != 0))
            fillRule = FILL_RULE_NON_ZERO
        } else {
            val subCount = buf.short.toInt() and 0xFFFF
            subpaths = ArrayList<Subpath>(subCount).apply {
                repeat(subCount) {
                    val subFlags = buf.get().toInt()
                    add(Subpath(readAnchors(buf), (subFlags and FLAG_CLOSED) != 0))
                }
            }
            fillRule = if ((flags and FLAG_EVEN_ODD) != 0) FILL_RULE_EVEN_ODD else FILL_RULE_NON_ZERO
        }
        val fillArgb = if (buf.remaining() >= 4) buf.int else 0
        val strokeStyle = if (buf.hasRemaining()) buf.get() else ShapeCodec.STROKE_STYLE_SOLID
        val capJoin = if (buf.hasRemaining()) buf.get().toInt() and 0xFF else DEFAULT_CAP_JOIN
        // 13.2 — optional trailing gradient block.
        val gradient = FillStyle.decode(buf)
        return PathPayload(
            subpaths = subpaths,
            fillRule = fillRule,
            fillArgb = fillArgb,
            strokeStyle = strokeStyle,
            capJoin = capJoin,
            gradient = gradient,
        )
    }

    /** Read a `[count:u16][anchors…]` run (shared by v1 body and v2 subpaths). */
    private fun readAnchors(buf: ByteBuffer): List<Anchor> {
        val count = buf.short.toInt() and 0xFFFF
        require(buf.remaining() >= count * BYTES_PER_ANCHOR) {
            "PathCodec: truncated payload ($count anchors, ${buf.remaining()} bytes left)"
        }
        val anchors = ArrayList<Anchor>(count)
        repeat(count) {
            anchors += Anchor(
                x = buf.float, y = buf.float,
                inDx = buf.float, inDy = buf.float,
                outDx = buf.float, outDy = buf.float,
                type = buf.get(),
            )
        }
        return anchors
    }

    /**
     * Apply a [StrokeTransform]-layout affine to **every** subpath. Anchor
     * points map through the full matrix; handle deltas through the
     * **linear part only** (no translation), so moving a path never
     * distorts its curvature and rotation / scale act on handles exactly.
     */
    fun transform(payload: PathPayload, m: FloatArray): PathPayload {
        fun tx(x: Float, y: Float) = m[0] * x + m[1] * y + m[2]
        fun ty(x: Float, y: Float) = m[3] * x + m[4] * y + m[5]
        fun dx(x: Float, y: Float) = m[0] * x + m[1] * y
        fun dy(x: Float, y: Float) = m[3] * x + m[4] * y
        return payload.copy(
            subpaths = payload.subpaths.map { sub ->
                sub.copy(
                    anchors = sub.anchors.map { a ->
                        Anchor(
                            x = tx(a.x, a.y), y = ty(a.x, a.y),
                            inDx = dx(a.inDx, a.inDy), inDy = dy(a.inDx, a.inDy),
                            outDx = dx(a.outDx, a.outDy), outDy = dy(a.outDx, a.outDy),
                            type = a.type,
                        )
                    },
                )
            },
        )
    }

    /**
     * Control points of segment [index] as `[x0,y0, c1x,c1y, c2x,c2y, x1,y1]`.
     * The closing segment (closed subpaths only) is `index == anchors.size - 1`.
     */
    fun segment(sub: Subpath, index: Int): FloatArray {
        val a = sub.anchors[index]
        val b = sub.anchors[(index + 1) % sub.anchors.size]
        return floatArrayOf(
            a.x, a.y,
            a.x + a.outDx, a.y + a.outDy,
            b.x + b.inDx, b.y + b.inDy,
            b.x, b.y,
        )
    }

    /**
     * Segment of the **first** subpath — node-edit / single-subpath callers
     * only (the node editor is gated to single-subpath payloads).
     */
    fun segment(payload: PathPayload, index: Int): FloatArray =
        segment(payload.subpaths[0], index)

    /**
     * Exact world-space bounds `[minX, minY, maxX, maxY]` across **all**
     * subpaths, or null for empty paths. Solves the cubic-extrema quadratic
     * per segment per axis rather than enveloping the control points, so
     * selection rectangles hug the curve.
     */
    fun boundsOf(payload: PathPayload): FloatArray? {
        var out: FloatArray? = null
        for (sub in payload.subpaths) {
            val b = boundsOf(sub) ?: continue
            if (out == null) {
                out = b
            } else {
                if (b[0] < out[0]) out[0] = b[0]
                if (b[1] < out[1]) out[1] = b[1]
                if (b[2] > out[2]) out[2] = b[2]
                if (b[3] > out[3]) out[3] = b[3]
            }
        }
        return out
    }

    /** Exact bounds of one subpath, or null when it has no anchors. */
    fun boundsOf(sub: Subpath): FloatArray? {
        if (sub.anchors.isEmpty()) return null
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        fun include(x: Float, y: Float) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (sub.anchors.size == 1) {
            val a = sub.anchors[0]
            include(a.x, a.y)
            return floatArrayOf(minX, minY, maxX, maxY)
        }
        for (i in 0 until sub.segmentCount) {
            val s = segment(sub, i)
            include(s[0], s[1])
            include(s[6], s[7])
            axisExtrema(s[0], s[2], s[4], s[6]) { t ->
                include(cubicAt(s[0], s[2], s[4], s[6], t), cubicAt(s[1], s[3], s[5], s[7], t))
            }
            axisExtrema(s[1], s[3], s[5], s[7]) { t ->
                include(cubicAt(s[0], s[2], s[4], s[6], t), cubicAt(s[1], s[3], s[5], s[7], t))
            }
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    /** Roots of the cubic's derivative along one axis, restricted to (0, 1). */
    private inline fun axisExtrema(p0: Float, c1: Float, c2: Float, p1: Float, emit: (Float) -> Unit) {
        val a = 3f * (-p0 + 3f * c1 - 3f * c2 + p1)
        val b = 6f * (p0 - 2f * c1 + c2)
        val c = 3f * (c1 - p0)
        if (kotlin.math.abs(a) < 1e-12f) {
            if (kotlin.math.abs(b) > 1e-12f) {
                val t = -c / b
                if (t > 0f && t < 1f) emit(t)
            }
            return
        }
        val disc = b * b - 4f * a * c
        if (disc < 0f) return
        val sq = kotlin.math.sqrt(disc)
        val t1 = (-b + sq) / (2f * a)
        val t2 = (-b - sq) / (2f * a)
        if (t1 > 0f && t1 < 1f) emit(t1)
        if (t2 > 0f && t2 < 1f) emit(t2)
    }

    /** Point on a one-axis cubic at parameter [t]. */
    fun cubicAt(p0: Float, c1: Float, c2: Float, p1: Float, t: Float): Float {
        val mt = 1f - t
        return mt * mt * mt * p0 + 3f * mt * mt * t * c1 + 3f * mt * t * t * c2 + t * t * t * p1
    }

    /**
     * Flatten one subpath to a world-space polyline `[x0,y0, x1,y1, …]` at
     * [stepsPerSegment] uniform-t samples per cubic. Includes the start
     * anchor once and, for closed subpaths, ends back at it — so the result
     * is directly usable for polygon containment and segment-distance walks.
     */
    fun flatten(sub: Subpath, stepsPerSegment: Int = FLATTEN_STEPS): FloatArray {
        val segs = sub.segmentCount
        if (segs == 0) {
            val a = sub.anchors.firstOrNull() ?: return FloatArray(0)
            return floatArrayOf(a.x, a.y)
        }
        val out = FloatArray((segs * stepsPerSegment + 1) * 2)
        val first = sub.anchors[0]
        out[0] = first.x
        out[1] = first.y
        var w = 2
        for (i in 0 until segs) {
            val s = segment(sub, i)
            for (step in 1..stepsPerSegment) {
                val t = step.toFloat() / stepsPerSegment
                out[w] = cubicAt(s[0], s[2], s[4], s[6], t)
                out[w + 1] = cubicAt(s[1], s[3], s[5], s[7], t)
                w += 2
            }
        }
        return out
    }

    /**
     * Flatten the **first** subpath — node-edit / single-subpath callers
     * only. Multi-subpath consumers (hit testing) use [flattenAll].
     */
    fun flatten(payload: PathPayload, stepsPerSegment: Int = FLATTEN_STEPS): FloatArray =
        payload.subpaths.firstOrNull()?.let { flatten(it, stepsPerSegment) } ?: FloatArray(0)

    /** 16.1 — one flattened polyline per subpath. */
    fun flattenAll(payload: PathPayload, stepsPerSegment: Int = FLATTEN_STEPS): List<FloatArray> =
        payload.subpaths.map { flatten(it, stepsPerSegment) }
}
