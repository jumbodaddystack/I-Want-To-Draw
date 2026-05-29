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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.model.BrushPreset
import com.aichat.sandbox.data.notes.FavoriteSlot

/**
 * Sub-phase 8.4 — favorites bar.
 *
 * Horizontal row of six slots above the bottom tool palette. Tap = apply
 * the preset; long-press = "Replace with current brush" / "Clear" menu.
 * Empty slots render as outlined "+" tiles and are tappable to assign
 * via the long-press menu (same path as filled slots).
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
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
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
    Box {
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
                .border(
                    width = if (isActive) 2.dp else 1.dp,
                    color = ring,
                    shape = CircleShape,
                )
                .pointerInput(slot.index, preset?.id) {
                    detectTapGestures(
                        onTap = {
                            if (preset != null) onApply()
                            else menuExpanded = true
                        },
                        onLongPress = { menuExpanded = true },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            if (preset == null) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Empty slot",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
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
