package com.aichat.sandbox.ui.screens.vector.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.vector.IconSizeSet
import com.aichat.sandbox.data.vector.VectorPreviewBuilder
import com.aichat.sandbox.ui.screens.vector.VectorPreviewCanvas

/**
 * Phase 3 — synchronized multi-size preview strip.
 *
 * Renders every target of [sizeSet] (`24 / 48 / 108` dp, with each size's optical
 * adjustment applied via [IconSizeSet.deriveAll]) as a small thumbnail next to the
 * live master canvas, so a single edit is previewed at every output size at once.
 * Reuses the existing [VectorPreviewCanvas] (no new renderer); each thumbnail is a
 * fixed on-screen box so the relative sizes read at a glance.
 */
@Composable
fun MultiSizePreviewStrip(
    sizeSet: IconSizeSet,
    modifier: Modifier = Modifier,
) {
    val derived = remember(sizeSet) { sizeSet.deriveAll() }
    Surface(tonalElevation = 1.dp, modifier = modifier) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            for ((target, doc) in derived) {
                val model = remember(doc) { VectorPreviewBuilder.build(doc) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    VectorPreviewCanvas(
                        model = model,
                        modifier = Modifier
                            .size(thumbSizeFor(target.dp))
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(6.dp),
                            )
                            .padding(2.dp),
                    )
                    Text(
                        text = "${target.dp}dp",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Map a target dp to an on-screen thumbnail box, capped so 108 stays compact. */
private fun thumbSizeFor(dp: Int): androidx.compose.ui.unit.Dp = when {
    dp <= 24 -> 36.dp
    dp <= 48 -> 52.dp
    else -> 72.dp
}
