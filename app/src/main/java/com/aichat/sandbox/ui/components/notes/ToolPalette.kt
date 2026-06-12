package com.aichat.sandbox.ui.components.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Pentagon
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Bottom tool palette (sub-phase 1.6).
 *
 * Layout: tool row → divider → color swatch row + width slider. Disabled
 * tools (lasso / text) render greyed-out so the user can see what's coming.
 */
@Composable
fun ToolPalette(
    state: ToolPaletteState,
    modifier: Modifier = Modifier,
    onPickCustomColor: () -> Unit = {},
    snapMask: Int = Snap.MASK_ANGLE or Snap.MASK_ENDPOINT,
    onToggleSnap: (Int) -> Unit = { },
    // Phase 10.2 — opens the colour picker targeting the shape-fill slot.
    onPickShapeFillColor: () -> Unit = {},
) {
    val selected = state.selected
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolRow(state = state)

            HorizontalDivider(thickness = 0.5.dp)

            if (selected.isInk) {
                InkConfigRow(state = state, onPickCustomColor = onPickCustomColor)
            } else if (selected.isEraser) {
                EraserConfigRow(state = state)
            } else if (selected.isLasso) {
                LassoHintRow()
            } else if (selected.isText) {
                TextHintRow()
            } else if (selected.isShape) {
                // Phase 6.2 — shape tools reuse the active ink color + width.
                InkConfigRow(state = state, onPickCustomColor = onPickCustomColor)
                // Phase 10.2/10.3 — fill + outline style for new shapes.
                ShapeStyleRow(state = state, onPickShapeFillColor = onPickShapeFillColor)
                SnapChipRow(snapMask = snapMask, onToggle = onToggleSnap)
            } else if (selected.isFrame) {
                FrameHintRow()
            } else if (selected.isSticky) {
                StickyConfigRow(state = state)
            } else if (selected.isConnector) {
                // Sub-phase 11.2 — connectors reuse the active ink colour + width.
                InkConfigRow(state = state, onPickCustomColor = onPickCustomColor)
                ConnectorHintRow()
            } else if (selected.isPathPen) {
                // Sub-phase 12.2 — the pen shares the ink colour/width row and
                // the shape fill / line-style row (paths encode both).
                // 12.5 adds cap / join chips.
                InkConfigRow(state = state, onPickCustomColor = onPickCustomColor)
                ShapeStyleRow(state = state, onPickShapeFillColor = onPickShapeFillColor)
                PathCapJoinRow(state = state)
            }
        }
    }
}

/**
 * One-row tool bar: the three ink tools, a grouped eraser button, lasso,
 * text, a grouped shapes button and the frame tool. Every button is icon-only
 * (the old 13 text-chips scrolled off-screen and hid most tools); equal
 * weights keep the whole roster visible on a 360 dp phone. Grouped buttons
 * re-select their last-used variant on tap and open a picker on long-press
 * (or on tap while already active).
 */
@Composable
private fun ToolRow(state: ToolPaletteState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolIconButton(state, Tool.PEN, Modifier.weight(1f))
        ToolIconButton(state, Tool.HIGHLIGHTER, Modifier.weight(1f))
        ToolIconButton(state, Tool.PENCIL, Modifier.weight(1f))
        GroupedToolButton(
            state = state,
            groupTools = listOf(Tool.ERASER_STROKE, Tool.ERASER_AREA),
            lastUsed = state.lastEraserTool,
            // One fixed glyph for the group: the variant distinction lives in
            // the picker, where the two get their proper names.
            groupIcon = { Icons.Outlined.Backspace },
            groupDescription = "Eraser",
            modifier = Modifier.weight(1f),
        )
        ToolIconButton(state, Tool.LASSO, Modifier.weight(1f))
        ToolIconButton(state, Tool.TEXT, Modifier.weight(1f))
        GroupedToolButton(
            state = state,
            // 12.2 — the vector pen joins the shapes roster.
            groupTools = listOf(
                Tool.LINE, Tool.RECT, Tool.ELLIPSE, Tool.ARROW, Tool.POLYGON, Tool.PATH_PEN,
            ),
            lastUsed = state.lastShapeTool,
            // The group button wears the last-used shape's glyph so the next
            // tap's outcome is visible before tapping.
            groupIcon = { state.lastShapeTool.icon() },
            groupDescription = "Shapes",
            modifier = Modifier.weight(1f),
        )
        // Sub-phase 11.1/11.2 — whiteboard group: sticky notes + connectors.
        GroupedToolButton(
            state = state,
            groupTools = listOf(Tool.STICKY, Tool.CONNECTOR),
            lastUsed = state.lastBoardTool,
            groupIcon = { state.lastBoardTool.icon() },
            groupDescription = "Board",
            modifier = Modifier.weight(1f),
        )
        ToolIconButton(state, Tool.FRAME, Modifier.weight(1f))
    }
}

