package com.aichat.sandbox.ui.screens.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.aichat.sandbox.data.model.NoteFrame
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.notes.FrameThumbnailRenderer

/**
 * Sub-phase 8.2 — frame navigator.
 *
 * A left-edge vertical strip showing per-frame thumbnails. Tap = fly to.
 * Long-press = rename / delete menu.
 */
@Composable
fun FrameNavigator(
    frames: List<NoteFrame>,
    currentFrameId: String?,
    items: List<NoteItem>,
    thumbnailRenderer: FrameThumbnailRenderer,
    onSelect: (NoteFrame) -> Unit,
    onRename: (frameId: String, newName: String) -> Unit,
    onDelete: (frameId: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var renameTarget by remember { mutableStateOf<NoteFrame?>(null) }
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(112.dp),
        shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.fillMaxHeight()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Frames",
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = onClose, contentPadding = PaddingValues(0.dp)) {
                    Text("Close", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (frames.isEmpty()) {
                Text(
                    text = "Use the Frame tool to carve out a region.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(frames, key = { it.id }) { frame ->
                        FrameNavigatorCell(
                            frame = frame,
                            items = items,
                            isCurrent = frame.id == currentFrameId,
                            thumbnailRenderer = thumbnailRenderer,
                            onTap = { onSelect(frame) },
                            onLongPress = { renameTarget = frame },
                        )
                    }
                }
            }
        }
    }

    val target = renameTarget
    if (target != null) {
        FrameRenameDialog(
            initial = target.name,
            onRename = { name ->
                onRename(target.id, name)
                renameTarget = null
            },
            onDelete = {
                onDelete(target.id)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }
}

@Composable
private fun FrameNavigatorCell(
    frame: NoteFrame,
    items: List<NoteItem>,
    isCurrent: Boolean,
    thumbnailRenderer: FrameThumbnailRenderer,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    // Render the thumbnail off the main thread; reuses the cache so a stable
    // (frameId, hash) pair returns the same bitmap on every recomposition.
    // Keying on items.size keeps it cheap — content-hash mismatches inside
    // the renderer re-rasterise as needed.
    val bitmap by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        key1 = frame.id,
        key2 = frame.minX,
        key3 = items.size,
    ) {
        value = thumbnailRenderer.thumbnailFor(frame, items)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = if (isCurrent) 4.dp else 0.dp,
        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isCurrent) 2.dp else 0.5.dp,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            )
            .pointerInput(frame.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() },
                )
            }
    ) {
        Column(modifier = Modifier.padding(4.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = frame.name,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Text(
                text = frame.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun FrameRenameDialog(
    initial: String,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Frame") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(text) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
