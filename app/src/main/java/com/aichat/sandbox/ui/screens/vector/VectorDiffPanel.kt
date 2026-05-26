package com.aichat.sandbox.ui.screens.vector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.vector.VectorVersionDiff
import kotlin.math.roundToInt

/**
 * Structural diff between the original and the analyzed source version
 * (Phase 7): size/path/command deltas, color movement, warning delta, and
 * whether the geometry bounds shifted. No raster comparison.
 */
@Composable
fun VectorDiffPanel(
    diff: VectorVersionDiff?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (diff == null) {
            Text(
                text = "Compare a version against the original to see a diff.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        DiffRow("Size", signedBytes(diff.bytesDelta, diff.bytesDeltaPercent))
        DiffRow("Paths", signed(diff.pathCountDelta))
        DiffRow("Commands", signed(diff.commandCountDelta))
        DiffRow("Warnings", signed(diff.warningDelta))
        DiffRow("Bounds", if (diff.boundsChanged) "changed" else "unchanged")
        if (diff.colorAdded.isNotEmpty()) ColorRow("Added", diff.colorAdded)
        if (diff.colorRemoved.isNotEmpty()) ColorRow("Removed", diff.colorRemoved)
        if (diff.colorRetained.isNotEmpty()) ColorRow("Retained", diff.colorRetained)

        Text(
            text = diff.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun DiffRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ColorRow(label: String, colors: List<String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = colors.joinToString(", "),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun signed(value: Int): String = when {
    value > 0 -> "+$value"
    value < 0 -> value.toString()
    else -> "0"
}

private fun signedBytes(bytes: Int, percent: Float?): String {
    val pct = percent?.let { " (${if (it > 0) "+" else ""}${it.roundToInt()}%)" } ?: ""
    return "${signed(bytes)} B$pct"
}
