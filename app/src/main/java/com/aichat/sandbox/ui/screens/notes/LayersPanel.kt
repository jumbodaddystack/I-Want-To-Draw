package com.aichat.sandbox.ui.screens.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.model.NoteLayer

/**
 * Sub-phase 6.4 — slide-in layers panel.
 *
 * Layers are listed top-to-bottom in render order *as seen by the user*:
 * the layer with the **highest** ordinal renders on top, so it sits at the
 * top of the list. Each row exposes visibility / lock toggles, an opacity
 * slider, reorder arrows, and delete. New layers are added at the top.
 *
 * The panel docks to the right edge in [NoteEditorScreen] alongside the AI
 * side sheet; the two share screen real estate via a small tab switcher in
 * the parent.
 */
@Composable
fun LayersPanel(
    layers: List<NoteLayer>,
    activeLayerId: String?,
    onAddLayer: () -> Unit,
    onSelectLayer: (String) -> Unit,
    onToggleVisible: (NoteLayer) -> Unit,
    onToggleLocked: (NoteLayer) -> Unit,
    onOpacityChange: (NoteLayer, Int) -> Unit,
    onMoveUp: (NoteLayer) -> Unit,
    onMoveDown: (NoteLayer) -> Unit,
    onDelete: (NoteLayer) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Top-of-list = highest ordinal = renders on top.
    val sorted = layers.sortedByDescending { it.ordinal }
    // Studio Bench: the layers panel is a dark "instrument" surface that
    // orbits the white artboard. Sharp inner edge (no rounded card), hairline
    // border, accent reserved for the active layer.
    val studio = com.aichat.sandbox.ui.theme.studio.StudioDarkColors
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 320.dp)
            .border(
                width = 1.dp,
                color = studio.hairline,
                shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp),
            ),
        color = studio.surfaceRail,
        contentColor = studio.inkDefault,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "LAYERS",
                    style = com.aichat.sandbox.ui.theme.studio.StudioTypographyDefault.section,
                    color = studio.inkMuted,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onAddLayer) {
                    Icon(Icons.Filled.Add, contentDescription = "Add layer", tint = studio.inkDefault)
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = studio.inkDefault)
                }
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(sorted, key = { it.id }) { layer ->
                    LayerRow(
                        layer = layer,
                        isActive = layer.id == activeLayerId,
                        onSelect = { onSelectLayer(layer.id) },
                        onToggleVisible = { onToggleVisible(layer) },
                        onToggleLocked = { onToggleLocked(layer) },
                        onOpacityChange = { onOpacityChange(layer, it) },
                        onMoveUp = { onMoveUp(layer) },
                        onMoveDown = { onMoveDown(layer) },
                        onDelete = { onDelete(layer) },
                        canDelete = layers.size > 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun LayerRow(
    layer: NoteLayer,
    isActive: Boolean,
    onSelect: () -> Unit,
    onToggleVisible: () -> Unit,
    onToggleLocked: () -> Unit,
    onOpacityChange: (Int) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    canDelete: Boolean,
) {
    val studio = com.aichat.sandbox.ui.theme.studio.StudioDarkColors
    val bg = if (isActive) studio.accentGhost else studio.canvasSunken
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(
                width = if (isActive) 1.dp else 0.5.dp,
                color = if (isActive) studio.accentSignature else studio.hairline,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleVisible, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (layer.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                        contentDescription = if (layer.visible) "Hide layer" else "Show layer",
                    )
                }
                IconButton(onClick = onToggleLocked, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (layer.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = if (layer.locked) "Unlock layer" else "Lock layer",
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = layer.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = studio.inkStrong,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
                IconButton(
                    onClick = onSelect,
                    enabled = !isActive,
                    modifier = Modifier.size(32.dp),
                ) {
                    Text(
                        text = if (isActive) "•" else " ",
                        style = MaterialTheme.typography.bodyLarge,
                        color = studio.accentSignature,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Opacity", style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.width(8.dp))
                Slider(
                    value = layer.opacityPercent.toFloat(),
                    onValueChange = { onOpacityChange(it.toInt()) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                )
                Text("${layer.opacityPercent}%", style = MaterialTheme.typography.labelSmall)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
                }
                IconButton(onClick = onMoveDown, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
                }
                IconButton(
                    onClick = onDelete,
                    enabled = canDelete,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete layer")
                }
            }
        }
    }
}
