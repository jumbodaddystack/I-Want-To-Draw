package com.aichat.sandbox.ui.components.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import com.aichat.sandbox.data.model.NoteFrame

/**
 * Sub-phase 8.1 — frame overlay.
 *
 * Renders each [NoteFrame] as a constant-1-device-pixel rectangle on top of
 * the canvas with a small name label hanging off the top-left corner. The
 * width is constant in screen space (a 1 px stroke regardless of zoom) so a
 * heavily zoomed-out canvas still shows the frame boundary; at extreme
 * zoom-in the label sits at the world-space corner so the user can find the
 * frame by panning.
 *
 * The overlay does not consume pointer events — it sits beneath the
 * selection overlay and AI banner. Frame creation / resize gestures are
 * handled inside [DrawingSurface] (drag = create, tap = select); frame
 * deletion and rename happen from the navigator (sub-phase 8.2).
 */
@Composable
fun FrameOverlay(
    frames: List<NoteFrame>,
    currentFrameId: String?,
    viewport: ViewportController?,
    modifier: Modifier = Modifier,
) {
    if (frames.isEmpty() || viewport == null) return
    val density = LocalDensity.current
    // Observe viewport changes through derivedStateOf so a pan/zoom triggers
    // a recomposition for this overlay even though the controller itself is
    // a plain object (its fields are mutableStateOf-backed).
    val viewportSnapshot by remember(viewport) {
        derivedStateOf { Triple(viewport.offsetX, viewport.offsetY, viewport.scale) }
    }
    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val (offX, offY, scale) = viewportSnapshot
            for (frame in frames) {
                val left = frame.minX * scale + offX
                val top = frame.minY * scale + offY
                val right = frame.maxX * scale + offX
                val bottom = frame.maxY * scale + offY
                val isCurrent = frame.id == currentFrameId
                val color = if (isCurrent) FRAME_CURRENT_COLOR else FRAME_COLOR
                drawRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size((right - left).coerceAtLeast(1f), (bottom - top).coerceAtLeast(1f)),
                    style = Stroke(width = if (isCurrent) 2f else 1f),
                )
            }
        }
        // Name labels in Compose so they're crisp and theme-aware.
        for (frame in frames) {
            val (offX, offY, scale) = viewportSnapshot
            val left = frame.minX * scale + offX
            val top = frame.minY * scale + offY
            val isCurrent = frame.id == currentFrameId
            val pxLeft = with(density) { left.toDp() }
            val pxTop = with(density) { top.toDp() }
            Box(
                modifier = Modifier
                    .offset { IntOffset(left.toInt(), (top - with(density) { 18.dp.toPx() }).toInt().coerceAtLeast(0)) }
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = frame.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrent) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private val FRAME_COLOR = Color(0x661E88E5)
private val FRAME_CURRENT_COLOR = Color(0xFF1E88E5)
