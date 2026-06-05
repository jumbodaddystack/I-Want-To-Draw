package com.aichat.sandbox.ui.screens.vector.edit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.vector.VectorPreviewBuilder
import com.aichat.sandbox.data.vector.VectorPreviewPathNormalizer
import com.aichat.sandbox.data.vector.edit.EditAnchor
import com.aichat.sandbox.data.vector.edit.EditablePath
import com.aichat.sandbox.data.vector.edit.EditablePathSerializer
import com.aichat.sandbox.ui.components.notes.ViewportController
import com.aichat.sandbox.ui.screens.vector.PreparedPreviewPath
import com.aichat.sandbox.ui.screens.vector.buildComposePath
import com.aichat.sandbox.ui.screens.vector.drawPreparedPath
import com.aichat.sandbox.ui.screens.vector.parseVectorColor
import com.aichat.sandbox.ui.screens.vector.toBrush
import com.aichat.sandbox.ui.screens.vector.toStrokeCap
import com.aichat.sandbox.ui.screens.vector.toStrokeJoin

/**
 * Phase 1 (step 1d) — the Compose canvas + gestures for the node editor.
 *
 * This is intentionally thin: all hit-testing and action mapping already live in
 * [VectorEditViewModel] (which funnels through the pure [VectorEditReducer] and
 * [EditHitTest]), so the canvas only ever has to **draw the current state** and
 * **forward gestures** to the VM. There is no edit logic here — keep new geometry
 * in the reducer, never in the canvas (see PROGRESS watch-outs).
 *
 * Rendering reuses the existing preview pipeline ([buildComposePath] /
 * [drawPreparedPath] and friends) under the editor's own [ViewportController]
 * mapping (`screen = world * scale + offset`), so the geometry the user edits is
 * pixel-identical to what the Tune-Up preview shows. The path currently in
 * node-edit is drawn from its **live** [EditablePath] geometry (which reflects
 * in-progress edits before `ApplyToDocument`), with a constant-width "skeleton"
 * outline so an unfilled/unstroked path is still visible while editing. Anchor
 * knobs and control handles are overlaid in screen space at constant size; handles
 * are drawn only for **selected** anchors, matching [EditHitTest]'s candidate rule
 * so what's visible is exactly what's grabbable.
 *
 * Gestures map straight onto the VM contract: a single-finger tap is [onTap], a
 * single-finger drag brackets [onDragStart]/[onDrag]/[onDragEnd] (the VM coalesces
 * a drag into one undo step, so the bracket must be honest — [onDragEnd] fires on
 * cancel too), and a two-finger gesture is pan/zoom via [onPan]/[onZoom]. Drag
 * deltas are raw **screen** pixels; the VM divides by `viewport.scale` itself.
 */
