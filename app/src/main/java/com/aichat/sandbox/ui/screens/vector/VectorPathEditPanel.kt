package com.aichat.sandbox.ui.screens.vector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Controls for editing the selected paths (Phase 7). All edits are deterministic
 * and create a new `MANUAL_EDIT` child version; the source is never mutated. Hex
 * color text fields are used instead of a full color picker (deferred).
 */
@Composable
fun VectorPathEditPanel(
    selectedCount: Int,
    enabled: Boolean,
    onDelete: () -> Unit,
    onSimplify: (tolerance: Float, simplifyFills: Boolean) -> Unit,
    onRecolor: (strokeColor: String?, fillColor: String?) -> Unit,
    onRestyle: (strokeWidth: Float?, lineCap: String?, lineJoin: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var strokeColor by remember { mutableStateOf("") }
    var fillColor by remember { mutableStateOf("") }
    var strokeWidth by remember { mutableStateOf("") }
    var lineCap by remember { mutableStateOf<String?>(null) }
    var lineJoin by remember { mutableStateOf<String?>(null) }
    var tolerance by remember { mutableStateOf("0.5") }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = if (selectedCount == 0) {
                "Select paths in the inspector to edit them."
            } else {
                "$selectedCount path(s) selected."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDelete, enabled = enabled) { Text("Delete selected") }
            OutlinedButton(
                onClick = { onSimplify(tolerance.toFloatOrNull() ?: 0.5f, false) },
                enabled = enabled,
            ) { Text("Simplify") }
        }

        OutlinedTextField(
            value = tolerance,
            onValueChange = { tolerance = it },
            label = { Text("Simplify tolerance") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )

        Text(
            text = "Recolor",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        OutlinedTextField(
            value = strokeColor,
            onValueChange = { strokeColor = it },
            label = { Text("Stroke color (#RRGGBB)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = fillColor,
            onValueChange = { fillColor = it },
            label = { Text("Fill color (#RRGGBB)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
        Button(
            onClick = { onRecolor(strokeColor.ifBlank { null }, fillColor.ifBlank { null }) },
            enabled = enabled,
            modifier = Modifier.padding(top = 6.dp),
        ) { Text("Apply recolor") }

        Text(
            text = "Restyle",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        OutlinedTextField(
            value = strokeWidth,
            onValueChange = { strokeWidth = it },
            label = { Text("Stroke width (0.1–64)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        StyleChips("Cap", listOf("butt", "round", "square"), lineCap) { lineCap = it }
        StyleChips("Join", listOf("miter", "round", "bevel"), lineJoin) { lineJoin = it }
        Button(
            onClick = { onRestyle(strokeWidth.toFloatOrNull(), lineCap, lineJoin) },
            enabled = enabled,
            modifier = Modifier.padding(top = 6.dp),
        ) { Text("Apply restyle") }
    }
}

@Composable
private fun StyleChips(
    label: String,
    options: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(if (selected == option) null else option) },
                label = { Text(option) },
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}
