package com.aichat.sandbox.ui.screens.vector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.vector.VectorOptimizeOptions

/**
 * Exposes the Phase 2 [VectorOptimizeOptions] as sliders + switches. Ranges are
 * clamped to safe values and defaults are conservative; changing a control only
 * updates [onOptionsChange] — the caller still drives optimization explicitly.
 */
@Composable
fun VectorOptimizeControls(
    options: VectorOptimizeOptions,
    onOptionsChange: (VectorOptimizeOptions) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Conservative defaults: only stroked paths are simplified and " +
                "filled shapes are left untouched.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        LabeledSlider(
            label = "Tolerance",
            value = options.tolerance,
            range = 0f..2f,
            format = { "%.2f".format(it) },
            onValueChange = { onOptionsChange(options.copy(tolerance = it)) },
        )
        LabeledSlider(
            label = "Min path length",
            value = options.minPathLength,
            range = 0f..2f,
            format = { "%.2f".format(it) },
            onValueChange = { onOptionsChange(options.copy(minPathLength = it)) },
        )
        LabeledSlider(
            label = "Decimal places",
            value = options.decimalPlaces.toFloat(),
            range = 0f..4f,
            steps = 3,
            format = { it.toInt().toString() },
            onValueChange = { onOptionsChange(options.copy(decimalPlaces = it.toInt())) },
        )

        LabeledSwitch(
            label = "Remove tiny paths",
            checked = options.removeTinyPaths,
            onCheckedChange = { onOptionsChange(options.copy(removeTinyPaths = it)) },
        )
        LabeledSwitch(
            label = "Simplify strokes",
            checked = options.simplifyStrokes,
            onCheckedChange = { onOptionsChange(options.copy(simplifyStrokes = it)) },
        )
        LabeledSwitch(
            label = "Simplify fills",
            checked = options.simplifyFills,
            onCheckedChange = { onOptionsChange(options.copy(simplifyFills = it)) },
        )
        LabeledSwitch(
            label = "Preserve curves",
            checked = options.preserveCurves,
            onCheckedChange = { onOptionsChange(options.copy(preserveCurves = it)) },
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit,
    steps: Int = 0,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = format(value),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Composable
private fun LabeledSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
