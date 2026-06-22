package com.aichat.sandbox.ui.theme.kids

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Kids type scale — one coherent set of big, friendly, heavy styles. Replaces
 * the per-screen mix of `headlineLarge` / `headlineSmall` + raw `sp` literals.
 *
 * Everything is rounded sans (system default for now; a bundled rounded display
 * face is a later asset swap) and leans heavy — kids read shape and weight long
 * before they read words, so titles are Black and body stays large (16sp) and
 * SemiBold for legibility.
 */
@Immutable
data class KidsTypography(
    /** Big home title ("Doodle Pad"). */
    val display: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 30.sp,
        lineHeight = 36.sp,
    ),
    /** Screen / card titles. */
    val title: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    /** Section headings. */
    val heading: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    /** Body / subtitles — deliberately large for young readers. */
    val body: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    /** Small labels, counts. */
    val label: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    /** Big button text. */
    val button: TextStyle = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 20.sp,
        lineHeight = 24.sp,
    ),
)

val KidsTypographyDefault = KidsTypography()
