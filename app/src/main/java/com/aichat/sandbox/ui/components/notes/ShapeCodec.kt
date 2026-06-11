package com.aichat.sandbox.ui.components.notes

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Phase 6.2 — shape binary wire format.
 *
 * Little-endian, tagged. Trailing `fillArgb:i32` is appended for every shape
 * (0 means "no fill"); the codec is otherwise minimal — colour and stroke
 * width travel on the enclosing [com.aichat.sandbox.data.model.NoteItem].
 *
 * Phase 10.3 appends an optional trailing `strokeStyle:u8` after `fillArgb`
 * (0 = solid, 1 = dashed, 2 = dotted). Decode treats a payload that ends at
 * `fillArgb` as solid, so every pre-Phase-10 payload round-trips unchanged.
 * Future trailing fields must follow the same convention: append after the
 * last optional field and decode via `buf.hasRemaining()`.
 *
 * Phase 13.2 appends the optional [FillStyle] gradient block after
 * `strokeStyle` (absent or fillType 0 = solid fill only).
 *
 * ```
 * [type:u8] <type-specific fields…> [fillArgb:i32] [strokeStyle:u8]? [gradient]?
 *
 * type 0x01 Line:    x0:f y0:f x1:f y1:f
 * type 0x02 Rect:    x0:f y0:f x1:f y1:f cornerRadius:f
 * type 0x03 Ellipse: cx:f cy:f rx:f ry:f rotationRad:f
 * type 0x04 Arrow:   x0:f y0:f x1:f y1:f headSize:f
 * type 0x05 Polygon: count:u16 [x:f y:f]*count closed:u8
 * ```
 *
 * `closed:u8` is 1 for closed polygons (filled / hit-tested as a region),
 * 0 for polylines.
 */
object ShapeCodec {

    private const val BYTES_INT: Int = 4
    private const val BYTES_FLOAT: Int = 4

    fun encode(
        shape: Shape,
        fillArgb: Int = 0,
        strokeStyle: Byte = STROKE_STYLE_SOLID,
        gradient: FillStyle.Gradient? = null,
    ): ByteArray {
        // trailing fillArgb + strokeStyle + gradient block
        val size = sizeOf(shape) + BYTES_INT + 1 + FillStyle.byteSize(gradient)
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(shape.type)
        when (shape) {
            is Shape.Line -> {
                buf.putFloat(shape.x0); buf.putFloat(shape.y0)
                buf.putFloat(shape.x1); buf.putFloat(shape.y1)
            }
            is Shape.Rect -> {
                buf.putFloat(shape.x0); buf.putFloat(shape.y0)
                buf.putFloat(shape.x1); buf.putFloat(shape.y1)
                buf.putFloat(shape.cornerRadius)
            }
            is Shape.Ellipse -> {
                buf.putFloat(shape.cx); buf.putFloat(shape.cy)
                buf.putFloat(shape.rx); buf.putFloat(shape.ry)
                buf.putFloat(shape.rotationRad)
            }
            is Shape.Arrow -> {
                buf.putFloat(shape.x0); buf.putFloat(shape.y0)
                buf.putFloat(shape.x1); buf.putFloat(shape.y1)
                buf.putFloat(shape.headSize)
            }
            is Shape.Polygon -> {
                val count = shape.points.size / 2
                buf.putShort(count.toShort())
                for (i in 0 until count * 2) buf.putFloat(shape.points[i])
                buf.put(if (shape.closed) 1.toByte() else 0.toByte())
            }
        }
        buf.putInt(fillArgb)
        buf.put(strokeStyle)
        FillStyle.encode(buf, gradient)
        return buf.array()
    }

    fun decode(payload: ByteArray): DecodedShape {
        require(payload.isNotEmpty()) { "empty shape payload" }
        val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val type = buf.get()
        val shape: Shape = when (type) {
            Shape.TYPE_LINE -> Shape.Line(
                buf.float, buf.float, buf.float, buf.float,
            )
            Shape.TYPE_RECT -> Shape.Rect(
                buf.float, buf.float, buf.float, buf.float, buf.float,
            )
            Shape.TYPE_ELLIPSE -> Shape.Ellipse(
                buf.float, buf.float, buf.float, buf.float, buf.float,
            )
            Shape.TYPE_ARROW -> Shape.Arrow(
                buf.float, buf.float, buf.float, buf.float, buf.float,
            )
            Shape.TYPE_POLYGON -> {
                val count = buf.short.toInt() and 0xFFFF
                val pts = FloatArray(count * 2)
                for (i in 0 until count * 2) pts[i] = buf.float
                val closed = buf.get() != 0.toByte()
                Shape.Polygon(pts, closed)
            }
            else -> throw IllegalArgumentException("unknown shape type=$type")
        }
        val fillArgb = buf.int
        // Phase 10.3 — optional trailing strokeStyle; payloads written before
        // the byte existed decode as solid.
        val strokeStyle = if (buf.hasRemaining()) buf.get() else STROKE_STYLE_SOLID
        // Phase 13.2 — optional trailing gradient block.
        val gradient = FillStyle.decode(buf)
        return DecodedShape(shape, fillArgb, strokeStyle, gradient)
    }

    private fun sizeOf(shape: Shape): Int = 1 + when (shape) {
        is Shape.Line -> 4 * BYTES_FLOAT
        is Shape.Rect -> 5 * BYTES_FLOAT
        is Shape.Ellipse -> 5 * BYTES_FLOAT
        is Shape.Arrow -> 5 * BYTES_FLOAT
        is Shape.Polygon -> 2 + shape.points.size * BYTES_FLOAT + 1
    }

