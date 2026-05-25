package com.aichat.sandbox.ui.screens.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun NotesListScreen(
    onNoteClick: (String) -> Unit,
    onNewNote: () -> Unit,
    onNewIcon: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    viewModel: NotesListViewModel = hiltViewModel(),
) {
    val notes by viewModel.notes.collectAsState()
    var pendingDelete by remember { mutableStateOf<Note?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onNewNote),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "New note",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "New note",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Row(
                modifier = Modifier
                    .clickable(onClick = onNewIcon)
                    .padding(end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Draw,
                    contentDescription = "New icon",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "New icon",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onOpenSearch) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = "Search notes",
                )
            }
        }

        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

        if (notes.isEmpty()) {
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
                        text = "No notes yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap + New note to get started",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
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

private val THUMBNAIL_SIZE = 56.dp
