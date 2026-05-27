package com.aichat.sandbox.ui.screens.vector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.vector.AndroidVectorDrawableParser
import com.aichat.sandbox.data.vector.VectorPreviewBuilder
import com.aichat.sandbox.data.vector.VectorPreviewModel
import com.aichat.sandbox.data.vector.VectorWarning

/** Result of parsing + building a preview for one version (memoized in the panel). */
private data class PreviewBuild(
    val model: VectorPreviewModel?,
    val xmlBytes: Int,
    val pathCount: Int,
    val commandCount: Int,
    val warnings: List<VectorWarning>,
)

/**
 * Phase 8 — renders one [VectorVersionUi] as a safe vector preview with small
 * metadata and a warning summary.
 *
 * Parsing + preview building are memoized on the version id + XML so they only
 * re-run when the version actually changes. Everything is wrapped so malformed
 * XML degrades to a friendly placeholder instead of crashing.
 */
@Composable
fun VectorPreviewPanel(
    title: String,
    version: VectorVersionUi?,
    modifier: Modifier = Modifier,
    showMetadata: Boolean = true,
    highlightPathIds: Set<String> = emptySet(),
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        if (version == null) {
            PreviewMessage("Nothing to preview yet.")
            return
        }

        val build = remember(version.id, version.xml) {
            runCatching {
                val document = AndroidVectorDrawableParser.parse(version.xml)
                val model = VectorPreviewBuilder.build(document)
                PreviewBuild(
                    model = model,
                    xmlBytes = version.xml.toByteArray(Charsets.UTF_8).size,
                    pathCount = model.paths.size,
                    commandCount = model.paths.sumOf { it.commands.size },
                    warnings = model.warnings,
                )
            }.getOrElse {
                PreviewBuild(null, version.xml.length, 0, 0, emptyList())
            }
        }

        val model = build.model
        if (model == null || model.paths.isEmpty()) {
            PreviewMessage(
                "Preview unavailable for this vector. Diagnostics and XML editing still work.",
            )
        } else {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(previewAspect(model))
                    .semantics {
                        contentDescription =
                            "$title preview: ${build.pathCount} paths, ${build.commandCount} commands."
                    },
            ) {
                VectorPreviewCanvas(
                    model = model,
                    modifier = Modifier.fillMaxWidth(),
                    highlightPathIds = highlightPathIds,
                )
            }
        }

        if (showMetadata) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Meta("${build.xmlBytes} B")
                Meta("${build.pathCount} paths")
                Meta("${build.commandCount} cmds")
                Meta("${build.warnings.size} warn")
            }
        }

        val notable = build.warnings.filter {
            it.code != VectorWarning.Codes.PREVIEW_COMPILED_WITH_WARNINGS
        }
        if (notable.isNotEmpty()) {
            Text(
                text = warningSummary(notable),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun previewAspect(model: VectorPreviewModel): Float {
    val w = model.viewport.viewportWidth
    val h = model.viewport.viewportHeight
    if (w <= 0f || h <= 0f) return 1f
    return (w / h).coerceIn(0.4f, 2.5f)
}

private fun warningSummary(warnings: List<VectorWarning>): String {
    val skipped = warnings.count { it.code == VectorWarning.Codes.PREVIEW_SKIPPED_UNPARSED_PATH }
    val parts = ArrayList<String>()
    if (skipped > 0) parts += "$skipped path(s) skipped"
    if (warnings.any { it.code == VectorWarning.Codes.PREVIEW_UNSUPPORTED_FEATURE }) {
        parts += "unsupported features omitted"
    }
    return if (parts.isEmpty()) {
        "${warnings.size} preview note(s)."
    } else {
        parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }
}

@Composable
private fun Meta(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PreviewMessage(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.8f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}
