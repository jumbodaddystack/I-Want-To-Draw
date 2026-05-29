package com.aichat.sandbox.ui.screens.vector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.vector.VectorQualityScores
import kotlin.math.roundToInt

/**
 * Advisory, explainable quality scores for the analyzed source version
 * (Phase 7). Each row shows a 0–100 score with a bar; faithfulness shows "—"
 * when there is no original to compare against.
 */
@Composable
fun VectorQualityPanel(
    scores: VectorQualityScores?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (scores == null) {
            Text(
                text = "Select a version and tap Analyze to see quality scores.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        ScoreRow("Overall", scores.overall, emphasized = true)
        ScoreRow("Cleanliness", scores.cleanliness)
        ScoreRow("Faithfulness", scores.faithfulness)
        ScoreRow("Icon readiness", scores.iconReadiness)
        ScoreRow("File efficiency", scores.fileEfficiency)
        ScoreRow("Maintainability", scores.maintainability)

        if (scores.notes.isNotEmpty()) {
            Text(
                text = "Notes",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            scores.notes.forEach { note ->
                Text(
                    text = "• $note",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun ScoreRow(label: String, score: Float?, emphasized: Boolean = false) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = score?.let { "${(it * 100).roundToInt()}" } ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        @Suppress("DEPRECATION")
        if (score != null) {
            LinearProgressIndicator(
                progress = score,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            )
        } else {
            // No data to score against (e.g. faithfulness with no original):
            // render a muted, empty track so it reads as "N/A" rather than a
            // broken 0% bar.
            LinearProgressIndicator(
                progress = 0f,
                color = Color.Transparent,
                trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            )
        }
    }
}
