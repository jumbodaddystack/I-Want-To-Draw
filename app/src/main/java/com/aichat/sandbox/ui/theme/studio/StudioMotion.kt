package com.aichat.sandbox.ui.theme.studio

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Immutable

/**
 * Studio Bench — motion tokens.
 *
 * Signature = "rack focus": when the active tool or stage changes, the chrome
 * cross-dissolves while the artboard stays rock-steady — the work never jumps.
 * The accent glows in on selection. Every duration here collapses to 0 when
 * the platform reduce-motion setting is on (see [rememberStudioReduceMotion]).
 */
@Immutable
data class StudioMotion(
    /** Accent glow-in when a tool/stage becomes active. */
    val rackFocusMillis: Int = 120,
    /** Inspector content cross-fade. */
    val dissolveMillis: Int = 160,
    /** Stage/tab shift. */
    val tabShiftMillis: Int = 200,
    val easeOut: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
    val standard: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f),
    val linear: Easing = LinearEasing,
) {
    /** Returns 0 when motion should be suppressed, else [millis]. */
    fun duration(millis: Int, reduceMotion: Boolean): Int =
        if (reduceMotion) 0 else millis
}

val StudioMotionDefault = StudioMotion()
