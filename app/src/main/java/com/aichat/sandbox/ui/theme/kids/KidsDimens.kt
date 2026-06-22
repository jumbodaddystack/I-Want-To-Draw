package com.aichat.sandbox.ui.theme.kids

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Kids spacing — 4dp base, generous by default. Small hands and a 4–10 audience
 * want roomy padding and big gaps, so the scale skews larger than a typical
 * Material rhythm. Components consume these, never inline dp.
 */
@Immutable
data class KidsSpacing(
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 12.dp,
    val l: Dp = 16.dp,
    val xl: Dp = 20.dp,
    val xxl: Dp = 28.dp,
)

/**
 * One rounded, bouncy shape language across the whole app. Replaces the
 * 20 / 28 / 30 / 32dp grab-bag the screens each invented.
 */
@Immutable
data class KidsShapes(
    val tile: RoundedCornerShape = RoundedCornerShape(20.dp),
    val card: RoundedCornerShape = RoundedCornerShape(28.dp),
    val button: RoundedCornerShape = RoundedCornerShape(30.dp),
    val chip: RoundedCornerShape = RoundedCornerShape(50),
    val pill: RoundedCornerShape = RoundedCornerShape(999.dp),
)

@Immutable
data class KidsSizing(
    /** Minimum touch target — bumped above the 48dp floor for little fingers. */
    val touchTarget: Dp = 56.dp,
    /** Big primary buttons (New sheet / Add notebook / FAB). */
    val bigButton: Dp = 64.dp,
    /** Hairline rule thickness. */
    val hairline: Dp = 1.dp,
)

val KidsSpacingDefault = KidsSpacing()
val KidsShapesDefault = KidsShapes()
val KidsSizingDefault = KidsSizing()
