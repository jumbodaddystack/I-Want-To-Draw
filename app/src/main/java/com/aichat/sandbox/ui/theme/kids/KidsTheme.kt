package com.aichat.sandbox.ui.theme.kids

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Doodle Pad — Kids scoped theme.
 *
 * Wrap a kid-facing surface in [KidsTheme] to opt it into the single playful,
 * always-light identity. Like the Studio theme it layers custom token
 * CompositionLocals on top of a Material 3 [MaterialTheme] whose colour scheme
 * is remapped onto the [KidsColors] palette — so stock M3 controls (text fields,
 * dialogs, buttons inside the new-notebook flow) inherit the identity for free,
 * while bespoke chrome reads the richer Kids tokens directly.
 *
 * There is no `dark` parameter on purpose: the app is committed to one bright
 * identity, which removes the old half-working dark path and the
 * `isSystemInDarkTheme()` vs preference mismatch.
 *
 * Usage:
 * ```
 * KidsTheme { KidsHomeScreen(...) }
 * val c = KidsTheme.colors
 * ```
 */
object KidsTheme {
    val colors: KidsColors
        @Composable @ReadOnlyComposable get() = LocalKidsColors.current
    val type: KidsTypography
        @Composable @ReadOnlyComposable get() = LocalKidsTypography.current
    val spacing: KidsSpacing
        @Composable @ReadOnlyComposable get() = LocalKidsSpacing.current
    val shapes: KidsShapes
        @Composable @ReadOnlyComposable get() = LocalKidsShapes.current
    val sizing: KidsSizing
        @Composable @ReadOnlyComposable get() = LocalKidsSizing.current
}

val LocalKidsColors = staticCompositionLocalOf { KidsPalette }
val LocalKidsTypography = staticCompositionLocalOf { KidsTypographyDefault }
val LocalKidsSpacing = staticCompositionLocalOf { KidsSpacingDefault }
val LocalKidsShapes = staticCompositionLocalOf { KidsShapesDefault }
val LocalKidsSizing = staticCompositionLocalOf { KidsSizingDefault }

@Composable
fun KidsTheme(
    content: @Composable () -> Unit,
) {
    val colors = KidsPalette

    // Map the Kids palette onto a light M3 ColorScheme so built-in controls
    // (TextField, AlertDialog, Button, RadioButton…) adopt the identity without
    // per-call recolouring. Bespoke chrome reads KidsColors directly.
    val scheme = lightColorScheme(
        primary = colors.primary,
        onPrimary = colors.onPrimary,
        secondary = colors.accentCoral,
        onSecondary = colors.onAccent,
        background = colors.background,
        onBackground = colors.inkDefault,
        surface = colors.surface,
        onSurface = colors.inkStrong,
        surfaceVariant = colors.surfaceSky,
        onSurfaceVariant = colors.inkMuted,
        error = colors.error,
        outline = colors.hairline,
        primaryContainer = colors.accentSun,
        onPrimaryContainer = colors.onAccent,
    )

    CompositionLocalProvider(
        LocalKidsColors provides colors,
        LocalKidsTypography provides KidsTypographyDefault,
        LocalKidsSpacing provides KidsSpacingDefault,
        LocalKidsShapes provides KidsShapesDefault,
        LocalKidsSizing provides KidsSizingDefault,
        LocalContentColor provides colors.inkDefault,
        LocalTextStyle provides KidsTypographyDefault.body,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
