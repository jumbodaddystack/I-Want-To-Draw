package com.aichat.sandbox.ui.components.notes

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer

/**
 * Sub-phase 6.4 — read-only resolver from a [NoteLayer] list to the
 * per-item state every render path / hit-test path needs.
 *
 * Items whose `layerId` is `null` or points at a layer that has been
 * removed are treated as belonging to a synthesized "default" layer:
 * visible, unlocked, full opacity, render-on-top (ordinal = MAX_VALUE).
 * This keeps legacy pre-migration notes and freshly-pasted clipboard
 * items rendering correctly even before a layer has been assigned.
 */
class LayerLookup(layers: List<NoteLayer>) {

    private val byId: Map<String, NoteLayer> = layers.associateBy { it.id }

    val layers: List<NoteLayer> = layers.sortedBy { it.ordinal }

    fun get(layerId: String?): NoteLayer? = if (layerId == null) null else byId[layerId]

    fun isVisible(item: NoteItem): Boolean = get(item.layerId)?.visible ?: true

    fun isLocked(item: NoteItem): Boolean = get(item.layerId)?.locked ?: false

    /** 0..1 multiplier applied to alpha at paint time. */
    fun opacity(item: NoteItem): Float {
        val l = get(item.layerId) ?: return 1f
        return l.opacityPercent.coerceIn(0, 100) / 100f
    }

    /** Sort key for render order: `(layer.ordinal, item.zIndex, item.id)`. */
    fun renderOrdinal(item: NoteItem): Int {
        val l = get(item.layerId) ?: return Int.MAX_VALUE
        return l.ordinal
    }

    /**
     * Items sorted in render order, with hidden-layer items dropped. Stable
     * tie-break on `id` so bulk paste / duplicate operations stay
     * deterministic.
     */
    fun renderOrder(items: List<NoteItem>): List<NoteItem> =
        items.asSequence()
            .filter { isVisible(it) }
            .sortedWith(
                compareBy(
                    { renderOrdinal(it) },
                    { it.zIndex },
                    { it.id },
                )
            )
            .toList()
}
