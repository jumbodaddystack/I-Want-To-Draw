package com.aichat.sandbox.ui.screens.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.notes.NoteRasterizer
import com.aichat.sandbox.ui.components.notes.ViewportController
import java.io.File

/**
 * Phase 18 (audit P0.1, finding A1) — on-canvas preview of a staged AI edit.
 *
 * Until now an AI edit was accepted blind: the banner showed only a count.
 * This overlay draws the [EditPreviewController.Simulation] directly over the
 * live canvas *before* the user accepts / rejects, so the change is visible:
 *
 *  - **removed**  → red, ghosted (geometry that would disappear).
 *  - **modified** → the *before* faintly ghosted under the *after* in amber.
 *  - **added**    → green.
 *
 * The geometry is the real thing — each bucket is recoloured to its diff tint
 * and handed to [NoteRasterizer.drawItems], the same per-kind dispatcher the
 * export pipeline uses — so strokes / shapes / paths / text render exactly as
 * they will once accepted (no separate approximate render path to drift). The
 * overlay is non-interactive: a bare [Canvas] consumes no pointer input, so
 * drawing and panning continue underneath while the preview is up.
 *
 * Mirrors [FrameOverlay]'s viewport handling: world→screen is the same
 * `translate(offset) + scale` the surface applies when it rasterizes, and a
 * [derivedStateOf] snapshot of the viewport fields repaints on pan / zoom.
 */
@Composable
fun AiEditDiffOverlay(
    simulation: EditPreviewController.Simulation,
    viewport: ViewportController?,
    filesDir: File?,
    modifier: Modifier = Modifier,
) {
    if (viewport == null || simulation.isEmpty) return
    val snapshot by remember(viewport) {
        derivedStateOf { Triple(viewport.offsetX, viewport.offsetY, viewport.scale) }
    }
    Canvas(modifier = modifier.fillMaxSize()) {
        val (offX, offY, scale) = snapshot
        drawIntoCanvas { compose ->
            val canvas = compose.nativeCanvas
            canvas.save()
            canvas.translate(offX, offY)
            canvas.scale(scale, scale)
            // Paint order doubles as z-order: removed underneath, added on top.
            drawBucket(canvas, simulation.removed.map { it.tinted(AI_DIFF_REMOVED_ARGB) }, REMOVED_ALPHA, filesDir)
            drawBucket(canvas, simulation.modified.map { it.first.tinted(DIFF_BEFORE_ARGB) }, BEFORE_ALPHA, filesDir)
            drawBucket(canvas, simulation.modified.map { it.second.tinted(AI_DIFF_MODIFIED_ARGB) }, AFTER_ALPHA, filesDir)
            drawBucket(canvas, simulation.added.map { it.tinted(AI_DIFF_ADDED_ARGB) }, ADDED_ALPHA, filesDir)
            canvas.restore()
        }
    }
}

/**
 * Draw one diff bucket at a uniform [alpha] (the ghosting) using a saved
 * layer so the per-segment alpha the renderers set never compounds past the
 * intended translucency.
 */
private fun drawBucket(
    canvas: android.graphics.Canvas,
    items: List<NoteItem>,
    alpha: Int,
    filesDir: File?,
) {
    if (items.isEmpty()) return
    val layer = canvas.saveLayerAlpha(null, alpha)
    NoteRasterizer.drawItems(canvas, items, filesDir)
    canvas.restoreToCount(layer)
}

/** Recolour to a diff tint; geometry / width / payload are left untouched. */
private fun NoteItem.tinted(argb: Int): NoteItem = copy(colorArgb = argb)

// Diff tints, shared with the banner legend (AiEditPreviewBanner). Opaque —
// the per-bucket saveLayerAlpha applies the ghosting on top.
internal const val AI_DIFF_ADDED_ARGB: Int = 0xFF2E7D32.toInt()    // green
internal const val AI_DIFF_REMOVED_ARGB: Int = 0xFFE53935.toInt()  // red
internal const val AI_DIFF_MODIFIED_ARGB: Int = 0xFFF57C00.toInt() // amber

private const val DIFF_BEFORE_ARGB: Int = 0xFF9E9E9E.toInt()       // grey ghost

private const val REMOVED_ALPHA: Int = 120
private const val BEFORE_ALPHA: Int = 80
private const val AFTER_ALPHA: Int = 235
private const val ADDED_ALPHA: Int = 235
