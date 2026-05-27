package com.aichat.sandbox.ui.screens.vector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.vector.AndroidVectorDrawableParser
import com.aichat.sandbox.data.vector.VectorPreviewBuilder
import com.aichat.sandbox.data.vector.VectorPreviewModel
import com.aichat.sandbox.data.vector.VectorVersionDiff
import com.aichat.sandbox.data.vector.VectorViewport
import com.aichat.sandbox.data.vector.contentBounds

/** Phase 8 — the visual-diff presentation modes (not a pixel diff). */
enum class VectorVisualDiffMode {
    SIDE_BY_SIDE,
    OVERLAY_BOUNDS,
    PATH_COUNT_HEATMAP,
}

/**
 * Phase 8 — a structural/visual comparison of the original vs the
 * selected/candidate version.
 *
 * This is intentionally not a pixel diff: it offers side-by-side rendered
 * previews, a bounds overlay (candidate drawn normally, original faint, plus a
 * bounding box per version), and a simple path-count comparison. The structural
 * [diff] summary from [VectorVersionDiff] is always shown for context.
 */
@Composable
fun VectorVisualDiffPanel(
    original: VectorVersionUi?,
    candidate: VectorVersionUi?,
    mode: VectorVisualDiffMode,
    onModeChange: (VectorVisualDiffMode) -> Unit,
    diff: VectorVersionDiff?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (original == null && candidate == null) {
            Text(
                text = "Parse a vector to compare versions visually.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VectorVisualDiffMode.entries.forEach { m ->
                FilterChip(
                    selected = mode == m,
                    onClick = { onModeChange(m) },
                    label = { Text(modeLabel(m)) },
                )
            }
        }

        when (mode) {
            VectorVisualDiffMode.SIDE_BY_SIDE -> SideBySide(original, candidate)
            VectorVisualDiffMode.OVERLAY_BOUNDS -> OverlayBounds(original, candidate)
            VectorVisualDiffMode.PATH_COUNT_HEATMAP -> PathCountHeatmap(original, candidate)
        }

        diff?.let {
            Text(
                text = it.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun SideBySide(original: VectorVersionUi?, candidate: VectorVersionUi?) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val totalWidth = maxWidth
        val wide = totalWidth >= 480.dp
        if (wide && original != null && candidate != null) {
            val columnWidth = (totalWidth - 12.dp) / 2
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                VectorPreviewPanel(
                    title = "Original",
                    version = original,
                    modifier = Modifier.width(columnWidth),
                )
                VectorPreviewPanel(
                    title = candidate.label,
                    version = candidate,
                    modifier = Modifier.width(columnWidth),
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (original != null) {
                    VectorPreviewPanel(title = "Original", version = original)
                }
                if (candidate != null) {
                    VectorPreviewPanel(
                        title = candidate.label,
                        version = candidate,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun OverlayBounds(original: VectorVersionUi?, candidate: VectorVersionUi?) {
    val base = candidate ?: original
    val ghost = if (candidate != null) original else null
    if (base == null) return

    val baseModel = remember(base.id, base.xml) { buildModel(base) }
    val ghostModel = remember(ghost?.id, ghost?.xml) { ghost?.let { buildModel(it) } }
    val basePrepared = remember(baseModel) { preparePreviewPaths(baseModel) }
    val ghostPrepared = remember(ghostModel) { ghostModel?.let { preparePreviewPaths(it) }.orEmpty() }

    val baseColor = MaterialTheme.colorScheme.primary
    val ghostColor = MaterialTheme.colorScheme.tertiary

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.4f),
    ) {
        Canvas(modifier = Modifier.fillMaxWidth()) {
            val t = computePreviewTransform(size, baseModel.viewport) ?: return@Canvas
            withTransform({
                translate(t.offsetX, t.offsetY)
                scale(t.scale, t.scale, pivot = Offset.Zero)
                clipRect(0f, 0f, t.viewportWidth, t.viewportHeight)
            }) {
                ghostPrepared.forEach { drawPreparedPath(it, alpha = 0.25f) }
                basePrepared.forEach { drawPreparedPath(it, alpha = 1f) }
                ghostModel?.contentBounds()?.let { drawBoundsBox(it, ghostColor, t.scale) }
                baseModel.contentBounds()?.let { drawBoundsBox(it, baseColor, t.scale) }
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "■ ${candidate?.label ?: "Original"} bounds",
            style = MaterialTheme.typography.labelSmall,
            color = baseColor,
        )
        if (ghostModel != null) {
            Text(
                text = "■ Original (faded)",
                style = MaterialTheme.typography.labelSmall,
                color = ghostColor,
            )
        }
    }
}

@Composable
private fun PathCountHeatmap(original: VectorVersionUi?, candidate: VectorVersionUi?) {
    val originalCount = original?.metrics?.pathCount ?: 0
    val candidateCount = candidate?.metrics?.pathCount ?: 0
    val maxCount = maxOf(originalCount, candidateCount, 1)

    Column(modifier = Modifier.fillMaxWidth()) {
        CountBar("Original", originalCount, originalCount.toFloat() / maxCount)
        if (candidate != null) {
            CountBar(candidate.label, candidateCount, candidateCount.toFloat() / maxCount)
        }
        Text(
            text = "Relative path count. Fewer paths usually means a simpler, lighter drawing.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun CountBar(label: String, count: Int, fraction: Float) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("$count paths", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = fraction.coerceIn(0f, 1f),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        )
    }
}

private fun buildModel(version: VectorVersionUi): VectorPreviewModel =
    runCatching { VectorPreviewBuilder.build(AndroidVectorDrawableParser.parse(version.xml)) }
        .getOrElse { VectorPreviewModel(viewport = VectorViewport(24f, 24f, 24f, 24f), paths = emptyList()) }

private fun modeLabel(mode: VectorVisualDiffMode): String = when (mode) {
    VectorVisualDiffMode.SIDE_BY_SIDE -> "Side by side"
    VectorVisualDiffMode.OVERLAY_BOUNDS -> "Overlay"
    VectorVisualDiffMode.PATH_COUNT_HEATMAP -> "Path count"
}
