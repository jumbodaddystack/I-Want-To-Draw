package com.aichat.sandbox.ui.screens.vector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.vector.VectorInputHealth
import com.aichat.sandbox.data.vector.VectorProjectHealth

/**
 * Diagnostics-tab panel summarizing how heavy the current vector is (Phase 11):
 * file size, path/command counts, warning count, an OK/LARGE/EXTREME/UNSAFE
 * rating, and a one-line recommendation.
 *
 * The rating is conveyed as text (not color alone) for accessibility. When the
 * input is EXTREME, a checkbox lets the user opt in to running expensive AI on it;
 * UNSAFE input is blocked regardless and the panel says so.
 */
@Composable
fun VectorProjectHealthPanel(
    health: VectorProjectHealth?,
    allowExpensive: Boolean,
    onAllowExpensiveChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (health == null) {
        Text(
            text = "Parse a vector to see its size and complexity health.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Health: ${severityLabel(health.severity)}",
            style = MaterialTheme.typography.titleSmall,
            color = severityColor(health.severity),
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Metric("${health.xmlBytes} B")
            Metric("${health.pathCount} paths")
            Metric("${health.commandCount} cmds")
            Metric("${health.warningCount} warn")
        }
        Text(
            text = health.recommendation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        when (health.severity) {
            VectorInputHealth.Severity.EXTREME -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = allowExpensive, onCheckedChange = onAllowExpensiveChange)
                    Text(
                        text = "Run AI on large input",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            VectorInputHealth.Severity.UNSAFE -> {
                Text(
                    text = "AI is disabled for inputs this large. Optimize locally first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun Metric(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun severityLabel(severity: VectorInputHealth.Severity): String = when (severity) {
    VectorInputHealth.Severity.OK -> "OK"
    VectorInputHealth.Severity.LARGE -> "Large"
    VectorInputHealth.Severity.EXTREME -> "Very large"
    VectorInputHealth.Severity.UNSAFE -> "Too large"
}

@Composable
private fun severityColor(severity: VectorInputHealth.Severity): Color = when (severity) {
    VectorInputHealth.Severity.OK -> MaterialTheme.colorScheme.primary
    VectorInputHealth.Severity.LARGE -> MaterialTheme.colorScheme.tertiary
    VectorInputHealth.Severity.EXTREME -> MaterialTheme.colorScheme.tertiary
    VectorInputHealth.Severity.UNSAFE -> MaterialTheme.colorScheme.error
}
