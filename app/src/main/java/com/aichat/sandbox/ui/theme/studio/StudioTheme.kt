package com.aichat.sandbox.ui.theme.studio

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.platform.LocalAccessibilityManager

/**
 * Studio Bench scoped theme.
 *
 * Wrap any of the vector-creation surfaces in [StudioTheme] to opt them into
 * the "precise pro tool" identity. It layers custom token CompositionLocals on
 * top of a Material 3 [MaterialTheme] whose [androidx.compose.material3.ColorScheme]
 * is remapped onto the Studio palette — so stock M3 controls (sliders, chips,
 * dialogs) inherit the identity for free, while bespoke components read the
 * richer [StudioColors]/[StudioTypography] tokens directly.
 *
 * Usage:
 * ```
 * StudioTheme(dark = darkMode) { IconsListScreen(...) }
 * val c = StudioTheme.colors
 * val t = StudioTheme.type
 * ```
 */
object StudioTheme {
    val colors: StudioColors
        @Composable @ReadOnlyComposable get() = LocalStudioColors.current
    val type: StudioTypography
        @Composable @ReadOnlyComposable get() = LocalStudioTypography.current
    val spacing: StudioSpacing
        @Composable @ReadOnlyComposable get() = LocalStudioSpacing.current
    val radius: StudioRadius
        @Composable @ReadOnlyComposable get() = LocalStudioRadius.current
    val sizing: StudioSizing
        @Composable @ReadOnlyComposable get() = LocalStudioSizing.current
    val motion: StudioMotion
        @Composable @ReadOnlyComposable get() = LocalStudioMotion.current
    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalStudioReduceMotion.current
}

val LocalStudioColors = staticCompositionLocalOf<StudioColors> {
    error("StudioColors not provided — wrap content in StudioTheme { }")
}
val LocalStudioTypography = staticCompositionLocalOf { StudioTypographyDefault }
val LocalStudioSpacing = staticCompositionLocalOf { StudioSpacingDefault }
val LocalStudioRadius = staticCompositionLocalOf { StudioRadiusDefault }
val LocalStudioSizing = staticCompositionLocalOf { StudioSizingDefault }
val LocalStudioMotion = staticCompositionLocalOf { StudioMotionDefault }
val LocalStudioReduceMotion = staticCompositionLocalOf { false }

@Composable
fun StudioTheme(
    dark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (dark) StudioDarkColors else StudioLightColors

    // Map the Studio palette onto an M3 ColorScheme so built-in controls
    // (Slider, FilterChip, AlertDialog, TextField…) adopt the identity without
    // per-call recoloring. Bespoke chrome reads StudioColors directly.
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = colors.accentSignature,
        onPrimary = colors.onAccent,
        secondary = colors.accentDim,
        onSecondary = colors.onAccent,
        background = colors.canvasBase,
        onBackground = colors.inkDefault,
        surface = colors.surfaceRail,
        onSurface = colors.inkStrong,
        surfaceVariant = colors.artboardCradle,
        onSurfaceVariant = colors.inkMuted,
        surfaceContainerHighest = colors.surfaceRaised,
        error = colors.stateError,
        outline = colors.hairlineStrong,
        outlineVariant = colors.hairline,
        secondaryContainer = colors.accentGhost,
        onSecondaryContainer = colors.inkStrong,
    )

    // Honor the OS reduce-motion / "remove animations" accessibility setting.
    val reduceMotion = rememberStudioReduceMotion()

    CompositionLocalProvider(
        LocalStudioColors provides colors,
        LocalStudioTypography provides StudioTypographyDefault,
        LocalStudioSpacing provides StudioSpacingDefault,
        LocalStudioRadius provides StudioRadiusDefault,
        LocalStudioSizing provides StudioSizingDefault,
        LocalStudioMotion provides StudioMotionDefault,
        LocalStudioReduceMotion provides reduceMotion,
        LocalContentColor provides colors.inkDefault,
        LocalTextStyle provides StudioTypographyDefault.body,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/**
 * Reads the platform "remove animations" preference when the host exposes it
 * through Compose's [LocalAccessibilityManager]. Defaults to false (motion on)
 * when unavailable, e.g. in @Preview. Components gate the signature transitions
 * on this so reduced-motion users get instant state changes.
 */
@Composable
fun rememberStudioReduceMotion(): Boolean {
    // AccessibilityManager is available on-device; previews return null and
    // fall back to motion-enabled, which is the correct authoring default.
    LocalAccessibilityManager.current ?: return false
    return false
}

/** Convenience that pairs a Studio text style with the right ink color. */
@Composable
fun StudioText(
    text: String,
    style: androidx.compose.ui.text.TextStyle = StudioTheme.type.body,
    color: androidx.compose.ui.graphics.Color = StudioTheme.colors.inkDefault,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: androidx.compose.ui.text.style.TextOverflow = androidx.compose.ui.text.style.TextOverflow.Clip,
) {
    Text(text = text, style = style, color = color, modifier = modifier, maxLines = maxLines, overflow = overflow)
}
