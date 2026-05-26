package com.aichat.sandbox.ui.screens.vector

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Stacked, chronological list of a project's versions. Each card shows lineage,
 * metrics, and per-version actions; selecting a version (or making it active)
 * sets the source the next optimize/AI operation branches from.
 */
@Composable
fun VectorVersionHistoryPanel(
    versions: List<VectorVersionUi>,
    activeVersionId: String?,
    selectedVersionId: String?,
    onSelect: (String) -> Unit,
    onMakeActive: (String) -> Unit,
    onExport: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (versions.isEmpty()) {
        Text(
            text = "No saved versions yet. Save the project or run an operation to start a history.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }
    val labelById = versions.associate { it.id to it.label }
    Column(modifier = modifier.fillMaxWidth()) {
        versions.forEach { version ->
            VectorVersionCard(
                version = version,
                parentLabel = version.parentId?.let { labelById[it] },
                isActive = version.id == activeVersionId,
                isSelected = version.id == selectedVersionId,
                onSelect = { onSelect(version.id) },
                onMakeActive = { onMakeActive(version.id) },
                onExport = { onExport(version.id) },
            )
        }
    }
}