@Composable
private fun ToolIconButton(
    state: ToolPaletteState,
    tool: Tool,
    modifier: Modifier = Modifier,
) {
    ToolButtonShell(
        selected = state.selected == tool,
        icon = tool.icon(),
        contentDescription = tool.displayName,
        onClick = { state.select(tool) },
        modifier = modifier,
    )
}

/**
 * Grouped tool button (eraser variants / shape roster). Tap re-selects the
 * group's last-used tool; tap-while-active or long-press opens the variant
 * picker.
 */
@Composable
private fun GroupedToolButton(
    state: ToolPaletteState,
    groupTools: List<Tool>,
    lastUsed: Tool,
    groupIcon: () -> ImageVector,
    groupDescription: String,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val groupActive = state.selected in groupTools
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        ToolButtonShell(
            selected = groupActive,
            icon = groupIcon(),
            contentDescription = groupDescription,
            onClick = {
                if (groupActive) menuExpanded = true
                else state.select(lastUsed)
            },
            onLongClick = { menuExpanded = true },
        )
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            groupTools.forEach { tool ->
                DropdownMenuItem(
                    text = { Text(tool.displayName) },
                    leadingIcon = {
                        Icon(tool.icon(), contentDescription = null)
                    },
                    trailingIcon = {
                        if (state.selected == tool) {
                            Icon(Icons.Filled.Check, contentDescription = "Selected")
                        }
                    },
                    onClick = {
                        state.select(tool)
                        menuExpanded = false
                    },
                )
            }
        }
    }
}

