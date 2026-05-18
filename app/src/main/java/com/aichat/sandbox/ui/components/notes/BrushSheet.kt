package com.aichat.sandbox.ui.components.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.model.BrushPreset

/**
 * Sub-phase 6.5 — collapsible Brush sheet.
 *
 * Shows the active preset's chips at the top (tap to apply, long press =
 * future); below that a slider stack for width / opacity / taper / jitter
 * tied to the *currently active* preset. Edits are uncommitted until the
 * user saves them as a new preset — picking a different preset throws away
 * the in-flight slider state, matching how Concepts handles its brushes.
 */
@Composable
fun BrushSheet(
    presets: List<BrushPreset>,
    activePreset: BrushPreset?,
    onApplyPreset: (BrushPreset) -> Unit,
    onLiveEdit: (BrushPreset) -> Unit,
    onSaveAsPreset: (BrushPreset, String) -> Unit,
    onTextureChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (activePreset == null) return
    var saveDialogOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Presets",
                    style = MaterialTheme.typography.labelMedium,
                )
                presets
                    .filter { it.tool == activePreset.tool }
                    .forEach { preset ->
                        PresetChip(
                            preset = preset,
                            active = preset.id == activePreset.id,
                            onClick = { onApplyPreset(preset) },
                        )
                    }
                IconButton(onClick = { saveDialogOpen = true }) {
                    Icon(Icons.Filled.Save, contentDescription = "Save as preset")
                }
            }

            BrushSliders(
                preset = activePreset,
                onLiveEdit = onLiveEdit,
            )

            TextureRow(
                activeTexture = activePreset.textureId,
                onPick = onTextureChange,
            )
        }
    }

    if (saveDialogOpen) {
        SavePresetDialog(
            seedName = activePreset.name + " copy",
            onDismiss = { saveDialogOpen = false },
            onConfirm = { name ->
                onSaveAsPreset(activePreset, name)
                saveDialogOpen = false
            },
        )
    }
}

@Composable
private fun PresetChip(
    preset: BrushPreset,
    active: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = active,
        onClick = onClick,
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(preset.colorArgb))
                        .border(
                            width = 0.5.dp,
                            color = Color.Black.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(6.dp),
                        ),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(text = preset.name)
            }
        },
    )
}

@Composable
private fun BrushSliders(
    preset: BrushPreset,
    onLiveEdit: (BrushPreset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LabeledSlider(
            label = "Width",
            value = preset.baseWidthPx,
            valueRange = ToolPaletteState.WIDTH_MIN_PX..ToolPaletteState.WIDTH_MAX_PX,
            onChange = { onLiveEdit(preset.copy(baseWidthPx = it)) },
        )
        LabeledSlider(
            label = "Opacity",
            value = preset.opacity,
            valueRange = 0f..1f,
            onChange = { onLiveEdit(preset.copy(opacity = it)) },
        )
        LabeledSlider(
            label = "Taper start",
            value = preset.taperStart,
            valueRange = 0f..1f,
            onChange = { onLiveEdit(preset.copy(taperStart = it)) },
        )
        LabeledSlider(
            label = "Taper end",
            value = preset.taperEnd,
            valueRange = 0f..1f,
            onChange = { onLiveEdit(preset.copy(taperEnd = it)) },
        )
        LabeledSlider(
            label = "Jitter",
            value = preset.jitter,
            valueRange = 0f..1f,
            onChange = { onLiveEdit(preset.copy(jitter = it)) },
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(72.dp),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun TextureRow(
    activeTexture: String,
    onPick: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Texture",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(72.dp),
        )
        TextureRegistry.available().forEach { id ->
            FilterChip(
                selected = id == activeTexture,
                onClick = { onPick(id) },
                label = { Text(id.replaceFirstChar { it.uppercase() }) },
            )
        }
    }
}

@Composable
private fun SavePresetDialog(
    seedName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(seedName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as preset") },
        text = {
            Column {
                Text(
                    text = "Name",
                    style = MaterialTheme.typography.labelSmall,
                )
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name.ifBlank { seedName }) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
