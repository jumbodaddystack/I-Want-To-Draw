package com.aichat.sandbox.ui.screens.vector

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.vector.VectorPathCatalogEntry

private enum class PathFilter { ALL, STROKED, FILLED }

/**
 * Lists the catalog entries for the analyzed source version and lets the user
 * select paths for manual editing (Phase 7). Filtering is intentionally simple:
 * all / stroked / filled, plus an optional single-color filter built from the
 * colors actually present in the catalog.
 */
@Composable
fun VectorPathInspectorPanel(
    entries: List<VectorPathCatalogEntry>,
    selectedPathIds: Set<String>,
    onToggle: (String) -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf(PathFilter.ALL) }
    var colorFilter by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (entries.isEmpty()) {
            Text(
                text = "No paths to inspect. Analyze a version first.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PathFilter.entries.forEach { f ->
                FilterChip(
                    selected = filter == f,
                    onClick = { filter = f },
                    label = { Text(f.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }

        val colors = remember(entries) {
            entries.flatMap { listOfNotNull(it.fillColor, it.strokeColor) }.distinct()
        }
        if (colors.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                colors.forEach { color ->
                    FilterChip(
                        selected = colorFilter == color,
                        onClick = { colorFilter = if (colorFilter == color) null else color },
                        label = {
                            Text(color, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
                        },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
        }

        if (selectedPathIds.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${selectedPathIds.size} selected",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = onClearSelection) { Text("Clear") }
            }
        }

        val filtered = entries.filter { entry ->
            val byKind = when (filter) {
                PathFilter.ALL -> true
                PathFilter.STROKED -> entry.strokeColor != null
                PathFilter.FILLED -> entry.fillColor != null
            }
            val byColor = colorFilter?.let { it == entry.fillColor || it == entry.strokeColor } ?: true
            byKind && byColor
        }

        if (filtered.isEmpty()) {
            Text(
                text = "No paths match the current filter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        filtered.forEach { entry ->
            PathRow(
                entry = entry,
                selected = entry.id in selectedPathIds,
                onToggle = { onToggle(entry.id) },
            )
        }
    }
}

@Composable
private fun PathRow(
    entry: VectorPathCatalogEntry,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clickable { onToggle() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = entry.name?.let { "$it (${entry.id})" } ?: entry.id,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle(entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun subtitle(entry: VectorPathCatalogEntry): String {
    val parts = ArrayList<String>()
    entry.fillColor?.let { parts += "fill $it" }
    entry.strokeColor?.let { parts += "stroke $it" }
    entry.strokeWidth?.let { parts += "w=$it" }
    parts += "${entry.commandCount} cmd"
    if (entry.warnings.isNotEmpty()) parts += "${entry.warnings.size} warn"
    return parts.joinToString(" · ")
}
