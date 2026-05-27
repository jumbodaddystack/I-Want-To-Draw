package com.aichat.sandbox.ui.screens.vector

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A single "import from a file" action backed by the system document picker
 * (Phase 11). Keeps the paste-first flow intact — this is an additional, optional
 * path. The caller receives the picked content [Uri] and reads it through the
 * ViewModel's bounded file reader; this composable owns no IO itself.
 *
 * @param buttonLabel Action label (e.g. "Import file", "Import bundle file").
 * @param mimeTypes MIME types offered to the picker.
 * @param onFilePicked Invoked with the chosen URI (no-op if the user cancels).
 */
@Composable
fun VectorFileImportPanel(
    buttonLabel: String,
    helpText: String,
    mimeTypes: Array<String>,
    onFilePicked: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onFilePicked(uri)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = helpText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        OutlinedButton(onClick = { picker.launch(mimeTypes) }) {
            Icon(
                imageVector = Icons.Filled.FileOpen,
                contentDescription = null, // adjacent text already labels the button
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(buttonLabel)
        }
    }
}

/** MIME types accepted for a vector file import (Android XML / SVG / bundle JSON). */
val VECTOR_IMPORT_MIME_TYPES: Array<String> = arrayOf(
    "text/xml",
    "application/xml",
    "image/svg+xml",
    "application/json",
    "text/plain",
)

/** MIME types accepted for a project bundle file import. */
val BUNDLE_IMPORT_MIME_TYPES: Array<String> = arrayOf(
    "application/json",
    "text/plain",
)
