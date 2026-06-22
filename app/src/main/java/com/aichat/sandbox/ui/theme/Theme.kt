package com.aichat.sandbox.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val DarkBackground = Color(0xFF1A1A1A)
val DarkSurface = Color(0xFF2A2A2A)
val DarkSurfaceVariant = Color(0xFF333333)
val DarkOnSurface = Color(0xFFE0E0E0)
val DarkOnBackground = Color(0xFFE0E0E0)
val AccentBlue = Color(0xFF6B8AFF)
val AccentBlueDim = Color(0xFF4A6AE0)
val ErrorRed = Color(0xFFFF6B6B)
val UserBubble = Color(0xFF4A6AE0)
val AssistantBubble = Color(0xFF2A2A2A)

private val DarkColorScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    secondary = AccentBlueDim,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = DarkOnBackground,
    onSurface = DarkOnSurface,
    onSurfaceVariant = Color(0xFFAAAAAA),
    error = ErrorRed,
    outline = Color(0xFF444444)
)

// The app runs always-light (see MainActivity). This light scheme is aligned to
// the Kids palette (see ui/theme/kids/KidsColor.kt) so the drawing editor's
// chrome — which themes off MaterialTheme.colorScheme — shares the one playful
// identity and the single grape primary accent (no more blue-vs-cyan split).
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF7048C8),
    onPrimary = Color.White,
    secondary = Color(0xFFFF6B6B),
    onSecondary = Color.White,
    background = Color(0xFFFFF6E9),
    surface = Color.White,
    surfaceVariant = Color(0xFFE8F4FF),
    onBackground = Color(0xFF2A2540),
    onSurface = Color(0xFF2A2540),
    onSurfaceVariant = Color(0xFF6E6685),
    error = ErrorRed,
    outline = Color(0xFFECE2D5)
)

@Composable
fun DoodlePadTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
