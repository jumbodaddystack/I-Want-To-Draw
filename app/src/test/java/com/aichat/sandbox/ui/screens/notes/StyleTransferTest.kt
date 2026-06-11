package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.ConnectorCodec
import com.aichat.sandbox.ui.components.notes.FillStyle
import com.aichat.sandbox.ui.components.notes.PathCodec
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.StickyCodec
import com.aichat.sandbox.ui.components.notes.TextItemCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 13.3 — per-kind style lift ([StyleTransfer.styleOf]) and apply. */
class StyleTransferTest {

    private val gradient = FillStyle.linear(0xFF2463EB.toInt(), 0xFF9333EA.toInt())

    private fun item(kind: String, payload: ByteArray, color: Int = 0xFF112233.toInt(), width: Float = 5f) =
        NoteItem(
            noteId = "n",
            zIndex = 0,
            kind = kind,
            tool = null,
            colorArgb = color,
            baseWidthPx = width,
            payload = payload,
        )

    private fun pathItem() = item(
        PathCodec.KIND,
        PathCodec.encode(PathCodec.PathPayload(
            anchors = listOf(
                PathCodec.Anchor(0f, 0f),
                PathCodec.Anchor(50f, 0f),
                PathCodec.Anchor(50f, 50f),
            ),
            closed = true,
            fillArgb = gradient.firstStopArgb,
            strokeStyle = ShapeCodec.STROKE_STYLE_DASHED,
            capJoin = PathCodec.capJoinOf(PathCodec.CAP_SQUARE, PathCodec.JOIN_BEVEL),
            gradient = gradient,
        )),
    )

    @Test
    fun styleOfPathLiftsEverything() {
        val style = StyleTransfer.styleOf(pathItem())
        assertNotNull(style)
        assertEquals(0xFF112233.toInt(), style!!.strokeArgb)
        assertEquals(5f, style.strokeWidthPx, 1e-4f)
        assertEquals(gradient.firstStopArgb, style.fillArgb)
        assertEquals(gradient, style.gradient)
        assertEquals(ShapeCodec.STROKE_STYLE_DASHED, style.strokeStyle)
        assertEquals(PathCodec.capJoinOf(PathCodec.CAP_SQUARE, PathCodec.JOIN_BEVEL), style.capJoin)
    }

    @Test
    fun pathStyleAppliesToShape() {
        val style = StyleTransfer.styleOf(pathItem())!!
        val shape = item(
            Shape.KIND,
            ShapeCodec.encode(Shape.Rect(0f, 0f, 10f, 10f, 0f)),
            color = 0xFF000000.toInt(),
            width = 2f,
        )
        val restyled = StyleTransfer.applyTo(shape, style)
        assertNotNull(restyled)
        assertEquals(0xFF112233.toInt(), restyled!!.colorArgb)
        assertEquals(5f, restyled.baseWidthPx, 1e-4f)
        val decoded = ShapeCodec.decode(restyled.payload)
        assertEquals(gradient, decoded.gradient)
        assertEquals(gradient.firstStopArgb, decoded.fillArgb)
        assertEquals(ShapeCodec.STROKE_STYLE_DASHED, decoded.strokeStyle)
    }

    @Test
    fun pathStyleAppliesToPathIncludingCapJoin() {
        val style = StyleTransfer.styleOf(pathItem())!!
        val target = item(
            PathCodec.KIND,
            PathCodec.encode(PathCodec.PathPayload(
                anchors = listOf(PathCodec.Anchor(0f, 0f), PathCodec.Anchor(9f, 9f)),
                closed = false,
            )),
            color = 0xFF000000.toInt(),
            width = 1f,
        )
        val restyled = StyleTransfer.applyTo(target, style)!!
        val decoded = PathCodec.decode(restyled.payload)
        assertEquals(style.capJoin, decoded.capJoin)
        assertEquals(style.strokeStyle, decoded.strokeStyle)
        assertEquals(gradient, decoded.gradient)
        // Geometry untouched.
        assertEquals(2, decoded.anchors.size)
    }