@Composable
fun VectorEditCanvas(
    state: VectorEditState,
    viewport: ViewportController,
    modifier: Modifier = Modifier,
    onTap: (Float, Float) -> Unit = { _, _ -> },
    onDragStart: (Float, Float) -> Unit = { _, _ -> },
    onDrag: (Float, Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {},
    onPan: (Float, Float) -> Unit = { _, _ -> },
    onZoom: (Float, Float, Float) -> Unit = { _, _, _ -> },
) {
    val artboardFill = MaterialTheme.colorScheme.surface
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val skeletonColor = MaterialTheme.colorScheme.outline
    val anchorColor = MaterialTheme.colorScheme.onSurfaceVariant
    val handleColor = MaterialTheme.colorScheme.tertiary
    val anchorFillWhenIdle = MaterialTheme.colorScheme.surface
    val keylineLineColor = MaterialTheme.colorScheme.outlineVariant
    val keylineShapeColor = MaterialTheme.colorScheme.secondary

    val density = LocalDensity.current
    val anchorRadiusPx = with(density) { 5.dp.toPx() }
    val handleRadiusPx = with(density) { 4.dp.toPx() }
    val thinStrokePx = with(density) { 1.5.dp.toPx() }

    // Static document geometry, minus the path under edit (drawn live below).
    val previewModel = remember(state.document) { VectorPreviewBuilder.build(state.document) }
    val editingPathId = state.editing?.pathId
    val staticPaths = remember(previewModel, editingPathId) {
        com.aichat.sandbox.ui.screens.vector.preparePreviewPaths(previewModel)
            .filter { it.id != editingPathId }
    }

    // Live geometry of the path being edited (reflects uncommitted edits).
    val editingRender = remember(state.editing) { state.editing?.let(::buildEditingRender) }

    // Fit the artboard into the viewport when the document or canvas size changes.
    // The VM owns the (otherwise un-initialized) viewport; this is the one place
    // that frames it. Bounds-clamping makes it behave like a bounded icon canvas.
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(state.document.viewport, canvasSize) {
        if (canvasSize.width > 0 && canvasSize.height > 0) {
            val vp = state.document.viewport
            val bounds = floatArrayOf(0f, 0f, vp.viewportWidth, vp.viewportHeight)
            val size = floatArrayOf(canvasSize.width.toFloat(), canvasSize.height.toFloat())
            viewport.setPanBounds(bounds, size)
            viewport.fitToContent(bounds, size)
        }
    }

    // Long-lived gesture coroutine reads the latest callbacks via updated state.
    val onTapState by rememberUpdatedState(onTap)
    val onDragStartState by rememberUpdatedState(onDragStart)
    val onDragState by rememberUpdatedState(onDrag)
    val onDragEndState by rememberUpdatedState(onDragEnd)
    val onPanState by rememberUpdatedState(onPan)
    val onZoomState by rememberUpdatedState(onZoom)

    Canvas(
        modifier = modifier
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position
                    var dragging = false
                    var multiTouch = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (pressed >= 2) {
                            // Two fingers → pan/zoom. Abandon any single-finger drag
                            // first so its undo step is still coalesced honestly.
                            if (!multiTouch) {
                                if (dragging) { onDragEndState(); dragging = false }
                                multiTouch = true
                            }
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f) {
                                val c = event.calculateCentroid(useCurrent = true)
                                if (c != Offset.Unspecified) onZoomState(c.x, c.y, zoom)
                            }
                            if (pan != Offset.Zero) onPanState(pan.x, pan.y)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (!multiTouch) {
                            val change = event.changes.firstOrNull(PointerInputChange::pressed)
                                ?: continue
                            if (!dragging &&
                                (change.position - start).getDistance() > slop
                            ) {
                                dragging = true
                                onDragStartState(start.x, start.y)
                            }
                            if (dragging) {
                                val d = change.positionChange()
                                if (d != Offset.Zero) onDragState(d.x, d.y)
                                change.consume()
                            }
                        } else {
                            // Dropped from two fingers to one: stay in pan/zoom mode
                            // (no stray tap/drag) until the gesture fully lifts.
                            event.changes.forEach { it.consume() }
                        }
                    }
                    when {
                        dragging -> onDragEndState()
                        !multiTouch -> onTapState(start.x, start.y)
                    }
                }
            },
    ) {
        val scale = viewport.scale
        val ox = viewport.offsetX
        val oy = viewport.offsetY
        val vp = state.document.viewport

        // Geometry in world coordinates, mapped by the viewport transform.
        withTransform({
            translate(ox, oy)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            drawRect(
                color = artboardFill,
                topLeft = Offset.Zero,
                size = Size(vp.viewportWidth, vp.viewportHeight),
            )
            for (p in staticPaths) drawPreparedPath(p, alpha = 1f)
            editingRender?.let { (path, prepared) ->
                drawPreparedPath(prepared, alpha = 1f)
                // Constant-width skeleton so even an unpainted edit path is visible.
                drawPath(
                    path = path,
                    color = skeletonColor.copy(alpha = 0.7f),
                    style = Stroke(width = thinStrokePx / scale),
                )
            }
        }

        // Artboard border + interaction overlay in screen space (constant size).
        drawRect(
            color = borderColor,
            topLeft = Offset(viewport.worldToScreenX(0f), viewport.worldToScreenY(0f)),
            size = Size(vp.viewportWidth * scale, vp.viewportHeight * scale),
            style = Stroke(width = thinStrokePx),
        )

        // Material keyline overlay (Phase 3), beneath the anchor overlay.
        state.keyline?.let { keyline ->
            drawKeylineOverlay(
                keyline = keyline,
                viewport = viewport,
                lineColor = keylineLineColor,
                shapeColor = keylineShapeColor,
                strokeWidthPx = thinStrokePx * 0.7f,
            )
        }

        state.editing?.let { editing ->
            drawEditOverlay(
                editing = editing,
                pendingPen = state.pendingPen,
                selection = state.selection,
                viewport = viewport,
                anchorRadiusPx = anchorRadiusPx,
                handleRadiusPx = handleRadiusPx,
                thinStrokePx = thinStrokePx,
                accent = accent,
                anchorColor = anchorColor,
                anchorFillWhenIdle = anchorFillWhenIdle,
                handleColor = handleColor,
            )
        }
    }
}

