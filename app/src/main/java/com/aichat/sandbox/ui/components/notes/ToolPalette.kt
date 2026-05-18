package com.aichat.sandbox.ui.components.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Pentagon
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Highlight
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Bottom tool palette (sub-phase 1.6).
 *
 * Layout: tool row → divider → color swatch row + width slider. Disabled
 * tools (lasso / text) render greyed-out so the user can see what's coming.
 */
@Composable
fun ToolPalette(
    state: ToolPaletteState,
    modifier: Modifier = Modifier,
    onPickCustomColor: () -> Unit = {},
    snapMask: Int = Snap.MASK_ANGLE or Snap.MASK_ENDPOINT,
    onToggleSnap: (Int) -> Unit = { },
) {
    val selected = state.selected
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolRow(state = state)

            HorizontalDivider(thickness = 0.5.dp)

            if (selected.isInk) {
                InkConfigRow(state = state, onPickCustomColor = onPickCustomColor)
            } else if (selected.isEraser) {
                EraserConfigRow(state = state)
            } else if (selected.isLasso) {
                LassoHintRow()
            } else if (selected.isText) {
                TextHintRow()
            } else if (selected.isShape) {
                // Phase 6.2 — shape tools reuse the active ink color + width.
                InkConfigRow(state = state, onPickCustomColor = onPickCustomColor)
                SnapChipRow(snapMask = snapMask, onToggle = onToggleSnap)
            } else if (selected.isFrame) {
                FrameHintRow()
            }
        }
    }
}

@Composable
private fun ToolRow(state: ToolPaletteState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tool.entries.forEach { tool ->
            ToolChip(
                tool = tool,
                selected = state.selected == tool,
                enabled = tool.enabledInPalette,
                onSelect = { state.select(tool) },
            )
        }
    }
}

@Composable
private fun ToolChip(
    tool: Tool,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onSelect,
        label = { Text(tool.displayName) },
        leadingIcon = {
            Icon(
                imageVector = tool.icon(),
                contentDescription = tool.displayName,
            )
        },
        colors = FilterChipDefaults.filterChipColors(),
    )
}

private fun Tool.icon(): ImageVector = when (this) {
    Tool.PEN -> Icons.Filled.Create
    Tool.HIGHLIGHTER -> Icons.Filled.Brush
    Tool.PENCIL -> Icons.Filled.Edit
    Tool.ERASER_STROKE -> Icons.Outlined.RadioButtonUnchecked
    Tool.ERASER_AREA -> Icons.Outlined.RadioButtonUnchecked
    Tool.LASSO -> Icons.Outlined.Highlight
    Tool.TEXT -> Icons.Filled.TextFields
    Tool.LINE -> Icons.Filled.HorizontalRule
    Tool.RECT -> Icons.Filled.CheckBoxOutlineBlank
    Tool.ELLIPSE -> Icons.Outlined.Circle
    Tool.ARROW -> Icons.AutoMirrored.Filled.TrendingFlat
    Tool.POLYGON -> Icons.Filled.Pentagon
    Tool.FRAME -> Icons.Filled.CropFree
}

@Composable
private fun InkConfigRow(
    state: ToolPaletteState,
    onPickCustomColor: () -> Unit,
) {
    val activeInk = state.lastInkTool
    val activeColor = state.colorFor(activeInk)
    val activeWidth = state.widthFor(activeInk)
    // Whether `activeColor` matches one of the default swatches drives the
    // highlight: a custom hue lights up the "+" tile instead, so the user
    // can see "my picked colour" reflected somewhere on the row.
    val isCustomColor = activeColor !in ToolPaletteState.DEFAULT_COLOR_SWATCHES

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolPaletteState.DEFAULT_COLOR_SWATCHES.forEach { swatch ->
                ColorSwatch(
                    colorArgb = swatch,
                    selected = swatch == activeColor,
                    onSelect = { state.setColor(activeInk, swatch) },
                    onLongPress = onPickCustomColor,
                )
            }
            // Phase 5.3 — "+" tile opens the full HSL picker. Long-pressing
            // any existing swatch does the same thing, so two affordances
            // converge on one sheet.
            CustomColorTile(
                showingCustom = isCustomColor,
                customColorArgb = if (isCustomColor) activeColor else null,
                onClick = onPickCustomColor,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Width",
                style = MaterialTheme.typography.labelMedium,
            )
            Slider(
                value = activeWidth,
                onValueChange = { state.setWidth(activeInk, it) },
                valueRange = ToolPaletteState.WIDTH_MIN_PX..ToolPaletteState.WIDTH_MAX_PX,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun EraserConfigRow(state: ToolPaletteState) {
    if (state.selected != Tool.ERASER_AREA) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Stroke eraser — removes whole strokes you touch.",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Area",
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = state.areaEraserRadiusPx,
            onValueChange = { state.setAreaEraserRadius(it) },
            valueRange = ToolPaletteState.ERASER_RADIUS_MIN_PX..ToolPaletteState.ERASER_RADIUS_MAX_PX,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LassoHintRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Lasso — draw a loop to select strokes.",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun FrameHintRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Frame — drag a rectangle to define an exportable region.",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun TextHintRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Text — tap empty space to add a note, tap existing text to edit.",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * Phase 6.3 — three-chip snap toggle row. Each chip lights up when its
 * mask bit is set; tap flips that single bit.
 */
@Composable
private fun SnapChipRow(snapMask: Int, onToggle: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Snap",
            style = MaterialTheme.typography.labelMedium,
        )
        SnapToggleChip("15°", snapMask and Snap.MASK_ANGLE != 0) { onToggle(Snap.MASK_ANGLE) }
        SnapToggleChip("Grid", snapMask and Snap.MASK_GRID != 0) { onToggle(Snap.MASK_GRID) }
        SnapToggleChip("Ends", snapMask and Snap.MASK_ENDPOINT != 0) { onToggle(Snap.MASK_ENDPOINT) }
    }
}

@Composable
private fun SnapToggleChip(label: String, on: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = on,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun ColorSwatch(
    colorArgb: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    val color = Color(colorArgb)
    val ring = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 2.dp else 0.5.dp,
                color = if (selected) ring else Color.Black.copy(alpha = 0.25f),
                shape = CircleShape,
            )
            .pointerInput(colorArgb) {
                detectTapGestures(
                    onTap = { onSelect() },
                    onLongPress = { onLongPress() },
                )
            }
    )
}

@Composable
private fun CustomColorTile(
    showingCustom: Boolean,
    customColorArgb: Int?,
    onClick: () -> Unit,
) {
    val ring = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(
                if (showingCustom && customColorArgb != null) Color(customColorArgb)
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .border(
                width = if (showingCustom) 2.dp else 0.5.dp,
                color = if (showingCustom) ring else Color.Black.copy(alpha = 0.25f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = "Custom colour",
            tint = if (showingCustom) Color.White
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}
