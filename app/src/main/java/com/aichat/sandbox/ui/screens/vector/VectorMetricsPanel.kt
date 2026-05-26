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
import com.aichat.sandbox.data.vector.VectorBounds
import com.aichat.sandbox.data.vector.VectorMetrics

/**
 * Tabular display of a [VectorMetrics] snapshot for the diagnostics panel.
 * Pure presentation — every value comes straight from the analyzer.
 */
@Composable
fun VectorMetricsPanel(
    metrics: VectorMetrics,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        MetricRow("XML size", "${metrics.xmlBytes} bytes")
        MetricRow("Path count", metrics.pathCount.toString())
        MetricRow("Group count", metrics.groupCount.toString())
        MetricRow("Command count", metrics.commandCount.toString())
        MetricRow("Parsed commands", metrics.parsedCommandCount.toString())
        MetricRow("Unsupported paths", metrics.unsupportedPathCount.toString())
        MetricRow("Estimated points", metrics.estimatedPointCount.toString())
        MetricRow("Stroke paths", metrics.strokePathCount.toString())
        MetricRow("Fill paths", metrics.fillPathCount.toString())
        MetricRow("Zero-length paths", metrics.zeroLengthPathCount.toString())
        MetricRow("Tiny segments (est.)", metrics.tinySegmentEstimate.toString())
        MetricRow("Duplicate coords (est.)", metrics.duplicateCoordinateEstimate.toString())
        MetricRow("Bounds", formatBounds(metrics.bounds))
        if (metrics.colorCounts.isNotEmpty()) {
            Text(
                text = "Colors",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            for ((color, count) in metrics.colorCounts) {
                MetricRow(color, "×$count", mono = true)
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun formatBounds(bounds: VectorBounds?): String {
    if (bounds == null) return "—"
    fun f(v: Float) = if (v == v.toLong().toFloat()) v.toLong().toString() else "%.1f".format(v)
    return "${f(bounds.minX)}, ${f(bounds.minY)} → ${f(bounds.maxX)}, ${f(bounds.maxY)}"
}
