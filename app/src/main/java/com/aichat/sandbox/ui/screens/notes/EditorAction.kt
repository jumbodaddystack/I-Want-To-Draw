package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.ui.components.notes.ImageItemCodec
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import com.aichat.sandbox.ui.components.notes.StrokeTransform
import com.aichat.sandbox.ui.components.notes.TextItemCodec

private const val STROKE_KIND = "stroke"
private const val TEXT_KIND = TextItemCodec.KIND
private const val SHAPE_KIND = Shape.KIND
private const val IMAGE_KIND = NoteItem.KIND_IMAGE

/**
 * Reversible canvas mutations recorded in the editor's undo / redo stack
 * (sub-phase 1.7).
 *
 * Each variant carries the full data needed to invert itself — for example
 * [RemoveItems] holds the complete [NoteItem]s rather than their ids so an
 * undo can re-insert them byte-identical, preserving stroke payload, color,
 * width, and z-index. [TransformItems] (1.8) holds the affine that was baked
 * so inversion just multiplies in the inverse. [UpdateText] (1.9) holds the
 * previous and next bodies so typing is reversible character-by-paragraph.
 *
 * Actions operate on a plain [MutableList] so the logic is testable on the
 * JVM; the editor's `SnapshotStateList<NoteItem>` satisfies that contract.
 */
sealed interface EditorAction {

    fun applyTo(items: MutableList<NoteItem>)

    fun invert(): EditorAction

    data class AddItems(val items: List<NoteItem>) : EditorAction {
        override fun applyTo(items: MutableList<NoteItem>) {
            items.addAll(this.items)
        }

        override fun invert(): EditorAction = RemoveItems(items)
    }

    data class RemoveItems(val items: List<NoteItem>) : EditorAction {
        override fun applyTo(items: MutableList<NoteItem>) {
            if (this.items.isEmpty()) return
            val ids = this.items.mapTo(HashSet(this.items.size)) { it.id }
            items.removeAll { it.id in ids }
        }

        override fun invert(): EditorAction = AddItems(items)
    }

    /**
     * Bakes an affine [matrix] into every item in [ids] by transforming its
     * decoded stroke samples and re-encoding the payload. Inversion uses
     * the matrix inverse — round-trip is exact for any non-singular affine.
     *
     * Text items (1.9) bake by mat-mul'ing the new transform into the item's
     * stored matrix; the body and font config are untouched.
     */
    data class TransformItems(val ids: List<String>, val matrix: FloatArray) : EditorAction {

        override fun applyTo(items: MutableList<NoteItem>) {
            if (ids.isEmpty() || StrokeTransform.isIdentity(matrix)) return
            val target = ids.toHashSet()
            for (i in items.indices) {
                val item = items[i]
                if (item.id !in target) continue
                items[i] = transformItem(item, matrix)
            }
        }

        override fun invert(): EditorAction = TransformItems(ids, StrokeTransform.invert(matrix))

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is TransformItems) return false
            return ids == other.ids && matrix.contentEquals(other.matrix)
        }

        override fun hashCode(): Int {
            var result = ids.hashCode()
            result = 31 * result + matrix.contentHashCode()
            return result
        }

        private fun transformItem(item: NoteItem, m: FloatArray): NoteItem = when (item.kind) {
            STROKE_KIND -> transformStroke(item, m)
            TEXT_KIND -> transformText(item, m)
            SHAPE_KIND -> transformShape(item, m)
            IMAGE_KIND -> transformImage(item, m)
            else -> item
        }

        private fun transformImage(item: NoteItem, m: FloatArray): NoteItem {
            val payload = ImageItemCodec.decode(item.payload)
            return item.copy(payload = ImageItemCodec.encode(ImageItemCodec.transform(payload, m)))
        }

        private fun transformShape(item: NoteItem, m: FloatArray): NoteItem {
            val decoded = ShapeCodec.decode(item.payload)
            val transformed = ShapeCodec.transform(decoded.shape, m)
            return item.copy(payload = ShapeCodec.encode(transformed, decoded.fillArgb))
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

    /**
     * Body replacement for a text item (sub-phase 1.9). Stores the previous
     * and next bodies so inversion swaps them — covers both "user typed a
     * paragraph" and "user erased a paragraph" with byte-identical recovery.
     *
     * The action quietly skips items whose kind is not "text" — that's a
     * stale id from an undo branch that's since been pruned. Throwing would
     * make the editor brittle to redo-of-an-old-edit interactions.
     */
    data class UpdateText(val id: String, val oldBody: String, val newBody: String) : EditorAction {

        override fun applyTo(items: MutableList<NoteItem>) {
            replaceBody(items, newBody)
        }

        override fun invert(): EditorAction = UpdateText(id, newBody, oldBody)

        private fun replaceBody(items: MutableList<NoteItem>, body: String) {
            for (i in items.indices) {
                val item = items[i]
                if (item.id != id || item.kind != TEXT_KIND) continue
                val decoded = TextItemCodec.decode(item.payload)
                if (decoded.body == body) return
                val updated = TextItemCodec.withBody(decoded, body)
                // [TextItemRenderer] notices the body change on its next
                // `layoutFor` call (cache compares `(body, fontSize, …)`).
                items[i] = item.copy(payload = TextItemCodec.encode(updated))
                return
            }
        }
    }

    /**
     * Sub-phase 6.4 — reparent every item in [ids] from [oldLayerId] to
     * [newLayerId]. Inversion swaps the two layer ids back. Items whose
     * current layer doesn't match [oldLayerId] are skipped silently (a stale
     * undo branch can do this).
     */
    data class MoveItemsBetweenLayers(
        val ids: List<String>,
        val oldLayerId: String?,
        val newLayerId: String?,
    ) : EditorAction {
        override fun applyTo(items: MutableList<NoteItem>) {
            if (ids.isEmpty() || oldLayerId == newLayerId) return
            val target = ids.toHashSet()
            for (i in items.indices) {
                val item = items[i]
                if (item.id !in target) continue
                if (item.layerId != oldLayerId) continue
                items[i] = item.copy(layerId = newLayerId)
            }
        }
        override fun invert(): EditorAction = MoveItemsBetweenLayers(ids, newLayerId, oldLayerId)
    }
}
