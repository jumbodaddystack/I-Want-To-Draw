package com.aichat.sandbox.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Sub-phase 6.5 — named brush settings combo. App-scope rows ship with the
 * binary and are never user-editable; cloning produces a user-scope copy.
 *
 * Tool scoping is deliberate: a pen preset can't be applied as a highlighter
 * because the per-tool dynamics curves and paint configuration in
 * [com.aichat.sandbox.ui.components.notes.ToolDynamics] are tool-specific.
 *
 * Field semantics:
 *  - [opacity] ∈ 0..1 — multiplied into the colour alpha at paint time.
 *  - [taperStart] / [taperEnd] ∈ 0..1 — fraction of the stroke length that
 *    fades in / out (width and alpha multiplied down to zero at the
 *    extremes).
 *  - [jitter] ∈ 0..1 — standard-deviation of per-sample width jitter in
 *    pixels (deterministic from sample index).
 *  - [pressureCurveId] ∈ `"LINEAR" | "EASE_IN" | "EASE_OUT" | "EASE_IN_OUT"`.
 *  - [textureId] is the [com.aichat.sandbox.ui.components.notes.TextureRegistry]
 *    key. `"smooth"` means no shader.
 */
@Entity(
    tableName = "brush_presets",
    indices = [Index("tool")],
)
data class BrushPreset(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val ownerScope: String,   // "app" | "user"
    val name: String,
    val tool: String,
    val colorArgb: Int,
    val baseWidthPx: Float,
    val opacity: Float,
    val taperStart: Float,
    val taperEnd: Float,
    val jitter: Float,
    val pressureCurveId: String,
    val textureId: String,
    val ordinal: Int,
) {
    companion object {
        const val SCOPE_APP = "app"
        const val SCOPE_USER = "user"

        const val CURVE_LINEAR = "LINEAR"
        const val CURVE_EASE_IN = "EASE_IN"
        const val CURVE_EASE_OUT = "EASE_OUT"
        const val CURVE_EASE_IN_OUT = "EASE_IN_OUT"

        const val TEXTURE_SMOOTH = "smooth"
        const val TEXTURE_CHARCOAL = "charcoal"
        const val TEXTURE_WATERCOLOR = "watercolor"
        const val TEXTURE_MARKER = "marker"
    }
}
