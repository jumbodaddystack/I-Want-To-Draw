package com.aichat.sandbox.ui.components.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Edit
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
                InkConfigRow(state = state)
            } else if (selected.isEraser) {
                EraserConfigRow(state = state)
            } else if (selected.isLasso) {
                LassoHintRow()
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
    Tool.TEXT -> Icons.Outlined.RadioButtonUnchecked
}

@Composable
private fun InkConfigRow(state: ToolPaletteState) {
    val activeInk = state.lastInkTool
    val activeColor = state.colorFor(activeInk)
    val activeWidth = state.widthFor(activeInk)

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
                )
            }
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
private fun ColorSwatch(
    colorArgb: Int,
    selected: Boolean,
    onSelect: () -> Unit,
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
            .clickable(onClick = onSelect)
    )
}
