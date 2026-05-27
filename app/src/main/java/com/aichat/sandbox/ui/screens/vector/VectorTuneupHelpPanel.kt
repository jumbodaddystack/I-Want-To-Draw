package com.aichat.sandbox.ui.screens.vector

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/**
 * A collapsible "How this works" guide for the Vector Art Tune-Up workspace
 * (Phase 11). Explains the workflow, the difference between the optimize/AI/edit
 * modes, and the main import/export limits, so the feature is approachable
 * without leaving the screen.
 */
@Composable
fun VectorTuneupHelpPanel(
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .semantics {
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "How Vector Tune-Up works",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Hide help" else "Show help",
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                HELP_STEPS.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                Text(
                    text = "Limits: imports up to 5 MB. Gradients, clip paths, masks, " +
                        "text, embedded images, and filters are not supported and are " +
                        "dropped with a warning. Previews are approximate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private val HELP_STEPS = listOf(
    "1. Paste or import Android VectorDrawable XML, SVG, or project bundle JSON.",
    "2. Use Diagnostics to inspect size, metrics, and warnings.",
    "3. Use Optimize for safe local cleanup that preserves the look.",
    "4. Use AI Tune-Up to edit the existing paths with a typed instruction.",
    "5. Use AI Redraw for a more transformative, icon-like rebuild.",
    "6. Use Edit for manual path-level changes and batch restyling.",
    "7. Use History to branch, duplicate, delete leaf versions, and import bundles.",
    "8. Use Export to save Android XML, SVG, or a project bundle JSON.",
)
