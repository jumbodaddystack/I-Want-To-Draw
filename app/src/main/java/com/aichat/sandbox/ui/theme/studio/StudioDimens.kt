package com.aichat.sandbox.ui.theme.studio

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Studio Bench — spacing, radius, and sizing tokens.
 *
 * Base unit is 4dp. The point of having named tokens (vs. scattering `16.dp`)
 * is rhythm: tight clusters inside the rail, generous section breaks, and a
 * measured artboard margin. Components must consume these, never inline dp.
 */
@Immutable
data class StudioSpacing(
    val hair: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

/**
 * Sharp / machined corner language. Chrome is near-square; only the artboard
 * cradle gets a small radius, and the "live" pill (active tool + primary
 * action) is fully rounded so it reads as the one distinct element.
 */
@Immutable
data class StudioRadius(
    val xs: Dp = 2.dp,
    val s: Dp = 3.dp,
    val m: Dp = 4.dp,
    val l: Dp = 6.dp,
    val pill: Dp = 999.dp,
)

@Immutable
data class StudioSizing(
    /** Hairline rule thickness. */
    val hairline: Dp = 1.dp,
    /** Focused / active edge thickness. */
    val hairlineStrong: Dp = 1.5.dp,
    /** Minimum touch target. */
    val touchTarget: Dp = 48.dp,
    /** Vertical tool rail width on tablet/expanded. */
    val railWidth: Dp = 72.dp,
    /** Persistent inspector width on tablet/expanded. */
    val inspectorWidth: Dp = 320.dp,
    /** Length of a corner tick on the artboard cradle. */
    val cornerTick: Dp = 14.dp,
    /** The breakpoint where phone (sheets) becomes tablet (orbiting panes). */
    val expandedBreakpoint: Dp = 600.dp,
)

val StudioSpacingDefault = StudioSpacing()
val StudioRadiusDefault = StudioRadius()
val StudioSizingDefault = StudioSizing()
