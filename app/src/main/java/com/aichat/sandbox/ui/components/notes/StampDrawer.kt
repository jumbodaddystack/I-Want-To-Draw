package com.aichat.sandbox.ui.components.notes

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.model.Stamp

/**
 * Sub-phase 8.3 — bottom-sheet object library. Phase 17.5 follow-on adds a
 * search field + tag-chip filter (mirroring the Icons gallery) and tag editing
 * in the manage dialog.
 *
 * Three-column grid of saved stamps. Tap → insert at viewport centre.
 * Long-press → rename / delete / tags dialog.
 */
@Composable
fun StampDrawer(
    stamps: List<Stamp>,
    onInsert: (stampId: String) -> Unit,
    onRename: (stampId: String, newName: String) -> Unit,
    onDelete: (stampId: String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // 17.5 follow-on — tags. Empty defaults keep older call sites compiling.
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    tagCounts: List<StampTagChip> = emptyList(),
    activeTag: String? = null,
    onTagClick: (String) -> Unit = {},
    tagsByStamp: Map<String, List<String>> = emptyMap(),
    onSetTags: (stampId: String, rawInput: String) -> Unit = { _, _ -> },
    totalStampCount: Int = stamps.size,
) {
    var manageTarget by remember { mutableStateOf<Stamp?>(null) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp, max = 420.dp),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 6.dp,
        shadowElevation = 12.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Stamps",
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
            if (totalStampCount == 0) {
                Text(
                    text = "Lasso a selection then tap \"Save as stamp\" to fill the library.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                // Search field (name + tags).
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("Search stamps") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                )
                // Tag-chip filter row.
                if (tagCounts.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(tagCounts, key = { it.tag }) { chip ->
                            FilterChip(
                                selected = chip.tag == activeTag,
                                onClick = { onTagClick(chip.tag) },
                                label = { Text("${chip.tag} (${chip.count})") },
                            )
                        }
                    }
                }
                if (stamps.isEmpty()) {
                    Text(
                        text = "No stamps match.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(stamps, key = { it.id }) { stamp ->
                            StampCell(
                                stamp = stamp,
                                onTap = { onInsert(stamp.id) },
                                onLongPress = { manageTarget = stamp },
                            )
                        }
                    }
                }
            }
        }
    }
    val target = manageTarget
    if (target != null) {
        StampManageDialog(
            initial = target.name,
            initialTags = tagsByStamp[target.id].orEmpty(),
            onRename = { name, tags ->
                onRename(target.id, name)
                onSetTags(target.id, tags)
                manageTarget = null
            },
            onDelete = {
                onDelete(target.id)
                manageTarget = null
            },
            onDismiss = { manageTarget = null },
        )
    }
}

/** One stamp-library filter chip: a tag and how many stamps carry it. */
data class StampTagChip(val tag: String, val count: Int)

@Composable
private fun StampCell(
    stamp: Stamp,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val bitmap = remember(stamp.thumbnailPath) {
        BitmapFactory.decodeFile(stamp.thumbnailPath)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(stamp.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() },
                )
            }
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stamp.name,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Text(
                text = stamp.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun StampManageDialog(
    initial: String,
    initialTags: List<String>,
    onRename: (name: String, rawTags: String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var tagText by remember { mutableStateOf(initialTags.joinToString(", ")) }
    var confirmingDelete by remember { mutableStateOf(false) }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete stamp?") },
            text = { Text("\"${initial.ifBlank { "Stamp" }}\" will be removed from your library. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = onDelete) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stamp") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Name") },
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = tagText,
                    onValueChange = { tagText = it },
                    singleLine = true,
                    label = { Text("Tags (comma-separated)") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(text, tagText) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmingDelete = true }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * Sub-phase 8.3 — "Save as stamp…" dialog shown from the selection menu.
 * Captures a name and emits the confirm callback. Cancel just dismisses.
 */
@Composable
fun SaveStampDialog(
    onConfirm: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save as stamp") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text("Name (optional)") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.ifBlank { "Stamp" }) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
