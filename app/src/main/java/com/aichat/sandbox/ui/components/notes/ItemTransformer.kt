package com.aichat.sandbox.ui.components.notes

import com.aichat.sandbox.data.model.NoteItem

/**
 * Phase 10.1 — shared per-kind affine transform for [NoteItem] payloads.
 *
 * Extracted from `EditorAction.TransformItems` so per-item transforms (the
 * align / distribute actions build a different translation per item) can
 * reuse the exact same payload re-encode logic. The matrix is the 3×2 affine
 * layout used by [StrokeTransform].
 *
 * Unknown kinds pass through untouched — same contract as the undo action.
 */
object ItemTransformer {

    fun transform(item: NoteItem, matrix: FloatArray): NoteItem = when (item.kind) {
        NoteItem.KIND_STROKE -> transformStroke(item, matrix)
        TextItemCodec.KIND -> transformText(item, matrix)
        Shape.KIND -> transformShape(item, matrix)
        NoteItem.KIND_IMAGE -> transformImage(item, matrix)
        StickyCodec.KIND -> transformSticky(item, matrix)
        ConnectorCodec.KIND -> transformConnector(item, matrix)
        else -> item
    }

    private fun transformSticky(item: NoteItem, m: FloatArray): NoteItem {
        val payload = StickyCodec.decode(item.payload)
        return item.copy(payload = StickyCodec.encode(StickyCodec.transform(payload, m)))
    }

    // 11.2 — transforming a connector moves only its fallback endpoints;
    // bound ends re-resolve from their items' current bounds at render time.
    private fun transformConnector(item: NoteItem, m: FloatArray): NoteItem {
        val payload = ConnectorCodec.decode(item.payload)
        return item.copy(payload = ConnectorCodec.encode(ConnectorCodec.transform(payload, m)))
    }

    private fun transformImage(item: NoteItem, m: FloatArray): NoteItem {
        val payload = ImageItemCodec.decode(item.payload)
        return item.copy(payload = ImageItemCodec.encode(ImageItemCodec.transform(payload, m)))
    }

    private fun transformShape(item: NoteItem, m: FloatArray): NoteItem {
        val decoded = ShapeCodec.decode(item.payload)
        val transformed = ShapeCodec.transform(decoded.shape, m)
        return item.copy(
            payload = ShapeCodec.encode(transformed, decoded.fillArgb, decoded.strokeStyle),
        )
    }

    private fun transformStroke(item: NoteItem, m: FloatArray): NoteItem {
        val samples = StrokeCodec.decode(item.payload)
        if (samples.isEmpty()) return item
        val transformed = StrokeTransform.applyToSamples(m, samples)
        return item.copy(payload = StrokeCodec.encode(transformed))
    }

    private fun transformText(item: NoteItem, m: FloatArray): NoteItem {
        val decoded = TextItemCodec.decode(item.payload)
        val newMatrix = StrokeTransform.multiply(m, decoded.matrix)
        val updated = TextItemCodec.withMatrix(decoded, newMatrix)
        // No render-cache invalidation needed: [TextItemRenderer] keys its
        // cache on body / fontSize / alignment / color, all unchanged here.
        return item.copy(payload = TextItemCodec.encode(updated))
    }
}
