package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.ConnectorCodec
import com.aichat.sandbox.ui.components.notes.FillStyle
import com.aichat.sandbox.ui.components.notes.PathCodec
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.StickyCodec
import com.aichat.sandbox.ui.components.notes.TextItemCodec

/**
 * Sub-phase 13.3 — style copy/paste.
 *
 * [StyleTransfer.styleOf] lifts the visual style off a single item;
 * [StyleTransfer.applyTo] writes the applicable subset back onto another
 * item per kind (text takes colour only, stickies take fill/gradient only,
 * connectors take colour / width / line style, …). Pure JVM — the codecs
 * carry all the bytes.
 */
data class CopiedStyle(
    val strokeArgb: Int,
    val strokeWidthPx: Float,
    val fillArgb: Int,
    val gradient: FillStyle.Gradient?,
    val strokeStyle: Byte,
    val capJoin: Int,
)

/** Process-scoped style clipboard, mirroring [NoteClipboard]'s lifecycle. */
object StyleClipboard {

    private var style: CopiedStyle? = null

    fun put(style: CopiedStyle) {
        this.style = style
    }

    fun peek(): CopiedStyle? = style

    fun isEmpty(): Boolean = style == null

    fun clear() {
        style = null
    }
}

object StyleTransfer {

    /** The style carried by [item], or null for unstyleable kinds (images…). */
    fun styleOf(item: NoteItem): CopiedStyle? = when (item.kind) {
        NoteItem.KIND_STROKE, TextItemCodec.KIND -> CopiedStyle(
            strokeArgb = item.colorArgb,
            strokeWidthPx = item.baseWidthPx,
            fillArgb = 0,
            gradient = null,
            strokeStyle = ShapeCodec.STROKE_STYLE_SOLID,
            capJoin = PathCodec.DEFAULT_CAP_JOIN,
        )
        Shape.KIND -> {
            val decoded = ShapeCodec.decode(item.payload)
            CopiedStyle(
                strokeArgb = item.colorArgb,
                strokeWidthPx = item.baseWidthPx,
                fillArgb = decoded.fillArgb,
                gradient = decoded.gradient,
                strokeStyle = decoded.strokeStyle,
                capJoin = PathCodec.DEFAULT_CAP_JOIN,
            )
        }
        PathCodec.KIND -> {
            val payload = PathCodec.decode(item.payload)
            CopiedStyle(
                strokeArgb = item.colorArgb,
                strokeWidthPx = item.baseWidthPx,
                fillArgb = payload.fillArgb,
                gradient = payload.gradient,
                strokeStyle = payload.strokeStyle,
                capJoin = payload.capJoin,
            )
        }
        StickyCodec.KIND -> {
            val payload = StickyCodec.decode(item.payload)
            CopiedStyle(
                strokeArgb = item.colorArgb,
                strokeWidthPx = item.baseWidthPx,
                fillArgb = payload.fillArgb,
                gradient = payload.gradient,
                strokeStyle = ShapeCodec.STROKE_STYLE_SOLID,
                capJoin = PathCodec.DEFAULT_CAP_JOIN,
            )
        }
        ConnectorCodec.KIND -> {
            val payload = ConnectorCodec.decode(item.payload)
            CopiedStyle(
                strokeArgb = item.colorArgb,
                strokeWidthPx = item.baseWidthPx,
                fillArgb = 0,
                gradient = null,
                strokeStyle = payload.strokeStyle,
                capJoin = PathCodec.DEFAULT_CAP_JOIN,
            )
        }
        else -> null
    }

    /**
     * [item] restyled with the applicable subset of [style], or null when
     * the kind takes no style or nothing would change (so callers can skip
     * no-op undo entries).
     */
    fun applyTo(item: NoteItem, style: CopiedStyle): NoteItem? {
        val restyled = when (item.kind) {
            NoteItem.KIND_STROKE -> item.copy(
                colorArgb = style.strokeArgb,
                baseWidthPx = style.strokeWidthPx,
            )
            TextItemCodec.KIND -> item.copy(colorArgb = style.strokeArgb)
            Shape.KIND -> {
                val decoded = ShapeCodec.decode(item.payload)
                item.copy(
                    colorArgb = style.strokeArgb,
                    baseWidthPx = style.strokeWidthPx,
                    payload = ShapeCodec.encode(
                        decoded.shape, style.fillArgb, style.strokeStyle, style.gradient,
                    ),
                )
            }
            PathCodec.KIND -> {
                val payload = PathCodec.decode(item.payload)
                item.copy(
                    colorArgb = style.strokeArgb,
                    baseWidthPx = style.strokeWidthPx,
                    payload = PathCodec.encode(payload.copy(
                        fillArgb = style.fillArgb,
                        strokeStyle = style.strokeStyle,
                        capJoin = style.capJoin,
                        gradient = style.gradient,
                    )),
                )
            }
            StickyCodec.KIND -> {
                // A sticky is always opaque — a fill-less style leaves it be.
                if (style.fillArgb == 0 && style.gradient == null) return null
                val payload = StickyCodec.decode(item.payload)
                item.copy(payload = StickyCodec.encode(payload.copy(
                    fillArgb = if (style.fillArgb != 0) style.fillArgb else payload.fillArgb,
                    gradient = style.gradient,
                )))
            }
            ConnectorCodec.KIND -> {
                val payload = ConnectorCodec.decode(item.payload)
                item.copy(
                    colorArgb = style.strokeArgb,
                    baseWidthPx = style.strokeWidthPx,
                    payload = ConnectorCodec.encode(payload.copy(strokeStyle = style.strokeStyle)),
                )
            }
            else -> return null
        }
        val unchanged = restyled.colorArgb == item.colorArgb &&
            restyled.baseWidthPx == item.baseWidthPx &&
            restyled.payload.contentEquals(item.payload)
        return if (unchanged) null else restyled
    }
}
