package com.aichat.sandbox.ui.screens.vector.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.vector.IconSetExporter
import com.aichat.sandbox.data.vector.IconSizeSet
import com.aichat.sandbox.data.vector.IconTarget

/**
 * Phase 3 — the icon-set export panel.
 *
 * Lets the user pick output sizes (24 / 48 / 108 dp), formats (VectorDrawable /
 * SVG), and whether to grid-quantize before serializing, then builds an
 * [IconSetExporter.Spec] off [baseSizeSet] (which carries any per-size optical
 * adjustments) and hands it to [onExport]. All export math is the pure
 * [IconSetExporter]; this panel is just the selection chrome.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IconExportPanel(
    baseSizeSet: IconSizeSet,
    onExport: (IconSetExporter.Spec) -> Unit,
    modifier: Modifier = Modifier,
) {
    var targets by remember { mutableStateOf(IconTarget.entries.toSet()) }
    var formats by remember {
        mutableStateOf(setOf(IconSetExporter.Format.ANDROID_VECTOR_DRAWABLE))
    }
    var quantize by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Export icon set", style = MaterialTheme.typography.titleMedium)

        Text("Sizes", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (target in IconTarget.entries) {
                FilterChip(
                    selected = target in targets,
                    onClick = { targets = targets.toggle(target) },
                    label = { Text("${target.dp}dp") },
                )
            }
        }

        Text("Formats", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = IconSetExporter.Format.ANDROID_VECTOR_DRAWABLE in formats,
                onClick = { formats = formats.toggle(IconSetExporter.Format.ANDROID_VECTOR_DRAWABLE) },
                label = { Text("VectorDrawable") },
            )
            FilterChip(
                selected = IconSetExporter.Format.SVG in formats,
                onClick = { formats = formats.toggle(IconSetExporter.Format.SVG) },
                label = { Text("SVG") },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = quantize, onCheckedChange = { quantize = it })
            Text(
                "Quantize to pixel grid",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Button(
            onClick = {
                onExport(
                    IconSetExporter.Spec(
                        sizes = baseSizeSet.copy(targets = IconTarget.entries.filter { it in targets }),
                        formats = formats,
                        quantize = quantize,
                    ),
                )
            },
            enabled = targets.isNotEmpty() && formats.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Export (.zip)")
        }
    }
}

private fun <T> Set<T>.toggle(value: T): Set<T> =
    if (value in this) this - value else this + value
