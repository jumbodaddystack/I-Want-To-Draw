package com.aichat.sandbox.ui.screens.vector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.model.VectorTuneupMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One version in the [VectorVersionHistoryPanel]: its label/mode, lineage,
 * headline metrics, and per-version actions (select, make active, export).
 * Active and selected versions are visually distinguished so the user can see
 * which version drives compare/export and which the next operation branches from.
 */
@Composable
fun VectorVersionCard(
    version: VectorVersionUi,
    parentLabel: String?,
    isActive: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onMakeActive: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isActive -> MaterialTheme.colorScheme.primaryContainer
                isSelected -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = version.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = markers(isActive, isSelected),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = lineage(version, parentLabel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = metricsLine(version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (version.warnings.isNotEmpty()) {
                Text(
                    text = "${version.warnings.size} warning(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onSelect, enabled = !isSelected) { Text("Select") }
                TextButton(onClick = onMakeActive, enabled = !isActive) { Text("Make active") }
                OutlinedButton(onClick = onExport) { Text("Export") }
            }
        }
    }
}

private fun markers(isActive: Boolean, isSelected: Boolean): String = when {
    isActive && isSelected -> "active · selected"
    isActive -> "active"
    isSelected -> "selected"
    else -> ""
}

private fun lineage(version: VectorVersionUi, parentLabel: String?): String {
    val mode = modeLabel(version.mode)
    val from = parentLabel?.let { " · from $it" } ?: ""
    val time = if (version.createdAt > 0L) " · ${timeFormat.format(Date(version.createdAt))}" else ""
    return "$mode$from$time"
}

private fun metricsLine(version: VectorVersionUi): String =
    "${version.metrics.xmlBytes}B · ${version.metrics.pathCount} paths · ${version.metrics.commandCount} cmds"

internal fun modeLabel(mode: VectorTuneupMode): String = when (mode) {
    VectorTuneupMode.ORIGINAL -> "Original"
    VectorTuneupMode.OPTIMIZE -> "Optimize"
    VectorTuneupMode.AI_TUNE_UP -> "AI Tune-Up"
    VectorTuneupMode.AI_REDRAW -> "AI Redraw"
    VectorTuneupMode.MANUAL_EDIT -> "Manual edit"
}

private val timeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
