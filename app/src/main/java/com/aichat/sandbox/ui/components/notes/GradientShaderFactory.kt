package com.aichat.sandbox.ui.components.notes

import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.sqrt

/**
 * Sub-phase 13.2 — maps a bounds-normalized [FillStyle.Gradient] onto an
 * item's world bounds as an [android.graphics.Shader]. The radial radius
 * scales by `sqrt((w² + h²) / 2)`, matching SVG `objectBoundingBox`
 * percentage rules so the on-canvas render and the SVG export agree.
 */
object GradientShaderFactory {

    /** Shader for [gradient] over `[minX, minY, maxX, maxY]`; null when degenerate. */
    fun shaderFor(gradient: FillStyle.Gradient, bounds: FloatArray): Shader? {
        if (gradient.stops.size < 2) return null
        val w = bounds[2] - bounds[0]
        val h = bounds[3] - bounds[1]
        if (w <= 0f && h <= 0f) return null
        val colors = IntArray(gradient.stops.size) { gradient.stops[it].argb }
        val positions = FloatArray(gradient.stops.size) { gradient.stops[it].offset }
        return when (gradient.type) {
            FillStyle.TYPE_RADIAL -> {
                val r = gradient.x1 * sqrt((w * w + h * h) / 2f)
                if (r <= 0f) return null
                RadialGradient(
                    bounds[0] + gradient.x0 * w,
                    bounds[1] + gradient.y0 * h,
                    r,
                    colors,
                    positions,
                    Shader.TileMode.CLAMP,
                )
            }
            else -> LinearGradient(
                bounds[0] + gradient.x0 * w,
                bounds[1] + gradient.y0 * h,
                bounds[0] + gradient.x1 * w,
                bounds[1] + gradient.y1 * h,
                colors,
                positions,
                Shader.TileMode.CLAMP,
            )
        }
    }
}
