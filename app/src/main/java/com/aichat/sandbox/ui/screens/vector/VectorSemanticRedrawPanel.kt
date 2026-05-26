package com.aichat.sandbox.ui.screens.vector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * AI Redraw controls for the Compare tab (Phase 5).
 *
 * The transformative counterpart to [VectorAiTuneupPanel]: instead of editing
 * existing paths, the model proposes a fresh clean scene of primitives that the
 * app validates and compiles to a candidate. The model only ever proposes a
 * scene (never raw XML); the app validates and compiles it before a candidate
 * appears — surfaced here as friendly status text, never raw JSON.
 */
@Composable
fun VectorSemanticRedrawPanel(
    state: VectorTuneupUiState,
    onPromptChange: (String) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "AI Redraw creates a new clean vector scene from the drawing " +
                "summary. It can change geometry more aggressively than AI Tune-Up.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        OutlinedTextField(
            value = state.redrawPrompt,
            onValueChange = onPromptChange,
            label = { Text("Redraw instruction") },
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onRun,
                enabled = !state.isBusy &&
                    state.redrawPrompt.isNotBlank() &&
                    (state.hasOriginal || state.inputXml.isNotBlank()),
            ) {
                Text("AI Redraw")
            }
            if (state.isRedrawRunning) {
                OutlinedButton(onClick = onCancel) {
                    Text("Cancel")
                }
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
        }

        if (state.isRedrawRunning) {
            Text(
                text = "AI is composing a clean vector scene…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        state.redrawStatusMessage?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        state.lastRedrawSummary?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
