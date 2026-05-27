package com.aichat.sandbox.ui.screens.vector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.vector.VectorImportFormat

/**
 * Input section of the workspace: a multiline XML field plus paste-sample,
 * parse, and clear actions. Parsing is always explicit (button), never on every
 * keystroke. Phase 9: accepts Android VectorDrawable XML or SVG and shows the
 * sniffed format below the field.
 */
@Composable
fun VectorXmlInputPanel(
    inputXml: String,
    detectedFormat: VectorImportFormat,
    onXmlChanged: (String) -> Unit,
    onParse: () -> Unit,
    onPasteSample: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Paste Android VectorDrawable XML or SVG and parse it to see " +
                "metrics and warnings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        OutlinedTextField(
            value = inputXml,
            onValueChange = onXmlChanged,
            label = { Text("Android VectorDrawable XML or SVG") },
            placeholder = { Text("<vector …> … </vector>  or  <svg …> … </svg>") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp),
        )
        if (inputXml.isNotBlank()) {
            Text(
                text = "Detected: ${detectedFormatLabel(detectedFormat)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (detectedFormat == VectorImportFormat.SVG) {
                Text(
                    text = "SVG will be converted to Android VectorDrawable internally so " +
                        "all tune-up tools keep working.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (detectedFormat == VectorImportFormat.PROJECT_BUNDLE) {
                Text(
                    text = "This is a project bundle. Use History → Import project bundle " +
                        "to load it as a new project.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onParse, enabled = inputXml.isNotBlank()) {
                Text("Parse")
            }
            OutlinedButton(onClick = onPasteSample) {
                Text("Paste sample")
            }
            TextButton(onClick = onClear, enabled = inputXml.isNotBlank()) {
                Text("Clear")
            }
        }
    }
}

private fun detectedFormatLabel(format: VectorImportFormat): String = when (format) {
    VectorImportFormat.ANDROID_VECTOR -> "Android VectorDrawable"
    VectorImportFormat.SVG -> "SVG"
    VectorImportFormat.PROJECT_BUNDLE -> "Project bundle JSON"
    VectorImportFormat.UNKNOWN -> "Unknown"
}
