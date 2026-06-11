package com.aichat.sandbox.ui.screens.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.Notebook
import com.aichat.sandbox.ui.components.AppScreenScaffold
import com.aichat.sandbox.ui.screens.notebooks.NewNotebookSheet
import com.aichat.sandbox.ui.screens.notebooks.NotebooksListViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Combined Notes + Notebooks destination.
 *
 * Notes and notebooks share one home (the user asked for them to be "the same
 * screen"): notebook covers ride a horizontal rail at the top, loose notes fill
 * the list below, and a single "New" action offers both. This freed the
 * bottom-nav slot that the Vector Tune-Up workspace reclaims.
 *
 * Two view models back the screen — [NotesListViewModel] for loose notes and
 * [NotebooksListViewModel] for notebook covers + create/rename/delete — so each
 * keeps its own repository wiring without a third aggregating type.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NotesListScreen(
    onNoteClick: (String) -> Unit,
    onNewNote: () -> Unit,
    onNewNoteFromTemplate: (templateId: String) -> Unit = {},
    onOpenNotebook: (noteId: String) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    viewModel: NotesListViewModel = hiltViewModel(),
    notebooksViewModel: NotebooksListViewModel = hiltViewModel(),
) {
    val notes by viewModel.notes.collectAsState()
    val notebooks by notebooksViewModel.notebooks.collectAsState()
    val pendingNav by notebooksViewModel.pendingNavigation.collectAsState()

    var pendingDelete by remember { mutableStateOf<Note?>(null) }
    var createMenuOpen by remember { mutableStateOf(false) }
    var newNotebookOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Notebook?>(null) }
    var deleteNotebookTarget by remember { mutableStateOf<Notebook?>(null) }

    // Open a freshly created notebook's underlying note once the FK look-up
    // resolves (mirrors the old standalone Notebooks screen contract).
    LaunchedEffect(pendingNav) {
        val target = pendingNav ?: return@LaunchedEffect
        notebooksViewModel.consumeNavigation()
        onOpenNotebook(target)
    }

    AppScreenScaffold(
        title = "Notes",
        actions = {
            IconButton(onClick = onOpenSearch) {
                Icon(Icons.Filled.Search, contentDescription = "Search notes")
            }
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { createMenuOpen = true },
                    text = { Text("New") },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                )
                DropdownMenu(
                    expanded = createMenuOpen,
                    onDismissRequest = { createMenuOpen = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("New note") },
                        leadingIcon = { Icon(Icons.Filled.EditNote, contentDescription = null) },
                        onClick = {
                            createMenuOpen = false
                            onNewNote()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("New notebook") },
                        leadingIcon = { Icon(Icons.Filled.AutoStories, contentDescription = null) },
                        onClick = {
                            createMenuOpen = false
                            newNotebookOpen = true
                        },
                    )
                    // Sub-phase 11.4 — starter templates. Each opens a fresh
                    // note pre-seeded with the template's frames + content.
                    HorizontalDivider()
                    Text(
                        text = "From template",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    com.aichat.sandbox.data.notes.NoteTemplate.entries.forEach { template ->
                        DropdownMenuItem(
                            text = { Text(template.displayName) },
                            leadingIcon = {
                                Icon(Icons.Filled.Dashboard, contentDescription = null)
                            },
                            onClick = {
                                createMenuOpen = false
                                onNewNoteFromTemplate(template.id)
                            },
                        )
                    }
                }
            }
        },
    ) {
        if (notes.isEmpty() && notebooks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.EditNote,
                        contentDescription = "No notes",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Nothing here yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap + New to add a note or notebook",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp),
            ) {
                if (notebooks.isNotEmpty()) {
                    item(key = "notebooks_header") { SectionHeader("Notebooks") }
                    item(key = "notebooks_rail") {
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(notebooks, key = { it.notebook.id }) { card ->
                                NotebookCover(
                                    card = card,
                                    onOpen = { card.noteId?.let(onOpenNotebook) },
                                    onLongPress = { renameTarget = card.notebook },
                                )
                            }
                        }
                    }
                }
                if (notes.isNotEmpty()) {
                    // Only label the Notes section when notebooks share the
                    // screen; otherwise the screen title already says "Notes".
                    if (notebooks.isNotEmpty()) {
                        item(key = "notes_header") { SectionHeader("Notes") }
                    }
                    items(notes, key = { it.id }) { note ->
                        NoteListItem(
                            note = note,
                            onClick = { onNoteClick(note.id) },
                            onLongClick = { pendingDelete = note },
                        )
                    }
                }
            }
        }
    }

    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete note?") },
            text = {
                Text(
                    text = "\"${target.title.ifBlank { "Untitled" }}\" will be permanently removed.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    if (newNotebookOpen) {
        NewNotebookSheet(
            onCreate = { title, pageSize, pageStyle, coverColor ->
                notebooksViewModel.createNotebook(title, pageSize, pageStyle, coverColor)
                newNotebookOpen = false
            },
            onDismiss = { newNotebookOpen = false },
        )
    }

    val rt = renameTarget
    if (rt != null) {
        RenameNotebookDialog(
            initial = rt.title,
            onRename = { newTitle ->
                notebooksViewModel.rename(rt, newTitle)
                renameTarget = null
            },
            onDelete = {
                renameTarget = null
                deleteNotebookTarget = rt
            },
            onDismiss = { renameTarget = null },
        )
    }

    val dt = deleteNotebookTarget
    if (dt != null) {
        AlertDialog(
            onDismissRequest = { deleteNotebookTarget = null },
            title = { Text("Delete notebook?") },
            text = {
                Text("\"${dt.title}\" and all its pages will be permanently removed.")
            },
            confirmButton = {
                TextButton(onClick = {
                    notebooksViewModel.delete(dt)
                    deleteNotebookTarget = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteNotebookTarget = null }) { Text("Cancel") }
            },
        )
    }
}

