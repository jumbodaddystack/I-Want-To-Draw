package com.aichat.sandbox.ui.components.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Sub-phase 5.4 zoom indicator + popover.
 *
 * Renders an [AssistChip] in the editor TopAppBar that shows the current
 * scale as a percentage. Tapping the chip opens a small menu with three
 * actions:
 *
 *  - **Fit content** — `ViewportController.fitToContent` over the supplied
 *    [contentBoundsProvider]. No-ops when bounds are empty.
 *  - **100%** — reset to 1.0× without losing the user's current viewport
 *    centre.
 *  - **Center** — pan the current content bounds back into view without
 *    changing scale.
 *
 * The chip recomposes from the controller's [ViewportController.scale]
 * Compose state directly, so live pinch-zoom updates the percentage on the
 * next frame without any extra plumbing.
 */
@Composable
fun ZoomChrome(
    viewport: ViewportController?,
    canvasSize: IntSize,
    contentBoundsProvider: () -> FloatArray?,
    modifier: Modifier = Modifier,
) {
    if (viewport == null) return

    var menuOpen by remember { mutableStateOf(false) }
    val scale = viewport.scale
    val canvasFloatSize = remember(canvasSize) {
        floatArrayOf(canvasSize.width.toFloat(), canvasSize.height.toFloat())
    }

    Box(modifier = modifier) {
        AssistChip(
            onClick = { menuOpen = true },
            label = {
                val pct = (scale * 100f).toInt()
                Text("$pct%")
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.ZoomOutMap,
                    contentDescription = "Zoom",
                    modifier = Modifier.padding(end = 2.dp),
                )
            },
            colors = AssistChipDefaults.assistChipColors(),
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text("Fit content") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.FitScreen,
                        contentDescription = null,
                    )
                },
                onClick = {
                    val bounds = contentBoundsProvider()
                    if (bounds != null) {
                        viewport.fitToContent(bounds, canvasFloatSize)
                    }
                    menuOpen = false
                },
            )
            DropdownMenuItem(
                text = { Text("100%") },
                onClick = {
                    viewport.resetToOneHundred(canvasFloatSize)
                    menuOpen = false
                },
            )
            DropdownMenuItem(
                text = { Text("Center") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.CenterFocusStrong,
                        contentDescription = null,
                    )
                },
                onClick = {
                    val bounds = contentBoundsProvider()
                    if (bounds != null) {
                        viewport.centerOnContent(bounds, canvasFloatSize)
                    }
                    menuOpen = false
                },
            )
        }
    }
}
