package com.aichat.sandbox.ui.components.notes

import com.aichat.sandbox.data.model.NoteItem

/**
 * Pure helpers backing [DrawingSurface]'s incremental scene compositing — the
 * machinery that keeps the canvas smooth as drawings grow. All free of Android
 * types so they stay JVM-unit-testable (`SceneCompositingTest`).
 */

/**
 * Scale+translate parameters for blitting the committed-scene bitmap during a
 * pan/zoom gesture, so the cached pixels can be transformed cheaply instead of
 * re-rasterizing every committed item every frame.
 *
 * The bitmap was rasterized at the *render* viewport (`screen = world * sRender
 * + oRender`), so a committed point lives at pixel `p = world * sRender +
 * oRender`. To show it at the *current* viewport (`screen = world * sCur +
 * oCur`) we solve `world = (p - oRender) / sRender` and substitute:
 *
 * ```
 * screen = p * (sCur / sRender) + (oCur - oRender * sCur / sRender)
 * ```
 *
 * a uniform scale `s` then a translate `(tx, ty)`. The caller feeds the result
 * into an `android.graphics.Matrix`.
 *
 * @return `[s, tx, ty]`.
 */
internal fun sceneBlitParams(
    renderScale: Float,
    renderOffsetX: Float,
    renderOffsetY: Float,
    curScale: Float,
    curOffsetX: Float,
    curOffsetY: Float,
): FloatArray {
    val s = curScale / renderScale
    val tx = curOffsetX - renderOffsetX * s
    val ty = curOffsetY - renderOffsetY * s
    return floatArrayOf(s, tx, ty)
}

/**
 * Order-sensitive hash of the render-relevant fields of [items] (id, zIndex,
 * payload size, layerId). Two sets with the same signature produce the same
 * committed pixels, so `DrawingSurface.replayItems` can skip a redundant raster
 * when the ViewModel hands back a set already reflected on the bitmap.
 */
internal fun itemsSignature(items: List<NoteItem>): Long {
    var h = 1125899906842597L
    for (it in items) {
        h = 31 * h + it.id.hashCode()
        h = 31 * h + it.zIndex
        h = 31 * h + it.payload.size
        h = 31 * h + (it.layerId?.hashCode() ?: 0)
    }
    return h
}

/**
 * True when [item] sorts last among the currently-visible [committed] items
 * under [LayerLookup.renderOrder]'s `(ordinal, zIndex, id)` key — i.e. it paints
 * on top, so compositing it over the existing bitmap matches a full raster. When
 * false (e.g. a highlighter the VM will sink under ink), the caller must
 * full-raster for correctness.
 */
internal fun sortsOnTop(
    item: NoteItem,
    committed: List<NoteItem>,
    layers: LayerLookup,
): Boolean {
    val newOrdinal = layers.renderOrdinal(item)
    val newZ = item.zIndex
    for (existing in committed) {
        if (existing.id == item.id) continue
        if (!layers.isVisible(existing)) continue
        val ordinal = layers.renderOrdinal(existing)
        if (ordinal > newOrdinal) return false
        if (ordinal == newOrdinal) {
            if (existing.zIndex > newZ) return false
            if (existing.zIndex == newZ && existing.id > item.id) return false
        }
    }
    return true
}
