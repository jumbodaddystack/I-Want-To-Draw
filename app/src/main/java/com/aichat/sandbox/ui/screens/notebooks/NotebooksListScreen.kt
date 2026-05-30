package com.aichat.sandbox.ui.screens.notebooks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aichat.sandbox.data.model.Notebook
import com.aichat.sandbox.ui.components.AppScreenScaffold
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NotebooksListScreen(
    onOpenNotebook: (noteId: String) -> Unit,
    onOpenSearch: () -> Unit = {},
    viewModel: NotebooksListViewModel = hiltViewModel(),
) {
    val notebooks by viewModel.notebooks.collectAsState()
    val pendingNav by viewModel.pendingNavigation.collectAsState()
    var newSheetOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Notebook?>(null) }
    var deleteTarget by remember { mutableStateOf<Notebook?>(null) }

    LaunchedEffect(pendingNav) {
        val target = pendingNav ?: return@LaunchedEffect
        viewModel.consumeNavigation()
        onOpenNotebook(target)
    }

    AppScreenScaffold(
        title = "Notebooks",
        actions = {
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Filled.Search, contentDescription = "Search notebooks")
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { newSheetOpen = true },
                text = { Text("New notebook") },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            )
        },
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            if (notebooks.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoStories,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "No notebooks yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                    Text(
                        "Tap + New notebook to get started",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(notebooks, key = { it.notebook.id }) { card ->
                        NotebookCover(
                            card = card,
                            onOpen = {
                                // One note per notebook (9.1 contract). When
                                // the FK look-up is still in flight on a
                                // freshly created notebook, the tap is a no-op
                                // until the card recomposes with a noteId.
                                card.noteId?.let(onOpenNotebook)
                            },
                            onLongPress = { renameTarget = card.notebook },
                        )
                    }
                }
            }
        }
    }

    if (newSheetOpen) {
        NewNotebookSheet(
            onCreate = { title, pageSize, pageStyle, coverColor ->
                viewModel.createNotebook(title, pageSize, pageStyle, coverColor)
                newSheetOpen = false
            },
            onDismiss = { newSheetOpen = false },
        )
    }

    val rt = renameTarget
    if (rt != null) {
        RenameNotebookDialog(
            initial = rt.title,
            onRename = { newTitle ->
                viewModel.rename(rt, newTitle)
                renameTarget = null
            },
            onDelete = {
                renameTarget = null
                deleteTarget = rt
            },
            onDismiss = { renameTarget = null },
        )
    }

    val dt = deleteTarget
    if (dt != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete notebook?") },
            text = {
                Text("\"${dt.title}\" and all its pages will be permanently removed.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(dt)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookCover(
    card: NotebooksListViewModel.NotebookCard,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        color = Color(card.notebook.coverColorArgb),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.AutoStories,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = card.notebook.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${card.pageCount} pages · ${dateFormat.format(Date(card.notebook.updatedAt))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }
}

@Composable
private fun RenameNotebookDialog(
    initial: String,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Notebook") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Title") },
            )
        },
        confirmButton = {
            Button(onClick = { onRename(text) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
