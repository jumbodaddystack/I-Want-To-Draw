package com.aichat.sandbox.ui.components.notes

/**
 * Phase 6.2 — shape primitives ("Line / Rect / Ellipse / Arrow / Polygon").
 *
 * A `Shape` is a value-class-style description of geometry only; rendering
 * paint state (color, stroke width, optional fill) lives on the enclosing
 * [com.aichat.sandbox.data.model.NoteItem]. Shapes serialise via [ShapeCodec],
 * render via [ShapeRenderer], and hit-test via [HitTest.shapeContainsPoint] /
 * [HitTest.shapeIntersectsPolygon] so every walker that handles a stroke
 * picks them up the same way.
 *
 * The polygon variant stores its vertices in world coordinates. Other shapes
 * carry the minimal scalar set the renderer needs — full bounds are derived
 * lazily.
 */
sealed interface Shape {

    val type: Byte

    data class Line(
        val x0: Float, val y0: Float,
        val x1: Float, val y1: Float,
    ) : Shape {
        override val type: Byte get() = TYPE_LINE
    }

    data class Rect(
        val x0: Float, val y0: Float,
        val x1: Float, val y1: Float,
        val cornerRadius: Float = 0f,
    ) : Shape {
        override val type: Byte get() = TYPE_RECT
        val minX: Float get() = if (x0 < x1) x0 else x1
        val minY: Float get() = if (y0 < y1) y0 else y1
        val maxX: Float get() = if (x0 > x1) x0 else x1
        val maxY: Float get() = if (y0 > y1) y0 else y1
    }

    data class Ellipse(
        val cx: Float, val cy: Float,
        val rx: Float, val ry: Float,
        val rotationRad: Float = 0f,
    ) : Shape {
        override val type: Byte get() = TYPE_ELLIPSE
    }

    data class Arrow(
        val x0: Float, val y0: Float,
        val x1: Float, val y1: Float,
        val headSize: Float,
    ) : Shape {
        override val type: Byte get() = TYPE_ARROW
    }

    data class Polygon(
        val points: FloatArray,    // [x0,y0,x1,y1,…]
        val closed: Boolean,
    ) : Shape {
        override val type: Byte get() = TYPE_POLYGON

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Polygon) return false
            return closed == other.closed && points.contentEquals(other.points)
        }

        override fun hashCode(): Int =
            31 * points.contentHashCode() + closed.hashCode()
    }

    companion object {
        const val TYPE_LINE: Byte = 0x01
        const val TYPE_RECT: Byte = 0x02
        const val TYPE_ELLIPSE: Byte = 0x03
        const val TYPE_ARROW: Byte = 0x04
        const val TYPE_POLYGON: Byte = 0x05

        /** "shape" — [com.aichat.sandbox.data.model.NoteItem.kind] for shape items. */
        const val KIND: String = "shape"
    }
}
