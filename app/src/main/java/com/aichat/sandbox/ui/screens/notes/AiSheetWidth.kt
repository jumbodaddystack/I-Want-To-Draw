package com.aichat.sandbox.ui.screens.notes

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Width of the docked AI rail, clamped to keep usable canvas visible. */
internal fun aiSheetWidthFor(screenWidth: Dp): Dp = (screenWidth * 0.5f).coerceIn(280.dp, 460.dp)
