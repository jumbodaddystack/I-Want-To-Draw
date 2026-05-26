package com.aichat.sandbox.ui.screens.vector

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.vector.VectorBatchRestyle

private enum class BatchTarget { ALL_STROKED, ALL_FILLED, BY_COLOR }

/**
 * Batch restyle by color / path group (Phase 7). Targets every stroked or filled
 * path, or every path using a chosen color, and applies new colors/width and/or
 * round caps+joins in one deterministic `MANUAL_EDIT` version.
 */
@Composable
fun VectorBatchRestylePanel(
    availableColors: List<String>,
    enabled: Boolean,
    onApply: (VectorBatchRestyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    var target by remember { mutableStateOf(BatchTarget.ALL_STROKED) }
    var targetColor by remember { mutableStateOf<String?>(null) }
    var newStroke by remember { mutableStateOf("") }
    var newFill by remember { mutableStateOf("") }
    var strokeWidth by remember { mutableStateOf("") }
    var roundCapsJoins by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TargetChip("All stroked", target == BatchTarget.ALL_STROKED) { target = BatchTarget.ALL_STROKED }
            TargetChip("All filled", target == BatchTarget.ALL_FILLED) { target = BatchTarget.ALL_FILLED }
            TargetChip("By color", target == BatchTarget.BY_COLOR) { target = BatchTarget.BY_COLOR }
        }

        if (target == BatchTarget.BY_COLOR) {
            if (availableColors.isEmpty()) {
                Text(
                    text = "No colors available in this version.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 4.dp),
                ) {
                    availableColors.forEach { color ->
                        FilterChip(
                            selected = targetColor == color,
                            onClick = { targetColor = if (targetColor == color) null else color },
                            label = {
                                Text(color, style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace))
                            },
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = newStroke,
            onValueChange = { newStroke = it },
            label = { Text("New stroke color (#RRGGBB)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        OutlinedTextField(
            value = newFill,
            onValueChange = { newFill = it },
            label = { Text("New fill color (#RRGGBB)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
        OutlinedTextField(
            value = strokeWidth,
            onValueChange = { strokeWidth = it },
            label = { Text("Stroke width (0.1–64)") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = roundCapsJoins, onCheckedChange = { roundCapsJoins = it })
            Text(
                text = "Round caps & joins",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Button(
            onClick = {
                val resolvedTarget = when (target) {
                    BatchTarget.ALL_STROKED -> VectorBatchRestyle.Target.AllStroked
                    BatchTarget.ALL_FILLED -> VectorBatchRestyle.Target.AllFilled
                    BatchTarget.BY_COLOR ->
                        VectorBatchRestyle.Target.ByColor(listOfNotNull(targetColor))
                }
                onApply(
                    VectorBatchRestyle(
                        target = resolvedTarget,
                        strokeColor = newStroke.ifBlank { null },
                        fillColor = newFill.ifBlank { null },
                        strokeWidth = strokeWidth.toFloatOrNull(),
                        lineCap = if (roundCapsJoins) "round" else null,
                        lineJoin = if (roundCapsJoins) "round" else null,
                    ),
                )
            },
            enabled = enabled,
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Apply batch restyle") }
    }
}

@Composable
private fun TargetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.padding(end = 6.dp),
    )
}
