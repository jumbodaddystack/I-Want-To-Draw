package com.aichat.sandbox.ui.screens.vector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * History-tab section for importing a portable project bundle (Phase 10).
 *
 * Paste the JSON produced by the Export tab's "Project bundle JSON" format and
 * import it as a brand-new local project — the current project is never
 * overwritten. Import status (including how many warnings the bundle produced)
 * is surfaced below the field.
 */
@Composable
fun VectorBundleImportPanel(
    bundleText: String,
    statusMessage: String?,
    isImporting: Boolean,
    onTextChanged: (String) -> Unit,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Paste a Vector Tune-Up project bundle JSON to import it as a new project.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedTextField(
            value = bundleText,
            onValueChange = onTextChanged,
            label = { Text("Project bundle JSON") },
            placeholder = { Text("{ \"schema\": 1, \"kind\": \"vector_tuneup_project\", … }") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            enabled = !isImporting,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 140.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onImport, enabled = !isImporting && bundleText.isNotBlank()) {
                Text("Import bundle")
            }
            TextButton(onClick = { onTextChanged("") }, enabled = !isImporting && bundleText.isNotBlank()) {
                Text("Clear")
            }
            if (isImporting) {
                CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp))
            }
        }
        statusMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
