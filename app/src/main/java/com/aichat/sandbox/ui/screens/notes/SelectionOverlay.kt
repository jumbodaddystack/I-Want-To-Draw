package com.aichat.sandbox.ui.screens.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.FormatPaint
import androidx.compose.material.icons.filled.GroupWork
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Brush as ComposeBrush
import com.aichat.sandbox.data.vector.edit.boolean.PathBoolean
import com.aichat.sandbox.ui.components.notes.AlignmentMath
import com.aichat.sandbox.ui.components.notes.FillStyle
import com.aichat.sandbox.ui.components.notes.StrokeTransform
import com.aichat.sandbox.ui.components.notes.ViewportController
import com.aichat.sandbox.ui.components.notes.ZOrderMath
import kotlin.math.atan2
import kotlin.math.max

/**
 * Selection overlay (sub-phase 1.8).
 *
 * Sits above the [com.aichat.sandbox.ui.components.notes.DrawingSurface]
 * and renders:
 *
 *  - A dashed rectangle around the union bounds of the active selection,
 *    transformed live by the current matrix (so drags reflect as the user
 *    moves their finger).
 *  - Four corner handles for scale and one rotate handle above the top edge.
 *  - A central drag-to-translate hit area that consumes pointer events so
 *    they don't fall through to the surface as stray strokes.
 *  - A floating action menu beneath the bounds with Ask / Convert to text /
 *    Duplicate / Delete / Cut / Copy / Paste. The first two were rendered
 *    disabled in 1.8 and got wired in sub-phase 2.7.
 *
 * Touches outside any of these regions fall through to the AndroidView so
 * the user can start a fresh stroke, which clears the selection via the
 * surface's `selectionShouldClearListener`.
 *
 * **Scope guard:** this overlay only manages drag-to-translate, single-axis
 * scale per corner, and rotation. Pinch-rotate of the selection is
 * explicitly out of scope (parent plan, "Explicit non-goals" for 1.8).
 */
