package com.aichat.sandbox.ui.components.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Pan / zoom state for the infinite notes canvas (sub-phase 1.5).
 *
 * Coordinates: stroke geometry is stored in **world** coordinates. The
 * viewport maps world → screen via `screen = world * scale + offset`.
 * `screenToWorld` inverts this mapping; both directions are needed because
 * input arrives in screen coords while strokes are persisted in world coords.
 *
 * Fields are backed by [mutableStateOf] so Compose-side observers (notably
 * the selection overlay in 1.8) recompose when the user pans or zooms.
 * The class itself is still Android-free, so [ViewportControllerTest] keeps
 * running on the JVM.
 */
class ViewportController(
    offsetX: Float = 0f,
    offsetY: Float = 0f,
    scale: Float = 1f,
) {

    var offsetX: Float by mutableStateOf(offsetX)
        private set
    var offsetY: Float by mutableStateOf(offsetY)
        private set
    var scale: Float by mutableStateOf(scale.coerceIn(MIN_SCALE, MAX_SCALE))
        private set

    /** Fired whenever offset or scale changes. Used by the surface to invalidate. */
    var onChanged: (() -> Unit)? = null

    fun applyPan(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        offsetX += dx
        offsetY += dy
        onChanged?.invoke()
    }

    /**
     * Multiply [scale] by [factor], keeping the world point currently under
     * (`focusScreenX`, `focusScreenY`) under the same screen point after the
     * change. The clamp on [scale] may reduce the effective factor.
     */
    fun applyZoom(focusScreenX: Float, focusScreenY: Float, factor: Float) {
        if (factor <= 0f) return
        val target = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        if (target == scale) return
        val worldX = (focusScreenX - offsetX) / scale
        val worldY = (focusScreenY - offsetY) / scale
        scale = target
        offsetX = focusScreenX - worldX * scale
        offsetY = focusScreenY - worldY * scale
        onChanged?.invoke()
    }

    fun reset() {
        if (offsetX == 0f && offsetY == 0f && scale == 1f) return
        offsetX = 0f
        offsetY = 0f
        scale = 1f
        onChanged?.invoke()
    }

    fun screenToWorldX(screenX: Float): Float = (screenX - offsetX) / scale
    fun screenToWorldY(screenY: Float): Float = (screenY - offsetY) / scale
    fun worldToScreenX(worldX: Float): Float = worldX * scale + offsetX
    fun worldToScreenY(worldY: Float): Float = worldY * scale + offsetY

    companion object {
        const val MIN_SCALE = 0.25f
        const val MAX_SCALE = 8f
    }
}
