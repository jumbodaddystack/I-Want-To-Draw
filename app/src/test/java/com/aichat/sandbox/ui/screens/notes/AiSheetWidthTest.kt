package com.aichat.sandbox.ui.screens.notes

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the docked AI-rail width coercion (P0.2 / audit A2). The editor
 * reserves exactly this width as canvas padding, so the bounds matter:
 * narrow phones must still leave a live canvas strip, tablets must not
 * sprawl.
 */
class AiSheetWidthTest {

    @Test
    fun narrowPhoneClampsToMinimum() {
        // 0.5 * 360 = 180, below the 280 floor.
        assertEquals(280.dp, aiSheetWidthFor(360.dp))
    }

    @Test
    fun midWidthUsesFraction() {
        // 0.5 * 700 = 350, inside [280, 460].
        assertEquals(350.dp, aiSheetWidthFor(700.dp))
    }

    @Test
    fun wideTabletClampsToMaximum() {
        // 0.5 * 1200 = 600, above the 460 ceiling.
        assertEquals(460.dp, aiSheetWidthFor(1200.dp))
    }
}