@Composable
fun SelectionOverlay(
    selection: Set<String>,
    worldBounds: FloatArray?,
    viewport: ViewportController?,
    liveMatrix: FloatArray,
    onTransformChanged: (FloatArray) -> Unit,
    onTransformCommitted: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onAsk: () -> Unit,
    onConvertToText: () -> Unit,
    canPaste: Boolean,
    onCannedEdit: ((com.aichat.sandbox.data.notes.CannedEditAction) -> Unit)? = null,
    onSaveAsStamp: (() -> Unit)? = null,
    // Phase 10 — restyle (shapes only), grouping, and arrange actions.
    selectionHasShapes: Boolean = false,
    canGroup: Boolean = false,
    canUngroup: Boolean = false,
    canDistribute: Boolean = false,
    onSetFill: ((Int?) -> Unit)? = null,
    onSetStrokeStyle: ((Int) -> Unit)? = null,
    onGroup: (() -> Unit)? = null,
    onUngroup: (() -> Unit)? = null,
    onAlign: ((AlignmentMath.AlignEdge) -> Unit)? = null,
    onDistribute: ((AlignmentMath.Axis) -> Unit)? = null,
    onReorder: ((ZOrderMath.Op) -> Unit)? = null,
    // Phase 12.3/12.4 — node editing (single path selected) + convert.
    canEditNodes: Boolean = false,
    onEditNodes: (() -> Unit)? = null,
    canConvertToPath: Boolean = false,
    onConvertToPath: (() -> Unit)? = null,
    // Phase 15.2 — stroke → filled outline path.
    canOutlineStroke: Boolean = false,
    onOutlineStroke: (() -> Unit)? = null,
    // Phase 13.1 — boolean ops; 13.2 — gradient fills; 13.3 — style tools.
    canCombine: Boolean = false,
    onCombine: ((PathBoolean.Op) -> Unit)? = null,
    // Phase 17.5 — concatenate style-compatible paths into one (holes kept).
    canMergePaths: Boolean = false,
    onMergePaths: (() -> Unit)? = null,
    // Phase 17.5 follow-on — one-tap tidy (simplify + snap + merge).
    canTidy: Boolean = false,
    onTidy: (() -> Unit)? = null,
    onSetGradient: ((FillStyle.Gradient) -> Unit)? = null,
    canCopyStyle: Boolean = false,
    onCopyStyle: (() -> Unit)? = null,
    canPasteStyle: Boolean = false,
    onPasteStyle: (() -> Unit)? = null,
    canPickColor: Boolean = false,
    onPickColor: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (selection.isEmpty() || worldBounds == null || viewport == null) return

    val onChangeUpdated by rememberUpdatedState(onTransformChanged)
    val onCommitUpdated by rememberUpdatedState(onTransformCommitted)
    // Wrap the live matrix in rememberUpdatedState so the long-lived
    // pointerInput coroutines always read the latest snapshot when a
    // gesture finally arrives (Compose otherwise captures the value at the
    // composition where pointerInput was first introduced).
    val liveMatrixRef by rememberUpdatedState(liveMatrix)

    // Transform the world bounds rect through the live matrix to get the
    // displayed corners. Order: TL, TR, BR, BL. World coords.
    val rawCorners = remember(worldBounds, liveMatrix) {
        cornersOf(worldBounds, liveMatrix)
    }
    val screenCorners = remember(rawCorners, viewport.scale, viewport.offsetX, viewport.offsetY) {
        Array(4) {
            Offset(
                viewport.worldToScreenX(rawCorners[it].x),
                viewport.worldToScreenY(rawCorners[it].y),
            )
        }
    }
    val screenRect = screenBoundsOf(screenCorners)

    val density = LocalDensity.current
    val handleSizePx = with(density) { HANDLE_DP.toPx() }
    val rotateOffsetPx = with(density) { ROTATE_OFFSET_DP.toPx() }
    val menuPaddingPx = with(density) { MENU_GAP_DP.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                color = SELECTION_OUTLINE,
                topLeft = Offset(screenRect.left, screenRect.top),
                size = androidx.compose.ui.geometry.Size(
                    width = screenRect.right - screenRect.left,
                    height = screenRect.bottom - screenRect.top,
                ),
                style = Stroke(
                    width = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f),
                ),
            )
        }

        // Central translate hit area — consumes pointer events so a drag
        // inside the selection doesn't leak through to the canvas.
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        screenRect.left.toInt(),
                        screenRect.top.toInt(),
                    )
                }
                .size(
                    width = with(density) { (screenRect.right - screenRect.left).toDp() },
                    height = with(density) { (screenRect.bottom - screenRect.top).toDp() },
                )
                .pointerInput(selection, worldBounds) {
                    var startMatrix = StrokeTransform.IDENTITY
                    detectDragGestures(
                        onDragStart = { startMatrix = liveMatrixRef.copyOf() },
                        onDrag = { change, drag ->
                            change.consume()
                            val worldDx = drag.x / max(viewport.scale, MIN_DIV_SCALE)
                            val worldDy = drag.y / max(viewport.scale, MIN_DIV_SCALE)
                            startMatrix = StrokeTransform.multiply(
                                StrokeTransform.translation(worldDx, worldDy),
                                startMatrix,
                            )
                            onChangeUpdated(startMatrix)
                        },
                        onDragEnd = { onCommitUpdated() },
                        onDragCancel = { onCommitUpdated() },
                    )
                },
        )

        // Scale handles (4 corners). Each pins the opposite corner as
        // anchor and rescales so the dragged corner tracks the finger.
        for (cornerIdx in 0..3) {
            val anchorIdx = (cornerIdx + 2) % 4
            val handleCenter = screenCorners[cornerIdx]
            ScaleHandle(
                centerPx = handleCenter,
                sizePx = handleSizePx,
                density = density,
                onDrag = { dx, dy ->
                    val anchor = rawCorners[anchorIdx]
                    val dragged = rawCorners[cornerIdx]
                    val worldDx = dx / max(viewport.scale, MIN_DIV_SCALE)
                    val worldDy = dy / max(viewport.scale, MIN_DIV_SCALE)
                    val newDraggedX = dragged.x + worldDx
                    val newDraggedY = dragged.y + worldDy
                    val origDx = dragged.x - anchor.x
                    val origDy = dragged.y - anchor.y
                    if (origDx == 0f || origDy == 0f) return@ScaleHandle
                    val sx = ((newDraggedX - anchor.x) / origDx).coerceIn(MIN_SCALE, MAX_SCALE)
                    val sy = ((newDraggedY - anchor.y) / origDy).coerceIn(MIN_SCALE, MAX_SCALE)
                    val scale = StrokeTransform.scaleAround(sx, sy, anchor.x, anchor.y)
                    onChangeUpdated(StrokeTransform.multiply(scale, liveMatrixRef))
                },
                onEnd = { onCommitUpdated() },
            )
        }

        // Phase 6.1 — edge handles (4 sides) for single-axis scaling. The
        // anchor edge is the opposite side; sx or sy is locked to 1f so the
        // selection only scales along the dragged axis.
        for (edgeIdx in 0..3) {
            val (cornerA, cornerB) = when (edgeIdx) {
                0 -> 0 to 1   // top
                1 -> 1 to 2   // right
                2 -> 3 to 2   // bottom
                else -> 0 to 3 // left
            }
            val midScreen = Offset(
                (screenCorners[cornerA].x + screenCorners[cornerB].x) * 0.5f,
                (screenCorners[cornerA].y + screenCorners[cornerB].y) * 0.5f,
            )
            val horizontal = edgeIdx == 0 || edgeIdx == 2
            val sourceWorldEdge = when (edgeIdx) {
                0 -> rawCorners[0].y
                1 -> rawCorners[1].x
                2 -> rawCorners[2].y
                else -> rawCorners[3].x
            }
            val anchorWorldEdge = when (edgeIdx) {
                0 -> rawCorners[3].y   // top anchored on bottom
                1 -> rawCorners[0].x   // right anchored on left
                2 -> rawCorners[0].y   // bottom anchored on top
                else -> rawCorners[1].x // left anchored on right
            }
            ScaleHandle(
                centerPx = midScreen,
                sizePx = handleSizePx,
                density = density,
                onDrag = { dx, dy ->
                    val worldDx = dx / max(viewport.scale, MIN_DIV_SCALE)
                    val worldDy = dy / max(viewport.scale, MIN_DIV_SCALE)
                    if (horizontal) {
                        val orig = sourceWorldEdge - anchorWorldEdge
                        if (orig == 0f) return@ScaleHandle
                        val nu = sourceWorldEdge + worldDy - anchorWorldEdge
                        val sy = (nu / orig).coerceIn(MIN_SCALE, MAX_SCALE)
                        val mat = StrokeTransform.scaleAround(1f, sy, 0f, anchorWorldEdge)
                        onChangeUpdated(StrokeTransform.multiply(mat, liveMatrixRef))
                    } else {
                        val orig = sourceWorldEdge - anchorWorldEdge
                        if (orig == 0f) return@ScaleHandle
                        val nu = sourceWorldEdge + worldDx - anchorWorldEdge
                        val sx = (nu / orig).coerceIn(MIN_SCALE, MAX_SCALE)
                        val mat = StrokeTransform.scaleAround(sx, 1f, anchorWorldEdge, 0f)
                        onChangeUpdated(StrokeTransform.multiply(mat, liveMatrixRef))
                    }
                },
                onEnd = { onCommitUpdated() },
            )
        }

        // Rotate handle: sits centred above the top edge in screen space.
        val top = screenCorners[0]
        val topRight = screenCorners[1]
        val midTopX = (top.x + topRight.x) * 0.5f
        val midTopY = (top.y + topRight.y) * 0.5f
        val rotateCenter = Offset(midTopX, midTopY - rotateOffsetPx)
        // Centre of the transformed bounds (in world space) — rotation pivot.
        val centerWorld = Offset(
            (rawCorners[0].x + rawCorners[2].x) * 0.5f,
            (rawCorners[0].y + rawCorners[2].y) * 0.5f,
        )
        RotateHandle(
            centerPx = rotateCenter,
            sizePx = handleSizePx,
            density = density,
            pivotScreen = Offset(
                viewport.worldToScreenX(centerWorld.x),
                viewport.worldToScreenY(centerWorld.y),
            ),
            onRotate = { newAngleRad, baseMatrix ->
                val rotation = StrokeTransform.rotationAround(
                    newAngleRad,
                    centerWorld.x,
                    centerWorld.y,
                )
                onChangeUpdated(StrokeTransform.multiply(rotation, baseMatrix))
            },
            onEnd = { onCommitUpdated() },
            currentMatrix = { liveMatrixRef },
        )

        // Floating menu under the selection, clamped fully on-screen. The
        // old offset-based placement let the row start at the selection's
        // left edge and run past the right side of the screen; Row then
        // squeezed the clipped trailing buttons' labels into one-letter-per-
        // line wraps, which ballooned the menu surface to several times the
        // height of its visible content.
        val menuMarginPx = with(density) { MENU_MARGIN_DP.roundToPx() }
        FloatingSelectionMenu(
            onCannedEdit = onCannedEdit,
            onSaveAsStamp = onSaveAsStamp,
            canPaste = canPaste,
            onAsk = onAsk,
            onConvertToText = onConvertToText,
            onDuplicate = onDuplicate,
            onDelete = onDelete,
            onCut = onCut,
            onCopy = onCopy,
            onPaste = onPaste,
            selectionHasShapes = selectionHasShapes,
            canGroup = canGroup,
            canUngroup = canUngroup,
            canDistribute = canDistribute,
            onSetFill = onSetFill,
            onSetStrokeStyle = onSetStrokeStyle,
            onGroup = onGroup,
            onUngroup = onUngroup,
            onAlign = onAlign,
            onDistribute = onDistribute,
            onReorder = onReorder,
            canEditNodes = canEditNodes,
            onEditNodes = onEditNodes,
            canConvertToPath = canConvertToPath,
            onConvertToPath = onConvertToPath,
            canOutlineStroke = canOutlineStroke,
            onOutlineStroke = onOutlineStroke,
            canCombine = canCombine,
            onCombine = onCombine,
            canMergePaths = canMergePaths,
            onMergePaths = onMergePaths,
            canTidy = canTidy,
            onTidy = onTidy,
            onSetGradient = onSetGradient,
            canCopyStyle = canCopyStyle,
            onCopyStyle = onCopyStyle,
            canPasteStyle = canPasteStyle,
            onPasteStyle = onPasteStyle,
            canPickColor = canPickColor,
            onPickColor = onPickColor,
            modifier = Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(
                        maxWidth = (constraints.maxWidth - 2 * menuMarginPx)
                            .coerceAtLeast(0),
                        maxHeight = constraints.maxHeight,
                    )
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    val x = screenRect.left.toInt()
                        .coerceAtMost(constraints.maxWidth - placeable.width - menuMarginPx)
                        .coerceAtLeast(menuMarginPx)
                    // Prefer below the selection; flip above it when there's
                    // no room left before the bottom of the canvas.
                    val below = (screenRect.bottom + menuPaddingPx).toInt()
                    val y = if (below + placeable.height + menuMarginPx > constraints.maxHeight) {
                        ((screenRect.top - menuPaddingPx).toInt() - placeable.height)
                            .coerceAtLeast(menuMarginPx)
                    } else {
                        below
                    }
                    placeable.place(x, y)
                }
            },
        )
    }
}