/** All-caps section divider for the combined list. */
@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteListItem(
    note: Note,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val isToday = remember(note.updatedAt) {
        val noteCal = Calendar.getInstance().apply { timeInMillis = note.updatedAt }
        val todayCal = Calendar.getInstance()
        noteCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
            noteCal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = MaterialTheme.colorScheme.background
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NoteThumbnail(note = note)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = note.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (isToday) timeFormat.format(Date(note.updatedAt))
                           else dateFormat.format(Date(note.updatedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NoteThumbnail(note: Note) {
    val context = LocalContext.current
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val placeholderBg = MaterialTheme.colorScheme.surfaceVariant
    val path = note.thumbnailPath
    Box(
        modifier = Modifier
            .size(THUMBNAIL_SIZE)
            .clip(RoundedCornerShape(6.dp))
            .background(placeholderBg),
        contentAlignment = Alignment.Center,
    ) {
        if (path != null) {
            // updatedAt is folded into Coil's cache keys so saves after the
            // first thumbnail show the new bitmap instead of the cached one.
            val request = remember(path, note.updatedAt) {
                val cacheKey = "$path:${note.updatedAt}"
                ImageRequest.Builder(context)
                    .data(File(path))
                    .memoryCacheKey(cacheKey)
                    .diskCacheKey(cacheKey)
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Default.EditNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(28.dp),
            )
        }
        androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = 1.dp.toPx()
            val r = 6.dp.toPx()
            drawRoundRect(
                color = borderColor,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
            )
        }
    }
}

/**
 * Notebook cover tile for the horizontal rail. A fixed width keeps the 0.75
 * portrait aspect that the standalone grid used, so covers read as little
 * books regardless of how many sit on the rail.
 */
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
            .width(NOTEBOOK_COVER_WIDTH)
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        color = Color(card.notebook.coverColorArgb),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.AutoStories,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = card.notebook.title,
                style = MaterialTheme.typography.titleSmall,
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

private val THUMBNAIL_SIZE = 56.dp
private val NOTEBOOK_COVER_WIDTH = 120.dp
