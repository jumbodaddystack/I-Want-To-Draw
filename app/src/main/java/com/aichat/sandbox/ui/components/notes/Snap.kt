package com.aichat.sandbox.ui.components.notes

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/**
 * Phase 6.3 — snapping primitives.
 *
 * Pure functions over world-space coordinates so callers can unit-test the
 * math on the JVM without an Android runtime. Each helper returns a
 * [SnapResult] with the snapped point and a "did we engage" flag — callers
 * pick whichever target is most relevant for the current gesture (angle
 * snap for line / arrow rubber-bands, grid snap for any endpoint, endpoint
 * snap for shape termini).
 *
 * Constants live as public defaults next to the helpers so the editor's
 * settings + a future debug tuning panel can override them without
 * re-deriving from scratch.
 */
object Snap {

    /** Bit 0 of `snapMask` — angle snap (line / arrow rubber-band). */
    const val MASK_ANGLE: Int = 0x1

    /** Bit 1 of `snapMask` — grid snap (endpoints land on the world grid). */
    const val MASK_GRID: Int = 0x2

    /** Bit 2 of `snapMask` — endpoint snap to existing stroke / shape termini. */
    const val MASK_ENDPOINT: Int = 0x4

    /** 15° step matches Concepts; users almost never want finer in practice. */
    val DEFAULT_ANGLE_STEP_RAD: Float = (PI / 12.0).toFloat()

    /** ±5° tolerance — within this window of a multiple of step we latch. */
    val DEFAULT_ANGLE_TOLERANCE_RAD: Float = (PI / 36.0).toFloat()

    /** World-space grid spacing — aligned with [NoteRasterizer.GRID_SPACING_WORLD]. */
    const val DEFAULT_GRID_SPACING_WORLD: Float = 32f

    /** Snap to grid only when within 8 world units of the nearest line. */
    const val DEFAULT_GRID_TOLERANCE_WORLD: Float = 8f

    /** Endpoint-to-endpoint snap radius (screen px — viewport-scaled at call site). */
    const val DEFAULT_ENDPOINT_RADIUS_PX: Float = 12f

    /** Three-component result: (`x`, `y`, snapped). Data class destructuring uses generated componentN. */
    data class SnapResult(val x: Float, val y: Float, val snapped: Boolean)

    /**
     * Snap the line `start → end` to the nearest multiple of [stepRad] when
     * the current angle is within [toleranceRad] of that multiple. Returns
     * the original `end` unchanged outside the tolerance window.
     */
    fun snapAngleTo(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        stepRad: Float = DEFAULT_ANGLE_STEP_RAD,
        toleranceRad: Float = DEFAULT_ANGLE_TOLERANCE_RAD,
    ): SnapResult {
        val dx = endX - startX
        val dy = endY - startY
        val len = hypot(dx, dy)
        if (len < 1e-3f) return SnapResult(endX, endY, false)
        val angle = atan2(dy, dx)
        val k = round(angle / stepRad)
        val nearest = k * stepRad
        if (abs(angle - nearest) > toleranceRad) return SnapResult(endX, endY, false)
        return SnapResult(
            startX + cos(nearest) * len,
            startY + sin(nearest) * len,
            true,
        )
    }

    /**
     * Snap [point] to the nearest grid intersection within [toleranceWorld].
     * Grid origin is the world origin, spacing is uniform.
     */
    fun snapToGrid(
        x: Float, y: Float,
        spacing: Float = DEFAULT_GRID_SPACING_WORLD,
        toleranceWorld: Float = DEFAULT_GRID_TOLERANCE_WORLD,
    ): SnapResult {
        if (spacing <= 0f) return SnapResult(x, y, false)
        val sx = round(x / spacing) * spacing
        val sy = round(y / spacing) * spacing
        val dx = sx - x
        val dy = sy - y
        return if (abs(dx) <= toleranceWorld && abs(dy) <= toleranceWorld) {
            SnapResult(sx, sy, true)
        } else {
            SnapResult(x, y, false)
        }
    }

    /**
     * Snap [point] to the closest endpoint in [candidates] (`[x0, y0, x1, y1, …]`)
     * within [radiusWorld]. Linear scan — fine for the candidate counts we
     * see in practice (< ~500 endpoints per note); a kd-tree would be
     * trivial if profiling demands it.
     */
    fun snapToEndpoints(
        x: Float, y: Float,
        candidates: FloatArray,
        radiusWorld: Float,
    ): SnapResult {
        if (candidates.isEmpty() || radiusWorld <= 0f) return SnapResult(x, y, false)
        var bestX = x
        var bestY = y
        var bestDist = radiusWorld
        var found = false
        var i = 0
        while (i < candidates.size - 1) {
            val cx = candidates[i]
            val cy = candidates[i + 1]
            val d = hypot(cx - x, cy - y)
            if (d <= bestDist) {
                bestDist = d
                bestX = cx
                bestY = cy
                found = true
            }
            i += 2
        }
        return SnapResult(bestX, bestY, found)
    }
}