/**
 * Convert the live [editing] path into a Compose [Path] plus its resolved paints,
 * reusing the exact preview pipeline so the editor and the Tune-Up preview agree
 * pixel-for-pixel. The [Path] is returned alongside the [PreparedPreviewPath] so
 * the caller can also stroke it as a skeleton outline.
 */
private fun buildEditingRender(editing: EditablePath): Pair<Path, PreparedPreviewPath> {
    val commands = EditablePathSerializer.toCommands(editing)
    val subpaths = VectorPreviewPathNormalizer.normalize(commands)
    val path = buildComposePath(subpaths).apply {
        fillType = if (editing.style.fillType.equals("evenOdd", ignoreCase = true)) {
            PathFillType.EvenOdd
        } else {
            PathFillType.NonZero
        }
    }
    val prepared = PreparedPreviewPath(
        id = editing.pathId,
        path = path,
        fill = parseVectorColor(editing.style.fillColor, editing.style.fillAlpha),
        fillBrush = editing.style.fill?.let { toBrush(it) },
        stroke = parseVectorColor(editing.style.strokeColor, editing.style.strokeAlpha),
        strokeWidth = editing.style.strokeWidth ?: 0f,
        cap = toStrokeCap(editing.style.strokeLineCap),
        join = toStrokeJoin(editing.style.strokeLineJoin),
    )
    return path to prepared
}

/**
 * Draw anchor knobs, control handles (selected anchors only), and the in-progress
 * pen draft on top of the rendered geometry. Everything is in screen space at a
 * constant pixel size so knobs stay grabbable at any zoom.
 */
private fun DrawScope.drawEditOverlay(
    editing: EditablePath,
    pendingPen: PenDraft?,
    selection: Selection,
    viewport: ViewportController,
    anchorRadiusPx: Float,
    handleRadiusPx: Float,
    thinStrokePx: Float,
    accent: Color,
    anchorColor: Color,
    anchorFillWhenIdle: Color,
    handleColor: Color,
) {
    fun screen(x: Float, y: Float) =
        Offset(viewport.worldToScreenX(x), viewport.worldToScreenY(y))

    editing.subpaths.forEach { sp ->
        // Handles first, so anchor knobs paint on top of their own handle lines.
        sp.anchors.forEach handles@{ a ->
            if (a.id !in selection.anchorIds) return@handles
            val anchorPos = screen(a.x, a.y)
            a.inHandle?.let { h -> drawHandle(anchorPos, screen(h.x, h.y), handleColor, handleRadiusPx, thinStrokePx) }
            a.outHandle?.let { h -> drawHandle(anchorPos, screen(h.x, h.y), handleColor, handleRadiusPx, thinStrokePx) }
        }
        sp.anchors.forEach { a ->
            val selected = a.id in selection.anchorIds
            val center = screen(a.x, a.y)
            if (selected) {
                drawCircle(accent, anchorRadiusPx, center)
            } else {
                // Hollow knob (outline + paper fill) so it reads as "tappable, idle".
                drawCircle(anchorFillWhenIdle, anchorRadiusPx, center)
                drawCircle(anchorColor, anchorRadiusPx, center, style = Stroke(width = thinStrokePx))
            }
        }
    }

    // In-progress pen draft: straight guide between placed points + their knobs.
    val draft = pendingPen?.anchors
    if (!draft.isNullOrEmpty()) {
        for (i in 0 until draft.size - 1) {
            drawLine(
                color = accent.copy(alpha = 0.8f),
                start = screen(draft[i].x, draft[i].y),
                end = screen(draft[i + 1].x, draft[i + 1].y),
                strokeWidth = thinStrokePx,
            )
        }
        draft.forEach { a -> drawCircle(accent, anchorRadiusPx, screen(a.x, a.y)) }
    }
}

/** Draw a single control handle: a guide line from the anchor plus its knob. */
private fun DrawScope.drawHandle(
    anchor: Offset,
    handle: Offset,
    color: Color,
    radiusPx: Float,
    thinStrokePx: Float,
) {
    drawLine(color = color, start = anchor, end = handle, strokeWidth = thinStrokePx)
    drawCircle(color = color, radius = radiusPx, center = handle, style = Stroke(width = thinStrokePx))
}
