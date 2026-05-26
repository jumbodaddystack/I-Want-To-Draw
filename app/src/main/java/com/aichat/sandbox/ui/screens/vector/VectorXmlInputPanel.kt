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

/**
 * Input section of the workspace: a multiline XML field plus paste-sample,
 * parse, and clear actions. Parsing is always explicit (button), never on every
 * keystroke.
 */
@Composable
fun VectorXmlInputPanel(
    inputXml: String,
    onXmlChanged: (String) -> Unit,
    onParse: () -> Unit,
    onPasteSample: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Paste Android VectorDrawable XML and parse it to see metrics " +
                "and warnings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        OutlinedTextField(
            value = inputXml,
            onValueChange = onXmlChanged,
            label = { Text("VectorDrawable XML") },
            placeholder = { Text("<vector xmlns:android=…> … </vector>") },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp),
        )
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
