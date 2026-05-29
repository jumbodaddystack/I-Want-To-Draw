package com.aichat.sandbox.ui.theme.studio

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Studio Bench — type tokens.
 *
 * Two voices give the "precise instrument" feel:
 *  - a default sans for prose, labels, and titles, with real contrast in
 *    size/weight/tracking (not just bold-as-emphasis), and
 *  - a MONOSPACE voice for every numeric readout (coordinates, byte counts,
 *    path counts, tolerances) so numbers read like instrument displays.
 *
 * `section` is an all-caps, wide-tracked micro label used as stage markers —
 * it replaces the generic `titleMedium` section headers from the old screens.
 */
@Immutable
data class StudioTypography(
    val display: TextStyle = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.3).sp,
    ),
    val title: TextStyle = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    /** ALL-CAPS stage marker. Apply `.uppercase()` to the string at call site. */
    val section: TextStyle = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.4.sp,
    ),
    val body: TextStyle = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    val label: TextStyle = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    /** Instrument readout — coordinates, bytes, counts, tolerances. */
    val monoReadout: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
    /** Tiny mono used for dimension ticks on the artboard cradle. */
    val monoTick: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 12.sp,
    ),
)

val StudioTypographyDefault = StudioTypography()