    /**
     * Apply a 3×2 affine [matrix] (layout matches [StrokeTransform]) to every
     * coordinate inside [shape], returning a freshly-allocated transformed
     * shape. Used by [com.aichat.sandbox.ui.screens.notes.EditorAction.TransformItems]
     * to bake live drags into the on-disk geometry.
     *
     * Ellipses rotate by the matrix's rotation component if it's a pure
     * rotation/translation; non-uniform scale or shear collapses the ellipse
     * to its bounding box and re-emits an axis-aligned ellipse, since
     * skewed-ellipse rendering isn't supported by the renderer.
     */
    fun transform(shape: Shape, matrix: FloatArray): Shape {
        require(matrix.size == 9 || matrix.size == StrokeTransform.SIZE) {
            "matrix length=${matrix.size}"
        }
        fun tx(x: Float, y: Float) = matrix[0] * x + matrix[1] * y + matrix[2]
        fun ty(x: Float, y: Float) = matrix[3] * x + matrix[4] * y + matrix[5]
        return when (shape) {
            is Shape.Line -> Shape.Line(
                tx(shape.x0, shape.y0), ty(shape.x0, shape.y0),
                tx(shape.x1, shape.y1), ty(shape.x1, shape.y1),
            )
            is Shape.Rect -> {
                val x0 = tx(shape.x0, shape.y0); val y0 = ty(shape.x0, shape.y0)
                val x1 = tx(shape.x1, shape.y1); val y1 = ty(shape.x1, shape.y1)
                Shape.Rect(x0, y0, x1, y1, shape.cornerRadius * scaleHint(matrix))
            }
            is Shape.Arrow -> Shape.Arrow(
                tx(shape.x0, shape.y0), ty(shape.x0, shape.y0),
                tx(shape.x1, shape.y1), ty(shape.x1, shape.y1),
                shape.headSize * scaleHint(matrix),
            )
            is Shape.Ellipse -> {
                val cx = tx(shape.cx, shape.cy)
                val cy = ty(shape.cx, shape.cy)
                val sx = scaleHintX(matrix)
                val sy = scaleHintY(matrix)
                Shape.Ellipse(cx, cy, shape.rx * sx, shape.ry * sy, shape.rotationRad)
            }
            is Shape.Polygon -> {
                val pts = FloatArray(shape.points.size)
                var i = 0
                while (i < shape.points.size) {
                    val x = shape.points[i]; val y = shape.points[i + 1]
                    pts[i] = tx(x, y); pts[i + 1] = ty(x, y)
                    i += 2
                }
                Shape.Polygon(pts, shape.closed)
            }
        }
    }

    /** Geometric mean of x/y scale factors — used for stroke-width / corner-radius. */
    private fun scaleHint(m: FloatArray): Float {
        val sx = kotlin.math.hypot(m[0], m[3])
        val sy = kotlin.math.hypot(m[1], m[4])
        return kotlin.math.sqrt((sx * sy).coerceAtLeast(0f))
    }

    private fun scaleHintX(m: FloatArray): Float = kotlin.math.hypot(m[0], m[3])
    private fun scaleHintY(m: FloatArray): Float = kotlin.math.hypot(m[1], m[4])

    /** World-space bounds of [shape] — used for hit-test / selection / export. */
    fun boundsOf(shape: Shape): FloatArray? = when (shape) {
        is Shape.Line -> floatArrayOf(
            kotlin.math.min(shape.x0, shape.x1),
            kotlin.math.min(shape.y0, shape.y1),
            kotlin.math.max(shape.x0, shape.x1),
            kotlin.math.max(shape.y0, shape.y1),
        )
        is Shape.Rect -> floatArrayOf(shape.minX, shape.minY, shape.maxX, shape.maxY)
        is Shape.Ellipse -> {
            // Loose envelope ignoring rotation — fine for selection bounds and
            // export, the renderer applies the rotation itself.
            val rx = kotlin.math.abs(shape.rx)
            val ry = kotlin.math.abs(shape.ry)
            floatArrayOf(shape.cx - rx, shape.cy - ry, shape.cx + rx, shape.cy + ry)
        }
        is Shape.Arrow -> {
            val pad = shape.headSize.coerceAtLeast(0f)
            floatArrayOf(
                kotlin.math.min(shape.x0, shape.x1) - pad,
                kotlin.math.min(shape.y0, shape.y1) - pad,
                kotlin.math.max(shape.x0, shape.x1) + pad,
                kotlin.math.max(shape.y0, shape.y1) + pad,
            )
        }
        is Shape.Polygon -> {
            if (shape.points.size < 2) {
                null
            } else {
                var minX = shape.points[0]; var minY = shape.points[1]
                var maxX = minX; var maxY = minY
                var i = 2
                while (i < shape.points.size) {
                    val x = shape.points[i]; val y = shape.points[i + 1]
                    if (x < minX) minX = x else if (x > maxX) maxX = x
                    if (y < minY) minY = y else if (y > maxY) maxY = y
                    i += 2
                }
                floatArrayOf(minX, minY, maxX, maxY)
            }
        }
    }

    data class DecodedShape(
        val shape: Shape,
        val fillArgb: Int,
        val strokeStyle: Byte = STROKE_STYLE_SOLID,
        val gradient: FillStyle.Gradient? = null,
    )

    const val STROKE_STYLE_SOLID: Byte = 0
    const val STROKE_STYLE_DASHED: Byte = 1
    const val STROKE_STYLE_DOTTED: Byte = 2
}