@Composable
private fun ScaleHandle(
    centerPx: Offset,
    sizePx: Float,
    density: androidx.compose.ui.unit.Density,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onEnd: () -> Unit,
) {
    val halfPx = sizePx * 0.5f
    val onDragRef by rememberUpdatedState(onDrag)
    val onEndRef by rememberUpdatedState(onEnd)
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (centerPx.x - halfPx).toInt(),
                    (centerPx.y - halfPx).toInt(),
                )
            }
            .size(with(density) { sizePx.toDp() })
            .clip(CircleShape)
            .background(HANDLE_FILL)
            .pointerInput(centerPx) {
                detectDragGestures(
                    onDrag = { change, drag ->
                        change.consume()
                        onDragRef(drag.x, drag.y)
                    },
                    onDragEnd = { onEndRef() },
                    onDragCancel = { onEndRef() },
                )
            },
    )
}

@Composable
private fun RotateHandle(
    centerPx: Offset,
    sizePx: Float,
    density: androidx.compose.ui.unit.Density,
    pivotScreen: Offset,
    onRotate: (radians: Float, baseMatrix: FloatArray) -> Unit,
    onEnd: () -> Unit,
    currentMatrix: () -> FloatArray,
) {
    val halfPx = sizePx * 0.5f
    val onRotateRef by rememberUpdatedState(onRotate)
    val onEndRef by rememberUpdatedState(onEnd)
    val currentMatrixRef by rememberUpdatedState(currentMatrix)
    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (centerPx.x - halfPx).toInt(),
                    (centerPx.y - halfPx).toInt(),
                )
            }
            .size(with(density) { sizePx.toDp() })
            .clip(CircleShape)
            .background(ROTATE_HANDLE_FILL)
            .pointerInput(centerPx, pivotScreen) {
                var startAngle = 0f
                var baseMatrix = StrokeTransform.IDENTITY
                detectDragGestures(
                    onDragStart = { startScreen ->
                        // Track the absolute angle from pivot to the user's
                        // finger; rotations apply the delta from this start.
                        startAngle = atan2(
                            startScreen.y + centerPx.y - halfPx - pivotScreen.y,
                            startScreen.x + centerPx.x - halfPx - pivotScreen.x,
                        )
                        baseMatrix = currentMatrixRef().copyOf()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        // change.position is local to this handle's top-left.
                        val absX = change.position.x + centerPx.x - halfPx
                        val absY = change.position.y + centerPx.y - halfPx
                        val angle = atan2(absY - pivotScreen.y, absX - pivotScreen.x)
                        val delta = angle - startAngle
                        onRotateRef(delta, baseMatrix)
                    },
                    onDragEnd = { onEndRef() },
                    onDragCancel = { onEndRef() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.RotateRight,
            contentDescription = "Rotate",
            tint = Color.White,
            modifier = Modifier.size(with(density) { (sizePx * 0.6f).toDp() }),
        )
    }
}

