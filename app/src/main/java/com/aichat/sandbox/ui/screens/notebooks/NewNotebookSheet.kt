package com.aichat.sandbox.ui.screens.notebooks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.aichat.sandbox.data.model.Notebook
import com.aichat.sandbox.data.model.NotebookPageSize

/**
 * Sub-phase 9.1 — modal for the "New notebook" flow.
 *
 * Picks title, page size (A4 / Letter / half-letter landscape), page style
 * (plain / dot / line / graph) and cover colour, then hands the params to
 * the caller which creates the notebook via [NotebooksListViewModel].
 */
@Composable
fun NewNotebookSheet(
    onCreate: (
        title: String,
        pageSize: NotebookPageSize,
        pageStyle: String,
        coverColorArgb: Int,
    ) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var pageSize by remember { mutableStateOf(NotebookPageSize.A4_PORTRAIT) }
    var pageStyle by remember { mutableStateOf(Notebook.STYLE_LINE) }
    var coverColor by remember { mutableStateOf(COVER_COLORS[0]) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New notebook") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                )

                Text(
                    text = "Page size",
                    style = MaterialTheme.typography.labelMedium,
                )
                Column {
                    NotebookPageSize.values().forEach { size ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { pageSize = size },
                        ) {
                            RadioButton(
                                selected = pageSize == size,
                                onClick = { pageSize = size },
                            )
                            Text(size.displayName)
                        }
                    }
                }

                Text(
                    text = "Page style",
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PAGE_STYLES.forEach { (key, label) ->
                        StyleChip(
                            label = label,
                            selected = pageStyle == key,
                            onClick = { pageStyle = key },
                        )
                    }
                }

                Text(
                    text = "Cover",
                    style = MaterialTheme.typography.labelMedium,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.foundation.lazy.items(COVER_COLORS) { argb ->
                        ColorDot(
                            argb = argb,
                            selected = argb == coverColor,
                            onClick = { coverColor = argb },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onCreate(title, pageSize, pageStyle, coverColor)
            }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun StyleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

@Composable
private fun ColorDot(argb: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color(argb))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

private val PAGE_STYLES = listOf(
    Notebook.STYLE_PLAIN to "Plain",
    Notebook.STYLE_DOT to "Dot",
    Notebook.STYLE_LINE to "Line",
    Notebook.STYLE_GRAPH to "Graph",
)

private val COVER_COLORS: List<Int> = listOf(
    0xFF1E88E5.toInt(),
    0xFFD81B60.toInt(),
    0xFF43A047.toInt(),
    0xFFFB8C00.toInt(),
    0xFF8E24AA.toInt(),
    0xFF5D4037.toInt(),
    0xFF455A64.toInt(),
    0xFF263238.toInt(),
)