/** Shared visual for tool buttons: 48 dp target, accent-filled circle when active. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ToolButtonShell(
    selected: Boolean,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    // Studio Bench signature moment: the active tool fills with the electric
    // accent ("glowing active-tool state"), so you feel which tool is live
    // rather than reading it. Pulled from the Studio tokens directly because
    // the white drawing canvas is intentionally not wrapped in StudioTheme.
    val studio = if (androidx.compose.foundation.isSystemInDarkTheme()) {
        com.aichat.sandbox.ui.theme.studio.StudioDarkColors
    } else {
        com.aichat.sandbox.ui.theme.studio.StudioLightColors
    }
    Box(
        modifier = modifier
            .height(48.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    if (selected) studio.accentSignature else Color.Transparent
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (selected) studio.onAccent
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private fun Tool.icon(): ImageVector = when (this) {
    // Distinct glyphs per ink tool — Pen and Pencil used to share two nearly
    // identical pencil icons, and Lasso wore the Highlight glyph while the
    // Highlighter wore Brush.
    Tool.PEN -> Icons.Filled.Draw
    Tool.HIGHLIGHTER -> Icons.Filled.BorderColor
    Tool.PENCIL -> Icons.Filled.Edit
    Tool.ERASER_STROKE -> Icons.Outlined.Backspace
    Tool.ERASER_AREA -> Icons.Outlined.Backspace
    Tool.LASSO -> Icons.Filled.Gesture
    Tool.TEXT -> Icons.Filled.TextFields
    Tool.LINE -> Icons.Filled.HorizontalRule
    Tool.RECT -> Icons.Filled.CheckBoxOutlineBlank
    Tool.ELLIPSE -> Icons.Outlined.Circle
    Tool.ARROW -> Icons.AutoMirrored.Filled.TrendingFlat
    Tool.POLYGON -> Icons.Filled.Pentagon
    Tool.FRAME -> Icons.Filled.CropFree
    Tool.STICKY -> Icons.Filled.StickyNote2
    Tool.CONNECTOR -> Icons.Filled.Polyline
    Tool.PATH_PEN -> Icons.Filled.Timeline
}

@Composable
private fun InkConfigRow(
    state: ToolPaletteState,
    onPickCustomColor: () -> Unit,
) {
    val activeInk = state.lastInkTool
    val activeColor = state.colorFor(activeInk)
    val activeWidth = state.widthFor(activeInk)
    // Whether `activeColor` matches one of the default swatches drives the
    // highlight: a custom hue lights up the "+" tile instead, so the user
    // can see "my picked colour" reflected somewhere on the row.
    val isCustomColor = activeColor !in ToolPaletteState.DEFAULT_COLOR_SWATCHES

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolPaletteState.DEFAULT_COLOR_SWATCHES.forEach { swatch ->
                ColorSwatch(
                    colorArgb = swatch,
                    selected = swatch == activeColor,
                    onSelect = { state.setColor(activeInk, swatch) },
                    onLongPress = onPickCustomColor,
                )
            }
            // Phase 5.3 — "+" tile opens the full HSL picker. Long-pressing
            // any existing swatch does the same thing, so two affordances
            // converge on one sheet.
            CustomColorTile(
                showingCustom = isCustomColor,
                customColorArgb = if (isCustomColor) activeColor else null,
                onClick = onPickCustomColor,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Live nib preview — a dot at the active colour whose diameter
            // tracks the slider, so "what will this stroke look like" is
            // answered before touching the canvas.
            WidthPreviewDot(widthPx = activeWidth, colorArgb = activeColor)
            Slider(
                value = activeWidth,
                onValueChange = { state.setWidth(activeInk, it) },
                valueRange = ToolPaletteState.WIDTH_MIN_PX..ToolPaletteState.WIDTH_MAX_PX,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "%.1f".format(activeWidth),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 14.1 — opt-in smoothing pass on pen lift. Only meaningful for
            // ink strokes, so the chip hides when this row serves the shape /
            // connector / path tools.
            if (state.selected.isInk) {
                FilterChip(
                    selected = state.inkBeautify,
                    onClick = { state.setBeautify(!state.inkBeautify) },
                    label = { Text("Beautify") },
                )
            }
        }
    }
}

@Composable
private fun WidthPreviewDot(widthPx: Float, colorArgb: Int) {
    // Map the 0.5–10 px width range onto a 4–24 dp dot: exact pixel size
    // would be invisible at the thin end, so the mapping favours legibility
    // over physical accuracy.
    val fraction = (widthPx - ToolPaletteState.WIDTH_MIN_PX) /
        (ToolPaletteState.WIDTH_MAX_PX - ToolPaletteState.WIDTH_MIN_PX)
    val diameter = (4f + fraction * 20f).dp
    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(diameter)
                .clip(CircleShape)
                .background(Color(colorArgb)),
        )
    }
}

@Composable
private fun EraserConfigRow(state: ToolPaletteState) {
    if (state.selected != Tool.ERASER_AREA) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Stroke eraser — removes whole strokes you touch.",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Area",
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = state.areaEraserRadiusPx,
            onValueChange = { state.setAreaEraserRadius(it) },
            valueRange = ToolPaletteState.ERASER_RADIUS_MIN_PX..ToolPaletteState.ERASER_RADIUS_MAX_PX,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun LassoHintRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Lasso — draw a loop to select strokes.",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun FrameHintRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Frame — drag a rectangle to define an exportable region.",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * Sub-phase 11.1 — sticky colour row: the 8 preset fills. Tapping a swatch
 * sets the fill for the *next* dropped sticky; restyling an existing one
 * happens through its inline editor flow (out of scope for v1).
 */
@Composable
private fun StickyConfigRow(state: ToolPaletteState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "Sticky",
            style = MaterialTheme.typography.labelMedium,
        )
        StickyCodec.PRESET_FILLS.forEach { fill ->
            ColorSwatch(
                colorArgb = fill,
                selected = fill == state.stickyFillColor,
                onSelect = { state.setStickyFill(fill) },
            )
        }
    }
}

@Composable
private fun ConnectorHintRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Connector — drag between items to link them; ends stay attached.",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * Sub-phase 12.5 — cap / join chips for newly drawn paths. Round / round
 * is the default (matches the ink feel); the chips swap the
 * [PathCodec] capJoin byte the pen encodes on commit.
 */
@Composable
private fun PathCapJoinRow(state: ToolPaletteState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Cap",
            style = MaterialTheme.typography.labelMedium,
        )
        CapJoinChip("Round", state.pathStrokeCap == PathCodec.CAP_ROUND) {
            state.setPathCap(PathCodec.CAP_ROUND)
        }
        CapJoinChip("Square", state.pathStrokeCap == PathCodec.CAP_SQUARE) {
            state.setPathCap(PathCodec.CAP_SQUARE)
        }
        CapJoinChip("Flat", state.pathStrokeCap == PathCodec.CAP_BUTT) {
            state.setPathCap(PathCodec.CAP_BUTT)
        }
        Text(
            text = "Join",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp),
        )
        CapJoinChip("Round", state.pathStrokeJoin == PathCodec.JOIN_ROUND) {
            state.setPathJoin(PathCodec.JOIN_ROUND)
        }
        CapJoinChip("Miter", state.pathStrokeJoin == PathCodec.JOIN_MITER) {
            state.setPathJoin(PathCodec.JOIN_MITER)
        }
        CapJoinChip("Bevel", state.pathStrokeJoin == PathCodec.JOIN_BEVEL) {
            state.setPathJoin(PathCodec.JOIN_BEVEL)
        }
    }
}