@Composable
private fun FloatingSelectionMenu(
    canPaste: Boolean,
    onAsk: () -> Unit,
    onConvertToText: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onCut: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onCannedEdit: ((com.aichat.sandbox.data.notes.CannedEditAction) -> Unit)? = null,
    onSaveAsStamp: (() -> Unit)? = null,
    selectionHasShapes: Boolean = false,
    canGroup: Boolean = false,
    canUngroup: Boolean = false,
    canDistribute: Boolean = false,
    onSetFill: ((Int?) -> Unit)? = null,
    onSetStrokeStyle: ((Int) -> Unit)? = null,
    onGroup: (() -> Unit)? = null,
    onUngroup: (() -> Unit)? = null,
    onAlign: ((AlignmentMath.AlignEdge) -> Unit)? = null,
    onDistribute: ((AlignmentMath.Axis) -> Unit)? = null,
    onReorder: ((ZOrderMath.Op) -> Unit)? = null,
    canEditNodes: Boolean = false,
    onEditNodes: (() -> Unit)? = null,
    canConvertToPath: Boolean = false,
    onConvertToPath: (() -> Unit)? = null,
    canOutlineStroke: Boolean = false,
    onOutlineStroke: (() -> Unit)? = null,
    canCombine: Boolean = false,
    onCombine: ((PathBoolean.Op) -> Unit)? = null,
    canMergePaths: Boolean = false,
    onMergePaths: (() -> Unit)? = null,
    canTidy: Boolean = false,
    onTidy: (() -> Unit)? = null,
    onSetGradient: ((FillStyle.Gradient) -> Unit)? = null,
    canCopyStyle: Boolean = false,
    onCopyStyle: (() -> Unit)? = null,
    canPasteStyle: Boolean = false,
    onPasteStyle: (() -> Unit)? = null,
    canPickColor: Boolean = false,
    onPickColor: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // V8 fix — the strip used to lay 20+ equal-weight buttons in one
    // horizontally-scrolled row, so the everyday actions (delete, copy, …)
    // sat shoulder-to-shoulder with rare ones (boolean combine, distribute,
    // outline ink) and you had to swipe to find anything. Now a small set of
    // primary actions stays inline and everything situational folds behind a
    // single "More" overflow, giving the menu a clear hierarchy.
    val hasSecondary = onCannedEdit != null ||
        onSaveAsStamp != null ||
        (canEditNodes && onEditNodes != null) ||
        (canConvertToPath && onConvertToPath != null) ||
        (canOutlineStroke && onOutlineStroke != null) ||
        (canCombine && onCombine != null) ||
        (canMergePaths && onMergePaths != null) ||
        (canTidy && onTidy != null) ||
        (selectionHasShapes && onSetFill != null && onSetStrokeStyle != null) ||
        (canPickColor && onPickColor != null) ||
        (canCopyStyle && onCopyStyle != null) ||
        onPasteStyle != null ||
        (canGroup && onGroup != null) ||
        (canUngroup && onUngroup != null) ||
        (onAlign != null && onDistribute != null && onReorder != null)

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        // Scrollable as a safety valve, but with the overflow pulled out the
        // primary row fits without swiping in the common case.
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Primary actions — the everyday verbs, always inline.
            MenuButton(
                icon = Icons.Filled.TextFields,
                label = "To text",
                onClick = onConvertToText,
            )
            MenuButton(icon = Icons.Filled.FileCopy, label = "Duplicate", onClick = onDuplicate)
            MenuButton(icon = Icons.Filled.Delete, label = "Delete", onClick = onDelete)
            MenuButton(icon = Icons.Filled.ContentCut, label = "Cut", onClick = onCut)
            MenuButton(icon = Icons.Outlined.ContentCopy, label = "Copy", onClick = onCopy)
            MenuButton(
                icon = Icons.Filled.ContentPaste,
                label = "Paste",
                enabled = canPaste,
                onClick = onPaste,
            )
            // Everything situational lives behind one overflow.
            if (hasSecondary) {
                SelectionMoreMenu(
                    onCannedEdit = onCannedEdit,
                    onSaveAsStamp = onSaveAsStamp,
                    selectionHasShapes = selectionHasShapes,
                    canGroup = canGroup,
                    canUngroup = canUngroup,
                    canDistribute = canDistribute,
                    onSetFill = onSetFill,
                    onSetStrokeStyle = onSetStrokeStyle,
                    onGroup = onGroup,
                    onUngroup = onUngroup,
                    onAlign = onAlign,
                    onDistribute = onDistribute,
                    onReorder = onReorder,
                    canEditNodes = canEditNodes,
                    onEditNodes = onEditNodes,
                    canConvertToPath = canConvertToPath,
                    onConvertToPath = onConvertToPath,
                    canOutlineStroke = canOutlineStroke,
                    onOutlineStroke = onOutlineStroke,
                    canCombine = canCombine,
                    onCombine = onCombine,
                    canMergePaths = canMergePaths,
                    onMergePaths = onMergePaths,
                    canTidy = canTidy,
                    onTidy = onTidy,
                    onSetGradient = onSetGradient,
                    canPickColor = canPickColor,
                    onPickColor = onPickColor,
                    canCopyStyle = canCopyStyle,
                    onCopyStyle = onCopyStyle,
                    canPasteStyle = canPasteStyle,
                    onPasteStyle = onPasteStyle,
                )
            }
        }
    }
}

