package com.aichat.sandbox.ui.components.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.max
import kotlin.math.min

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

    /**
     * Phase 5.4 — frame [bounds] (a world-space `[minX, minY, maxX, maxY]`)
     * inside a viewport of size [canvasSize] (`[width, height]` in screen
     * pixels), leaving [marginPx] of padding on all sides. Pinch and pan are
     * unaffected; this is a one-shot teleport.
     *
     * Empty / degenerate bounds (width or height ≤ 0) and a zero-size canvas
     * are silently ignored — the chip's "Fit content" action is gated on a
     * non-empty note in the UI, but the guard keeps the controller honest
     * under racey screen-rotation events.
     */
    fun fitToContent(bounds: FloatArray, canvasSize: FloatArray, marginPx: Float = 24f) {
        if (bounds.size < 4 || canvasSize.size < 2) return
        val worldW = bounds[2] - bounds[0]
        val worldH = bounds[3] - bounds[1]
        if (worldW <= 0f || worldH <= 0f) return
        val canvasW = canvasSize[0]
        val canvasH = canvasSize[1]
        if (canvasW <= 0f || canvasH <= 0f) return
        val usableW = max(1f, canvasW - 2f * marginPx)
        val usableH = max(1f, canvasH - 2f * marginPx)
        val targetScale = min(usableW / worldW, usableH / worldH)
            .coerceIn(MIN_SCALE, MAX_SCALE)
        val cx = (bounds[0] + bounds[2]) * 0.5f
        val cy = (bounds[1] + bounds[3]) * 0.5f
        scale = targetScale
        offsetX = canvasW * 0.5f - cx * targetScale
        offsetY = canvasH * 0.5f - cy * targetScale
        onChanged?.invoke()
    }

    /**
     * Centre [bounds] in the viewport without changing scale. Useful for the
     * "Center" action when the user is happily zoomed in and just wants the
     * content back under the cursor.
     */
    fun centerOnContent(bounds: FloatArray, canvasSize: FloatArray) {
        if (bounds.size < 4 || canvasSize.size < 2) return
        val canvasW = canvasSize[0]
        val canvasH = canvasSize[1]
        if (canvasW <= 0f || canvasH <= 0f) return
        val cx = (bounds[0] + bounds[2]) * 0.5f
        val cy = (bounds[1] + bounds[3]) * 0.5f
        offsetX = canvasW * 0.5f - cx * scale
        offsetY = canvasH * 0.5f - cy * scale
        onChanged?.invoke()
    }

    /**
     * Reset to 100% zoom while keeping [canvasSize]'s centre under the
     * current viewport centre. Differs from [reset] in that it doesn't snap
     * the offset back to `(0, 0)` — the user's view "stays put" but the
     * scale snaps back to 1.0.
     */
    fun resetToOneHundred(canvasSize: FloatArray) {
        if (canvasSize.size < 2) return
        val canvasW = canvasSize[0]
        val canvasH = canvasSize[1]
        if (canvasW <= 0f || canvasH <= 0f) {
            reset()
            return
        }
        if (scale == 1f) return
        // Preserve the world point at the centre of the canvas across the
        // scale change.
        val worldCx = (canvasW * 0.5f - offsetX) / scale
        val worldCy = (canvasH * 0.5f - offsetY) / scale
        scale = 1f
        offsetX = canvasW * 0.5f - worldCx
        offsetY = canvasH * 0.5f - worldCy
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
