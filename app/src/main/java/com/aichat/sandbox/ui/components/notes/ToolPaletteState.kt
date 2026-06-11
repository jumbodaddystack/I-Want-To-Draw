package com.aichat.sandbox.ui.components.notes

import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap

/**
 * Tool roster for the notes editor (sub-phase 1.6).
 *
 * [TEXT] became selectable in sub-phase 1.9 — tapping the canvas with it
 * active drops a text item and opens an inline Compose editor.
 */
enum class Tool(
    val id: String,
    val displayName: String,
    val enabledInPalette: Boolean = true,
) {
    PEN("pen", "Pen"),
    HIGHLIGHTER("highlighter", "Highlighter"),
    PENCIL("pencil", "Pencil"),
    ERASER_STROKE("eraser_stroke", "Eraser"),
    ERASER_AREA("eraser_area", "Area eraser"),
    LASSO("lasso", "Lasso"),
    TEXT("text", "Text"),

    // Phase 6.2 — shape tools. Each emits a `NoteItem(kind="shape")` on
    // commit; the rubber-band preview lives on [DrawingSurface].
    LINE("shape_line", "Line"),
    RECT("shape_rect", "Rectangle"),
    ELLIPSE("shape_ellipse", "Ellipse"),
    ARROW("shape_arrow", "Arrow"),
    POLYGON("shape_polygon", "Polygon"),

    // Sub-phase 8.1 — frame tool. Drag a rectangle in world space to create
    // a named region. Frames live alongside items and define exportable
    // sub-rects of the infinite canvas (also: the substrate Phase 9 uses
    // for notebook pages).
    FRAME("frame", "Frame"),

    // Sub-phase 11.1 — sticky notes. Tap-only (same interaction model as
    // TEXT): tap empty canvas drops a sticky and opens the inline editor;
    // tap an existing sticky edits it.
    STICKY("sticky", "Sticky note"),

    // Sub-phase 11.2 — bound connectors. Drag from one item to another;
    // endpoints bind to the nearest anchor and re-resolve at render time.
    CONNECTOR("connector", "Connector"),
    ;

    val isInk: Boolean get() = this == PEN || this == HIGHLIGHTER || this == PENCIL
    val isEraser: Boolean get() = this == ERASER_STROKE || this == ERASER_AREA
    val isLasso: Boolean get() = this == LASSO
    val isText: Boolean get() = this == TEXT
    val isShape: Boolean
        get() = this == LINE || this == RECT || this == ELLIPSE ||
            this == ARROW || this == POLYGON
    val isFrame: Boolean get() = this == FRAME
    val isSticky: Boolean get() = this == STICKY
    val isConnector: Boolean get() = this == CONNECTOR

    companion object {
        /** Resolve a persisted [id] back to its enum, or null for unknown ids. */
        fun fromId(id: String?): Tool? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Per-editor tool state. Selection, plus per-ink-tool color and width.
 * Eraser radius is also held here so the area eraser can be tuned without
 * touching the surface.
 *
 * Lifetime: this lives in the editor's ViewModel. Choices survive across
 * sessions via `ToolPalettePrefsStore` — the ViewModel calls [restore] with
 * the persisted snapshot on open and writes changes back on a debounce.
 */
class ToolPaletteState {

    var selected: Tool by mutableStateOf(Tool.PEN)
        private set

    private val colors: SnapshotStateMap<Tool, Int> = mutableStateMapOf<Tool, Int>().apply {
        put(Tool.PEN, Color.BLACK)
        put(Tool.HIGHLIGHTER, DEFAULT_HIGHLIGHTER_COLOR)
        put(Tool.PENCIL, Color.DKGRAY)
    }

    private val widths: SnapshotStateMap<Tool, Float> = mutableStateMapOf<Tool, Float>().apply {
        put(Tool.PEN, 4f)
        put(Tool.HIGHLIGHTER, 18f)
        put(Tool.PENCIL, 3f)
    }

    /** Screen-space radius of the area eraser. Stroke eraser uses [STROKE_ERASER_RADIUS_PX]. */
    var areaEraserRadiusPx: Float by mutableStateOf(24f)
        private set

    /** Most-recent ink tool — used to render swatches when an eraser is active. */
    var lastInkTool: Tool by mutableStateOf(Tool.PEN)
        private set

    /** Most-recent eraser — the grouped eraser button re-selects this on tap. */
    var lastEraserTool: Tool by mutableStateOf(Tool.ERASER_STROKE)
        private set

    /** Most-recent shape — the grouped shapes button re-selects this on tap. */
    var lastShapeTool: Tool by mutableStateOf(Tool.LINE)
        private set

    /** Most-recent board tool (sticky / connector) — grouped button re-selects it. */
    var lastBoardTool: Tool by mutableStateOf(Tool.STICKY)
        private set

    // ── Phase 10.2 — shape fill + stroke style ───────────────────────────

    /** Whether newly drawn shapes carry a fill. */
    var shapeFillEnabled: Boolean by mutableStateOf(false)
        private set

    /** ARGB fill colour applied when [shapeFillEnabled] (alpha included). */
    var shapeFillColor: Int by mutableStateOf(DEFAULT_SHAPE_FILL_COLOR)
        private set

    /** Outline style for newly drawn shapes — a [ShapeCodec] STROKE_STYLE_* value. */
    var shapeStrokeStyle: Int by mutableStateOf(ShapeCodec.STROKE_STYLE_SOLID.toInt())
        private set

    fun setFillEnabled(enabled: Boolean) {
        shapeFillEnabled = enabled
    }

    fun setFillColor(color: Int) {
        shapeFillColor = color
    }

    fun setStrokeStyle(style: Int) {
        if (style !in VALID_STROKE_STYLES) return
        shapeStrokeStyle = style
    }

    /** Fill ARGB the surface should encode on the next shape — 0 when disabled. */
    fun activeShapeFillArgb(): Int = if (shapeFillEnabled) shapeFillColor else 0

    // ── Sub-phase 11.1 — sticky fill ─────────────────────────────────────

    /** Fill applied to newly dropped stickies — one of [StickyCodec.PRESET_FILLS]. */
    var stickyFillColor: Int by mutableStateOf(StickyCodec.PRESET_FILLS.first())
        private set

    fun setStickyFill(color: Int) {
        stickyFillColor = color
    }

    fun select(tool: Tool) {
        if (!tool.enabledInPalette) return
        if (selected == tool) return
        selected = tool
        if (tool.isInk) lastInkTool = tool
        if (tool.isEraser) lastEraserTool = tool
        if (tool.isShape) lastShapeTool = tool
        if (tool.isSticky || tool.isConnector) lastBoardTool = tool
    }

    fun colorFor(tool: Tool): Int = colors[tool] ?: Color.BLACK
    fun widthFor(tool: Tool): Float = widths[tool] ?: 4f

    fun setColor(tool: Tool, color: Int) {
        if (!tool.isInk) return
        colors[tool] = color
    }

    fun setWidth(tool: Tool, width: Float) {
        if (!tool.isInk) return
        widths[tool] = width.coerceIn(WIDTH_MIN_PX, WIDTH_MAX_PX)
    }

    fun setAreaEraserRadius(radiusPx: Float) {
        areaEraserRadiusPx = radiusPx.coerceIn(ERASER_RADIUS_MIN_PX, ERASER_RADIUS_MAX_PX)
    }

    /** Color shown on the swatch row — follows whichever ink tool was last active. */
    fun activeInkColor(): Int = colors[lastInkTool] ?: Color.BLACK
    fun activeInkWidth(): Float = widths[lastInkTool] ?: 4f

    /**
     * Apply a persisted snapshot. Unknown tool ids and missing fields are
     * skipped so stale prefs (e.g. a removed tool) degrade to the defaults
     * instead of failing. Widths/radius re-run their normal clamps.
     */
    fun restore(
        selectedToolId: String?,
        inkColors: Map<String, Int>,
        inkWidths: Map<String, Float>,
        areaEraserRadiusPx: Float?,
        shapeFillEnabled: Boolean? = null,
        shapeFillColor: Int? = null,
        shapeStrokeStyle: Int? = null,
        stickyFillColor: Int? = null,
    ) {
        for ((id, color) in inkColors) {
            Tool.fromId(id)?.let { setColor(it, color) }
        }
        for ((id, width) in inkWidths) {
            Tool.fromId(id)?.let { setWidth(it, width) }
        }
        areaEraserRadiusPx?.let { setAreaEraserRadius(it) }
        shapeFillEnabled?.let { setFillEnabled(it) }
        shapeFillColor?.let { setFillColor(it) }
        shapeStrokeStyle?.let { setStrokeStyle(it) }
        stickyFillColor?.let { setStickyFill(it) }
        Tool.fromId(selectedToolId)?.let { select(it) }
    }

    companion object {
        const val WIDTH_MIN_PX = 0.5f
        const val WIDTH_MAX_PX = 10f
        const val ERASER_RADIUS_MIN_PX = 8f
        const val ERASER_RADIUS_MAX_PX = 96f

        /** Stroke eraser tip is small — matches a fine-pen-tip's catch area. */
        const val STROKE_ERASER_RADIUS_PX = 6f

        /** ~30% alpha base highlighter yellow, mirroring the highlighter paint alpha. */
        private val DEFAULT_HIGHLIGHTER_COLOR = Color.rgb(255, 222, 0)

        /** Default shape fill — translucent swatch blue so the outline stays legible. */
        val DEFAULT_SHAPE_FILL_COLOR: Int = Color.argb(64, 36, 99, 235)

        private val VALID_STROKE_STYLES: IntRange =
            ShapeCodec.STROKE_STYLE_SOLID.toInt()..ShapeCodec.STROKE_STYLE_DOTTED.toInt()

        val DEFAULT_COLOR_SWATCHES: List<Int> = listOf(
            Color.BLACK,
            Color.rgb(45, 45, 45),
            Color.rgb(214, 40, 40),
            Color.rgb(36, 99, 235),
            Color.rgb(16, 159, 92),
            Color.rgb(255, 159, 28),
        )
    }
}