/**
 * V8 — the selection menu's overflow. Folds every situational action
 * (canned AI edits, path/style tooling, boolean combine, grouping, arrange)
 * into one dropdown so the inline strip stays short. Grouped actions that
 * used to be their own popovers (Combine / Style / Arrange) are flattened
 * inline here under subheaders — a single scrollable menu instead of nested
 * popovers.
 */
@Composable
private fun SelectionMoreMenu(
    onCannedEdit: ((com.aichat.sandbox.data.notes.CannedEditAction) -> Unit)?,
    onSaveAsStamp: (() -> Unit)?,
    selectionHasShapes: Boolean,
    canGroup: Boolean,
    canUngroup: Boolean,
    canDistribute: Boolean,
    onSetFill: ((Int?) -> Unit)?,
    onSetStrokeStyle: ((Int) -> Unit)?,
    onGroup: (() -> Unit)?,
    onUngroup: (() -> Unit)?,
    onAlign: ((AlignmentMath.AlignEdge) -> Unit)?,
    onDistribute: ((AlignmentMath.Axis) -> Unit)?,
    onReorder: ((ZOrderMath.Op) -> Unit)?,
    canEditNodes: Boolean,
    onEditNodes: (() -> Unit)?,
    canConvertToPath: Boolean,
    onConvertToPath: (() -> Unit)?,
    canOutlineStroke: Boolean,
    onOutlineStroke: (() -> Unit)?,
    canCombine: Boolean,
    onCombine: ((PathBoolean.Op) -> Unit)?,
    canMergePaths: Boolean,
    onMergePaths: (() -> Unit)?,
    canTidy: Boolean,
    onTidy: (() -> Unit)?,
    onSetGradient: ((FillStyle.Gradient) -> Unit)?,
    canPickColor: Boolean,
    onPickColor: (() -> Unit)?,
    canCopyStyle: Boolean,
    onCopyStyle: (() -> Unit)?,
    canPasteStyle: Boolean,
    onPasteStyle: (() -> Unit)?,
) {
    var expanded by remember { mutableStateOf(false) }
    fun <T> fire(action: (T) -> Unit, arg: T) { action(arg); expanded = false }
    fun fire(action: () -> Unit) { action(); expanded = false }
    Box {
        MenuButton(icon = Icons.Filled.MoreHoriz, label = "More", onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // Canned AI / local edits.
            if (onCannedEdit != null) {
                MenuSectionLabel("Edit")
                DropdownMenuItem(
                    text = { Text("Clean up") },
                    leadingIcon = { Icon(Icons.Filled.AutoFixHigh, contentDescription = null) },
                    onClick = { fire({ onCannedEdit(com.aichat.sandbox.data.notes.CannedEditAction.CLEAN_UP) }) },
                )
                DropdownMenuItem(
                    text = { Text("Straighten") },
                    leadingIcon = { Icon(Icons.Filled.RotateRight, contentDescription = null) },
                    onClick = { fire({ onCannedEdit(com.aichat.sandbox.data.notes.CannedEditAction.STRAIGHTEN) }) },
                )
                DropdownMenuItem(
                    text = { Text("Auto-shape") },
                    leadingIcon = { Icon(Icons.Filled.Category, contentDescription = null) },
                    onClick = { fire({ onCannedEdit(com.aichat.sandbox.data.notes.CannedEditAction.AUTO_SHAPE) }) },
                )
            }

            // Path / geometry tooling.
            val hasPathTools = (canEditNodes && onEditNodes != null) ||
                (canConvertToPath && onConvertToPath != null) ||
                (canOutlineStroke && onOutlineStroke != null) ||
                (canCombine && onCombine != null) ||
                (canMergePaths && onMergePaths != null) ||
                (canTidy && onTidy != null)
            if (hasPathTools) {
                MenuSectionLabel("Shape")
                if (canEditNodes && onEditNodes != null) {
                    DropdownMenuItem(
                        text = { Text("Edit nodes") },
                        leadingIcon = { Icon(Icons.Filled.Polyline, contentDescription = null) },
                        onClick = { fire(onEditNodes) },
                    )
                }
                if (canConvertToPath && onConvertToPath != null) {
                    DropdownMenuItem(
                        text = { Text("To path") },
                        leadingIcon = { Icon(Icons.Filled.Timeline, contentDescription = null) },
                        onClick = { fire(onConvertToPath) },
                    )
                }
                if (canOutlineStroke && onOutlineStroke != null) {
                    DropdownMenuItem(
                        text = { Text("Outline ink") },
                        leadingIcon = { Icon(Icons.Filled.Gesture, contentDescription = null) },
                        onClick = { fire(onOutlineStroke) },
                    )
                }
                if (canMergePaths && onMergePaths != null) {
                    DropdownMenuItem(
                        text = { Text("Merge") },
                        leadingIcon = { Icon(Icons.Filled.MergeType, contentDescription = null) },
                        onClick = { fire(onMergePaths) },
                    )
                }
                if (canTidy && onTidy != null) {
                    DropdownMenuItem(
                        text = { Text("Tidy") },
                        leadingIcon = { Icon(Icons.Filled.AutoFixHigh, contentDescription = null) },
                        onClick = { fire(onTidy) },
                    )
                }
                if (canCombine && onCombine != null) {
                    MenuSectionLabel("Combine")
                    DropdownMenuItem(text = { Text("Union") }, onClick = { fire(onCombine, PathBoolean.Op.UNION) })
                    DropdownMenuItem(text = { Text("Subtract") }, onClick = { fire(onCombine, PathBoolean.Op.SUBTRACT) })
                    DropdownMenuItem(text = { Text("Intersect") }, onClick = { fire(onCombine, PathBoolean.Op.INTERSECT) })
                    DropdownMenuItem(text = { Text("Exclude") }, onClick = { fire(onCombine, PathBoolean.Op.EXCLUDE) })
                }
            }

            // Style — fill swatches + gradients + line style.
            if (selectionHasShapes && onSetFill != null && onSetStrokeStyle != null) {
                MenuSectionLabel("Style")
                DropdownMenuItem(
                    text = { Text("No fill") },
                    onClick = { fire({ onSetFill(null) }) },
                )
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (swatch in FILL_SWATCHES) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(swatch))
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                .clickable { fire({ onSetFill(swatch) }) },
                        )
                    }
                }
                if (onSetGradient != null) {
                    for (radial in listOf(false, true)) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (radial) "Radial" else "Linear",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            for ((start, end) in GRADIENT_PRESETS) {
                                val brush = if (radial) {
                                    ComposeBrush.radialGradient(listOf(Color(start), Color(end)))
                                } else {
                                    ComposeBrush.linearGradient(listOf(Color(start), Color(end)))
                                }
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(brush)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                        .clickable {
                                            fire({
                                                onSetGradient(
                                                    if (radial) FillStyle.radial(start, end)
                                                    else FillStyle.linear(start, end),
                                                )
                                            })
                                        },
                                )
                            }
                        }
                    }
                }
                DropdownMenuItem(
                    text = { Text("Solid line") },
                    onClick = { fire({ onSetStrokeStyle(STROKE_STYLE_SOLID) }) },
                )
                DropdownMenuItem(
                    text = { Text("Dashed line") },
                    onClick = { fire({ onSetStrokeStyle(STROKE_STYLE_DASHED) }) },
                )
                DropdownMenuItem(
                    text = { Text("Dotted line") },
                    onClick = { fire({ onSetStrokeStyle(STROKE_STYLE_DOTTED) }) },
                )
            }

            // Eyedropper + style copy/paste + save-as-stamp.
            val hasStyleClipboard = (canPickColor && onPickColor != null) ||
                (canCopyStyle && onCopyStyle != null) ||
                onPasteStyle != null ||
                onSaveAsStamp != null
            if (hasStyleClipboard) {
                MenuSectionLabel("Tools")
                if (canPickColor && onPickColor != null) {
                    DropdownMenuItem(
                        text = { Text("Pick colour") },
                        leadingIcon = { Icon(Icons.Filled.Colorize, contentDescription = null) },
                        onClick = { fire(onPickColor) },
                    )
                }
                if (canCopyStyle && onCopyStyle != null) {
                    DropdownMenuItem(
                        text = { Text("Copy style") },
                        leadingIcon = { Icon(Icons.Filled.Brush, contentDescription = null) },
                        onClick = { fire(onCopyStyle) },
                    )
                }
                if (onPasteStyle != null) {
                    DropdownMenuItem(
                        text = { Text("Paste style") },
                        leadingIcon = { Icon(Icons.Filled.FormatPaint, contentDescription = null) },
                        enabled = canPasteStyle,
                        onClick = { fire(onPasteStyle) },
                    )
                }
                if (onSaveAsStamp != null) {
                    DropdownMenuItem(
                        text = { Text("Save stamp") },
                        leadingIcon = { Icon(Icons.Filled.Bookmark, contentDescription = null) },
                        onClick = { fire(onSaveAsStamp) },
                    )
                }
            }

            // Group / arrange.
            val hasArrange = (canGroup && onGroup != null) ||
                (canUngroup && onUngroup != null) ||
                (onAlign != null && onDistribute != null && onReorder != null)
            if (hasArrange) {
                MenuSectionLabel("Arrange")
                if (canGroup && onGroup != null) {
                    DropdownMenuItem(
                        text = { Text("Group") },
                        leadingIcon = { Icon(Icons.Filled.GroupWork, contentDescription = null) },
                        onClick = { fire(onGroup) },
                    )
                }
                if (canUngroup && onUngroup != null) {
                    DropdownMenuItem(
                        text = { Text("Ungroup") },
                        leadingIcon = { Icon(Icons.Filled.LinkOff, contentDescription = null) },
                        onClick = { fire(onUngroup) },
                    )
                }
                if (onAlign != null && onDistribute != null && onReorder != null) {
                    DropdownMenuItem(text = { Text("Align left") }, onClick = { fire(onAlign, AlignmentMath.AlignEdge.LEFT) })
                    DropdownMenuItem(text = { Text("Align centre") }, onClick = { fire(onAlign, AlignmentMath.AlignEdge.CENTER_H) })
                    DropdownMenuItem(text = { Text("Align right") }, onClick = { fire(onAlign, AlignmentMath.AlignEdge.RIGHT) })
                    DropdownMenuItem(text = { Text("Align top") }, onClick = { fire(onAlign, AlignmentMath.AlignEdge.TOP) })
                    DropdownMenuItem(text = { Text("Align middle") }, onClick = { fire(onAlign, AlignmentMath.AlignEdge.CENTER_V) })
                    DropdownMenuItem(text = { Text("Align bottom") }, onClick = { fire(onAlign, AlignmentMath.AlignEdge.BOTTOM) })
                    DropdownMenuItem(
                        text = { Text("Distribute horizontally") },
                        enabled = canDistribute,
                        onClick = { fire(onDistribute, AlignmentMath.Axis.HORIZONTAL) },
                    )
                    DropdownMenuItem(
                        text = { Text("Distribute vertically") },
                        enabled = canDistribute,
                        onClick = { fire(onDistribute, AlignmentMath.Axis.VERTICAL) },
                    )
                    DropdownMenuItem(text = { Text("Bring to front") }, onClick = { fire(onReorder, ZOrderMath.Op.BRING_TO_FRONT) })
                    DropdownMenuItem(text = { Text("Bring forward") }, onClick = { fire(onReorder, ZOrderMath.Op.BRING_FORWARD) })
                    DropdownMenuItem(text = { Text("Send backward") }, onClick = { fire(onReorder, ZOrderMath.Op.SEND_BACKWARD) })
                    DropdownMenuItem(text = { Text("Send to back") }, onClick = { fire(onReorder, ZOrderMath.Op.SEND_TO_BACK) })
                }
            }
        }
    }
}

