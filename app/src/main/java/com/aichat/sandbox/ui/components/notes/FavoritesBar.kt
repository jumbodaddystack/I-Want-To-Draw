package com.aichat.sandbox.ui.components.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.model.BrushPreset
import com.aichat.sandbox.data.notes.FavoriteSlot

/**
 * Sub-phase 8.4 — favorites bar.
 *
 * Horizontal row of six slots above the bottom tool palette. Tap a filled
 * slot to apply its preset; tap an empty slot to save the current brush into
 * it directly. Long-press opens the "Replace with current brush" / "Clear"
 * menu. Filled slots wear their preset's tool glyph on the colour dot so two
 * presets of the same colour stay distinguishable (and the row doesn't read
 * as a second swatch row).
 */
@Composable
fun FavoritesBar(
    slots: List<FavoriteSlot>,
    presets: List<BrushPreset>,
    activePresetId: String?,
    onApply: (slotIndex: Int) -> Unit,
    onAssignActive: (slotIndex: Int) -> Unit,
    onClear: (slotIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presetsById = remember(presets) { presets.associateBy { it.id } }
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Favs",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            slots.forEach { slot ->
                FavoritesSlotTile(
                    slot = slot,
                    preset = slot.brushPresetId?.let { presetsById[it] },
                    isActive = slot.brushPresetId != null && slot.brushPresetId == activePresetId,
                    onApply = { onApply(slot.index) },
                    onAssignActive = { onAssignActive(slot.index) },
                    onClear = { onClear(slot.index) },
                )
            }
        }
    }
}

@Composable
private fun FavoritesSlotTile(
    slot: FavoriteSlot,
    preset: BrushPreset?,
    isActive: Boolean,
    onApply: () -> Unit,
    onAssignActive: () -> Unit,
    onClear: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val ring = if (isActive) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outline
    // Ring thickness hints at the preset's stroke width (active slots keep
    // the uniform 2 dp primary ring so "applied" stays unambiguous).
    val ringWidth = when {
        isActive -> 2.dp
        preset != null -> (1f + (preset.baseWidthPx / 10f) * 2f).dp
        else -> 1.dp
    }
    Box {
        // 44 dp gesture target around the 30 dp visual — the bare tiles were
        // well under the touch-target minimum.
        Box(
            modifier = Modifier
                .size(44.dp)
                .pointerInput(slot.index, preset?.id) {
                    detectTapGestures(
                        onTap = {
                            // Empty slot: a "+" that asks twice is just
                            // friction — assign the current brush directly.
                            if (preset != null) onApply()
                            else onAssignActive()
                        },
                        onLongPress = { menuExpanded = true },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            preset != null -> Color(preset.colorArgb)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    .border(width = ringWidth, color = ring, shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (preset == null) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Save current brush to slot",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    Icon(
                        imageVector = toolGlyph(preset.tool),
                        contentDescription = preset.name,
                        // Contrast against the preset colour, not the theme.
                        tint = if (Color(preset.colorArgb).luminance() > 0.5f) {
                            Color.Black.copy(alpha = 0.65f)
                        } else {
                            Color.White.copy(alpha = 0.9f)
                        },
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Replace with current brush") },
                onClick = {
                    onAssignActive()
                    menuExpanded = false
                },
            )
            if (preset != null) {
                DropdownMenuItem(
                    text = { Text("Clear") },
                    onClick = {
                        onClear()
                        menuExpanded = false
                    },
                )
            }
        }
    }
}

/** Glyph for a preset's tool id — mirrors the tool row's icon set. */
private fun toolGlyph(toolId: String): ImageVector = when (toolId) {
    "highlighter" -> Icons.Filled.BorderColor
    "pencil" -> Icons.Filled.Edit
    else -> Icons.Filled.Draw
}
