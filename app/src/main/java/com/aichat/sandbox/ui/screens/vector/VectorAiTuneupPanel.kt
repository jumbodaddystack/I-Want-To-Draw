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
 * AI Tune-Up controls for the Compare tab (Phase 4).
 *
 * Lets the user type an instruction and request a model-guided tune-up. The
 * model only ever proposes a plan; the app validates and applies it before a
 * candidate appears — surfaced here as friendly status text, never raw JSON.
 */
@Composable
fun VectorAiTuneupPanel(
    state: VectorTuneupUiState,
    onPromptChange: (String) -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "AI Tune-Up proposes safe edit operations. The app validates " +
                "the plan before creating a candidate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        OutlinedTextField(
            value = state.aiPrompt,
            onValueChange = onPromptChange,
            label = { Text("AI instruction") },
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
                    state.aiPrompt.isNotBlank() &&
                    (state.hasOriginal || state.inputXml.isNotBlank()),
            ) {
                Text("AI Tune-Up")
            }
            if (state.isAiRunning) {
                OutlinedButton(onClick = onCancel) {
                    Text("Cancel")
                }
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            }
        }

        if (state.isAiRunning) {
            Text(
                text = "AI is composing an edit plan…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        state.aiStatusMessage?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        state.lastAiSummary?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