/** Small uppercase subheader separating sections in the overflow menu. */
@Composable
private fun MenuSectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun MenuButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        onClick = onClick,
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = tint)
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
            )
        }
    }
}

private data class ScreenRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

private fun screenBoundsOf(corners: Array<Offset>): ScreenRect {
    var minX = corners[0].x
    var minY = corners[0].y
    var maxX = minX
    var maxY = minY
    for (i in 1 until corners.size) {
        val c = corners[i]
        if (c.x < minX) minX = c.x else if (c.x > maxX) maxX = c.x
        if (c.y < minY) minY = c.y else if (c.y > maxY) maxY = c.y
    }
    return ScreenRect(minX, minY, maxX, maxY)
}

/**
 * Transform the four world-space corners of [worldBounds] through [matrix].
 * Order: TL, TR, BR, BL. After rotation the corners are no longer axis-aligned,
 * which is why we keep all four (the screen bounds are derived as the
 * axis-aligned envelope).
 */
private fun cornersOf(worldBounds: FloatArray, matrix: FloatArray): Array<Offset> {
    val (minX, minY, maxX, maxY) = worldBounds.toCornerTuple()
    val raw = arrayOf(
        floatArrayOf(minX, minY),
        floatArrayOf(maxX, minY),
        floatArrayOf(maxX, maxY),
        floatArrayOf(minX, maxY),
    )
    val out = Array(4) {
        val p = raw[it]
        Offset(
            matrix[0] * p[0] + matrix[1] * p[1] + matrix[2],
            matrix[3] * p[0] + matrix[4] * p[1] + matrix[5],
        )
    }
    return out
}