@Composable
private fun CapJoinChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

@Composable
private fun TextHintRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Text — tap empty space to add a note, tap existing text to edit.",
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * Phase 10.2/10.3 — shape fill + outline style row. The fill chip toggles
 * the fill on/off; the swatch beside it shows the current fill colour and
 * opens the picker targeting the fill slot. Solid / dashed / dotted chips
 * pick the outline style for newly drawn shapes.
 */
@Composable
private fun ShapeStyleRow(
    state: ToolPaletteState,
    onPickShapeFillColor: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = state.shapeFillEnabled,
            onClick = { state.setFillEnabled(!state.shapeFillEnabled) },
            label = { Text("Fill") },
        )
        // Current fill colour; tap opens the picker (and turns the fill on).
        ColorSwatch(
            colorArgb = state.shapeFillColor,
            selected = state.shapeFillEnabled,
            onSelect = onPickShapeFillColor,
        )
        Text(
            text = "Line",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp),
        )
        StrokeStyleChip(state, ShapeCodec.STROKE_STYLE_SOLID.toInt(), "Solid")
        StrokeStyleChip(state, ShapeCodec.STROKE_STYLE_DASHED.toInt(), "Dashed")
        StrokeStyleChip(state, ShapeCodec.STROKE_STYLE_DOTTED.toInt(), "Dotted")
    }
}

@Composable
private fun StrokeStyleChip(state: ToolPaletteState, style: Int, label: String) {
    FilterChip(
        selected = state.shapeStrokeStyle == style,
        onClick = { state.setStrokeStyle(style) },
        label = { Text(label) },
    )
}

/**
 * Phase 6.3 — three-chip snap toggle row. Each chip lights up when its
 * mask bit is set; tap flips that single bit.
 */
@Composable
private fun SnapChipRow(snapMask: Int, onToggle: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Snap",
            style = MaterialTheme.typography.labelMedium,
        )
        SnapToggleChip("15°", snapMask and Snap.MASK_ANGLE != 0) { onToggle(Snap.MASK_ANGLE) }
        SnapToggleChip("Grid", snapMask and Snap.MASK_GRID != 0) { onToggle(Snap.MASK_GRID) }
        SnapToggleChip("Ends", snapMask and Snap.MASK_ENDPOINT != 0) { onToggle(Snap.MASK_ENDPOINT) }
    }
}

@Composable
private fun SnapToggleChip(label: String, on: Boolean, onClick: () -> Unit) {
    val studio = if (androidx.compose.foundation.isSystemInDarkTheme()) {
        com.aichat.sandbox.ui.theme.studio.StudioDarkColors
    } else {
        com.aichat.sandbox.ui.theme.studio.StudioLightColors
    }
    FilterChip(
        selected = on,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = studio.accentSignature,
            selectedLabelColor = studio.onAccent,
        ),
    )
}

@Composable
private fun ColorSwatch(
    colorArgb: Int,
    selected: Boolean,
    onSelect: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    val color = Color(colorArgb)
    val ring = MaterialTheme.colorScheme.primary
    val idleOutline = MaterialTheme.colorScheme.outline
    // Gesture target is the full 44 dp box; the visible swatch stays a
    // compact 28 dp circle. The bare 28 dp circles were well under the
    // touch-target minimum and easy to fat-finger.
    Box(
        modifier = Modifier
            .size(44.dp)
            .pointerInput(colorArgb) {
                detectTapGestures(
                    onTap = { onSelect() },
                    onLongPress = { onLongPress() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) ring else idleOutline,
                    shape = CircleShape,
                ),
        )
    }
}

@Composable
private fun CustomColorTile(
    showingCustom: Boolean,
    customColorArgb: Int?,
    onClick: () -> Unit,
) {
    val ring = MaterialTheme.colorScheme.primary
    val idleOutline = MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    if (showingCustom && customColorArgb != null) Color(customColorArgb)
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .border(
                    width = if (showingCustom) 2.dp else 1.dp,
                    color = if (showingCustom) ring else idleOutline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "Custom colour",
                tint = if (showingCustom) Color.White
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