    @Test
    fun textTakesColourOnly() {
        val style = StyleTransfer.styleOf(pathItem())!!
        val text = item(
            TextItemCodec.KIND,
            TextItemCodec.encode(TextItemCodec.newAt(0f, 0f, "hello")),
            color = 0xFF000000.toInt(),
            width = 1f,
        )
        val restyled = StyleTransfer.applyTo(text, style)
        assertNotNull(restyled)
        assertEquals(0xFF112233.toInt(), restyled!!.colorArgb)
        // Width and payload untouched for text.
        assertEquals(1f, restyled.baseWidthPx, 1e-4f)
        assertEquals(text.payload.toList(), restyled.payload.toList())
    }

    @Test
    fun stickyTakesFillAndGradientOnly() {
        val style = StyleTransfer.styleOf(pathItem())!!
        val sticky = item(
            StickyCodec.KIND,
            StickyCodec.encode(StickyCodec.newAt(0f, 0f, StickyCodec.PRESET_FILLS[0], body = "note")),
        )
        val restyled = StyleTransfer.applyTo(sticky, style)!!
        val decoded = StickyCodec.decode(restyled.payload)
        assertEquals(gradient, decoded.gradient)
        assertEquals(gradient.firstStopArgb, decoded.fillArgb)
        assertEquals("note", decoded.body)
        // Sticky has no stroke — item colour stays.
        assertEquals(sticky.colorArgb, restyled.colorArgb)
    }

    @Test
    fun fillLessStyleSkipsSticky() {
        val stroke = item(NoteItem.KIND_STROKE, ByteArray(0))
        val style = StyleTransfer.styleOf(stroke)!!
        val sticky = item(
            StickyCodec.KIND,
            StickyCodec.encode(StickyCodec.newAt(0f, 0f, StickyCodec.PRESET_FILLS[0])),
        )
        assertNull(StyleTransfer.applyTo(sticky, style))
    }

    @Test
    fun connectorTakesColourWidthAndLineStyle() {
        val style = StyleTransfer.styleOf(pathItem())!!
        val connector = item(
            ConnectorCodec.KIND,
            ConnectorCodec.encode(ConnectorCodec.ConnectorPayload(
                fromItemId = null, fromAnchor = ConnectorCodec.ANCHOR_CENTER,
                toItemId = null, toAnchor = ConnectorCodec.ANCHOR_CENTER,
                x0 = 0f, y0 = 0f, x1 = 100f, y1 = 0f,
            )),
            color = 0xFF000000.toInt(),
            width = 2f,
        )
        val restyled = StyleTransfer.applyTo(connector, style)!!
        assertEquals(0xFF112233.toInt(), restyled.colorArgb)
        assertEquals(5f, restyled.baseWidthPx, 1e-4f)
        assertEquals(
            ShapeCodec.STROKE_STYLE_DASHED,
            ConnectorCodec.decode(restyled.payload).strokeStyle,
        )
    }

    @Test
    fun unstyleableKindsReturnNull() {
        val image = item(NoteItem.KIND_IMAGE, ByteArray(0))
        assertNull(StyleTransfer.styleOf(image))
        val style = StyleTransfer.styleOf(pathItem())!!
        assertNull(StyleTransfer.applyTo(image, style))
    }

    @Test
    fun identicalStyleIsANoOp() {
        val source = pathItem()
        val style = StyleTransfer.styleOf(source)!!
        assertNull(StyleTransfer.applyTo(source, style))
    }

    @Test
    fun clipboardHoldsOneStyle() {
        StyleClipboard.clear()
        assertNull(StyleClipboard.peek())
        val style = StyleTransfer.styleOf(pathItem())!!
        StyleClipboard.put(style)
        assertEquals(style, StyleClipboard.peek())
        StyleClipboard.clear()
        assertNull(StyleClipboard.peek())
    }
}