private data class CornerTuple(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)
private fun FloatArray.toCornerTuple() = CornerTuple(this[0], this[1], this[2], this[3])

// Phase 10.3 — mirror ShapeCodec's STROKE_STYLE_* bytes (kept as Ints here
// so the Compose layer stays free of the codec dependency direction).
private const val STROKE_STYLE_SOLID = 0
private const val STROKE_STYLE_DASHED = 1
private const val STROKE_STYLE_DOTTED = 2

/** Quick-fill palette for the Style popover — translucent so outlines stay legible. */
private val FILL_SWATCHES: List<Int> = listOf(
    0x40000000,            // 25% black
    0x40D62828.toInt(),    // 25% red
    0x402463EB.toInt(),    // 25% blue
    0x40109F5C.toInt(),    // 25% green
    0x40FF9F1C.toInt(),    // 25% orange
    0xFFFFF59D.toInt(),    // sticky-note yellow (opaque)
)

/** Phase 13.2 — gradient preset endpoints (opaque so stickies stay solid). */
private val GRADIENT_PRESETS: List<Pair<Int, Int>> = listOf(
    0xFF2463EB.toInt() to 0xFF9333EA.toInt(), // blue → purple
    0xFFD62828.toInt() to 0xFFFF9F1C.toInt(), // red → orange
    0xFF109F5C.toInt() to 0xFF06B6D4.toInt(), // green → teal
    0xFFFDE047.toInt() to 0xFFF472B6.toInt(), // yellow → pink
)

private val SELECTION_OUTLINE = Color(0xCC1E88E5)
private val HANDLE_FILL = Color(0xFF1E88E5)
private val ROTATE_HANDLE_FILL = Color(0xFF1976D2)

private val HANDLE_DP = 18.dp
private val ROTATE_OFFSET_DP = 40.dp
private val MENU_GAP_DP = 16.dp
private val MENU_MARGIN_DP = 8.dp

private const val MIN_DIV_SCALE = 0.01f
private const val MIN_SCALE = 0.1f
private const val MAX_SCALE = 12f
