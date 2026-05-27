package com.aichat.sandbox.ui.screens.vector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.model.VectorTuneupMode

/**
 * History-tab actions for the selected version (Phase 10): duplicate it as a new
 * child, or delete it when it is a safe leaf. Delete is disabled for the original
 * version and when nothing is selected; the original and versions with children
 * are also protected by the repository, which surfaces a friendly message.
 */
@Composable
fun VectorVersionGraphActionsPanel(
    selected: VectorVersionUi?,
    enabled: Boolean,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (selected == null) {
            Text(
                text = "Select a version above to duplicate or delete it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        Text(
            text = "Selected: ${selected.label}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        val isOriginal = selected.mode == VectorTuneupMode.ORIGINAL
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onDuplicate, enabled = enabled) {
                Text("Duplicate selected")
            }
            OutlinedButton(onClick = onDelete, enabled = enabled && !isOriginal) {
                Text("Delete selected")
            }
        }
        if (isOriginal) {
            Text(
                text = "The original version cannot be deleted.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
