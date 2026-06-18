package com.aichat.sandbox.ui.theme.studio

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Studio Bench — semantic color tokens.
 *
 * This is the "precise pro tool" identity for the vector-creation surfaces
 * (Icons gallery, Vector Tune-Up workspace, and the drawing canvas in icon
 * mode). It is intentionally NOT the app-wide Material theme: chat / settings
 * keep [com.aichat.sandbox.ui.theme.DoodlePadTheme]. Studio Bench is a
 * scoped overlay applied only where the artwork is the hero.
 *
 * Design rule (see anti-cookie-cutter doctrine): exactly one loud color —
 * [accentSignature], reserved for the *active tool* and the *primary action*.
 * Everything else is a graphite ramp plus hairline rules. No default Material
 * purple, no decorative gradients, no all-shadowed-card surfaces.
 */
@Immutable
data class StudioColors(
    /** Electric cyan-teal. Active tool state + primary action ONLY. */
    val accentSignature: Color,
    /** Pressed / hover ground beneath the accent. */
    val accentDim: Color,
    /** Translucent accent wash for the active-tool cradle. */
    val accentGhost: Color,
    /** Readable ink/icon color when sitting on top of the accent. */
    val onAccent: Color,

    /** App background behind the artboard. */
    val canvasBase: Color,
    /** Deepest wells — rail gutters, sunken tracks. */
    val canvasSunken: Color,
    /** Tool rail / inspector / chrome surfaces. */
    val surfaceRail: Color,
    /** Transient flyouts only — the one place elevation is allowed. */
    val surfaceRaised: Color,
    /** The measured bench the artwork sits in (the signature frame). */
    val artboardCradle: Color,
    /** The lit face an icon's transparent art is composited onto. */
    val artboardFace: Color,

    /** Dividers, insets, ruled gutters — used instead of cards. */
    val hairline: Color,
    /** Active edges, focused field borders, corner ticks. */
    val hairlineStrong: Color,

    /** Headlines / high-emphasis text. */
    val inkStrong: Color,
    /** Body text. */
    val inkDefault: Color,
    /** Secondary text, labels, readouts at rest. */
    val inkMuted: Color,
    /** Disabled / ghost chrome. */
    val inkFaint: Color,

    val stateError: Color,
    val stateSuccess: Color,
    val stateWarning: Color,

    /** True when this is the dark instance — lets components pick assets. */
    val isDark: Boolean,
)

/** Dark-first instance — the default posture for the creation surfaces. */
val StudioDarkColors = StudioColors(
    accentSignature = Color(0xFF3DE1C4),
    accentDim = Color(0xFF1F8C79),
    accentGhost = Color(0x243DE1C4), // ~14% accent
    onAccent = Color(0xFF06231E),
    canvasBase = Color(0xFF0E1113),
    canvasSunken = Color(0xFF0A0C0D),
    surfaceRail = Color(0xFF15191C),
    surfaceRaised = Color(0xFF1C2227),
    artboardCradle = Color(0xFF1A1F23),
    artboardFace = Color(0xFFFFFFFF),
    hairline = Color(0xFF283036),
    hairlineStrong = Color(0xFF38424A),
    inkStrong = Color(0xFFEAEEF1),
    inkDefault = Color(0xFFC2CBD1),
    inkMuted = Color(0xFF8A969E),
    inkFaint = Color(0xFF5A656C),
    stateError = Color(0xFFFF6B6B),
    stateSuccess = Color(0xFF4ADE9B),
    stateWarning = Color(0xFFF4B740),
    isDark = true,
)

/** Light parity instance — same identity, daylight legibility. */
val StudioLightColors = StudioColors(
    accentSignature = Color(0xFF0FB89B),
    accentDim = Color(0xFF0A7E6B),
    accentGhost = Color(0x1F0FB89B),
    onAccent = Color(0xFFFFFFFF),
    canvasBase = Color(0xFFECEFF1),
    canvasSunken = Color(0xFFE2E6E9),
    surfaceRail = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    artboardCradle = Color(0xFFF4F6F7),
    artboardFace = Color(0xFFFFFFFF),
    hairline = Color(0xFFD3DADE),
    hairlineStrong = Color(0xFFB8C1C7),
    inkStrong = Color(0xFF10161A),
    inkDefault = Color(0xFF2B353B),
    inkMuted = Color(0xFF5C6970),
    inkFaint = Color(0xFF9AA6AD),
    stateError = Color(0xFFD64545),
    stateSuccess = Color(0xFF11885E),
    stateWarning = Color(0xFFB5780F),
    isDark = false,
)
