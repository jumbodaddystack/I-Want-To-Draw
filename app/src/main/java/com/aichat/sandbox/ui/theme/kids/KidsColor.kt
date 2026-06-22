package com.aichat.sandbox.ui.theme.kids

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Doodle Pad — Kids palette (semantic tokens).
 *
 * The single, app-wide playful identity for a 4–10 audience. It is intentionally
 * **always light**: there is one bright instance, no dark variant, and no
 * system / preference branching (that mismatch is what used to give the editor a
 * wrong-variant accent). Every kid-facing surface reads these tokens instead of
 * inlining `Color(0x…)`, so the basics — background, the one primary-action
 * colour, ink, dividers — can never drift between screens again.
 *
 * Design rules:
 *  - Exactly **one** primary-action colour ([primary]); the "big button" is the
 *    same colour on every screen so a child learns it once.
 *  - Warm accents ([accentSun]/[accentCoral]/[accentMint]/[accentSky]) are for
 *    decoration and variety only — never for the primary action.
 *  - All text tokens are chosen to clear WCAG AA on [background] and [surface].
 */
@Immutable
data class KidsColors(
    /** App background behind everything — warm, friendly cream. */
    val background: Color,
    /** Card / sheet faces. */
    val surface: Color,
    /** Cool secondary surface (e.g. notebook screens, info wells). */
    val surfaceSky: Color,
    /** Warm secondary surface (e.g. sheet-thumbnail wells). */
    val surfaceSun: Color,

    /** THE primary-action colour. FABs, "New sheet", "Create". White text on top. */
    val primary: Color,
    /** Pressed / shadow ground beneath [primary]. */
    val primaryDark: Color,
    /** Readable colour on top of [primary]. */
    val onPrimary: Color,

    /** Decorative pops — variety, never the primary action. */
    val accentSun: Color,
    val accentCoral: Color,
    val accentMint: Color,
    val accentSky: Color,
    /** Readable ink that sits on a bright accent (yellow/mint/etc). */
    val onAccent: Color,

    /** Headlines / titles — highest emphasis. */
    val inkStrong: Color,
    /** Body text. */
    val inkDefault: Color,
    /** Secondary text, subtitles, counts (AA-safe on background & surface). */
    val inkMuted: Color,

    /** Hairline dividers / idle outlines. */
    val hairline: Color,

    val error: Color,
    val success: Color,
)

/** The one, always-light Kids instance. */
val KidsPalette = KidsColors(
    background = Color(0xFFFFF6E9),
    surface = Color(0xFFFFFFFF),
    surfaceSky = Color(0xFFE8F4FF),
    surfaceSun = Color(0xFFFFF3CC),

    primary = Color(0xFF7048C8),
    primaryDark = Color(0xFF5A37A8),
    onPrimary = Color(0xFFFFFFFF),

    accentSun = Color(0xFFFFC233),
    accentCoral = Color(0xFFFF6B6B),
    accentMint = Color(0xFF2FCBA4),
    accentSky = Color(0xFF46A6FF),
    onAccent = Color(0xFF2A2540),

    inkStrong = Color(0xFF2A2540),
    inkDefault = Color(0xFF443C5C),
    inkMuted = Color(0xFF6E6685),

    hairline = Color(0xFFECE2D5),

    error = Color(0xFFD64545),
    success = Color(0xFF11885E),
)

/**
 * Pick a legible ink (near-black plum or white) to sit on an arbitrary fill —
 * used for notebook-cover titles, where the cover colour is kid-chosen and can
 * be anything from black to lemon. Uses the standard perceived-luminance rule so
 * white text never lands on a pale cover (or vice-versa).
 */
fun readableInkOn(fill: Color): Color {
    val luminance = 0.299f * fill.red + 0.587f * fill.green + 0.114f * fill.blue
    return if (luminance > 0.62f) Color(0xFF2A2540) else Color.White
}

/**
 * Curated "crayon box" — the bright, tap-to-pick colours kids draw with and the
 * cover colours for notebooks. One shared, cheerful set replaces the three
 * different palettes the screens used to invent (and the dark Material-600 cover
 * tones that clashed with the playful cards).
 */
val KidsCrayons: List<Int> = listOf(
    0xFF222222.toInt(), // crayon black
    0xFFFFFFFF.toInt(), // white
    0xFFFF5252.toInt(), // red
    0xFFFF7043.toInt(), // orange
    0xFFFFC233.toInt(), // sunshine
    0xFFFFE34D.toInt(), // lemon
    0xFF8BC34A.toInt(), // grass
    0xFF2FCBA4.toInt(), // mint
    0xFF26C6DA.toInt(), // sky
    0xFF46A6FF.toInt(), // blue
    0xFF5C6BC0.toInt(), // indigo
    0xFF7048C8.toInt(), // grape
    0xFFAB47BC.toInt(), // purple
    0xFFFF6FB5.toInt(), // bubblegum
    0xFFEC407A.toInt(), // raspberry
    0xFF8D6E63.toInt(), // chocolate
    0xFFA1887F.toInt(), // taupe
    0xFF90A4AE.toInt(), // stone
)
