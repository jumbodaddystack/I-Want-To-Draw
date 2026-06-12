package com.aichat.sandbox.data.notes

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot of the persisted tool-palette choices. Null fields mean "never
 * saved" — the palette keeps its built-in default for that slot.
 */
data class PalettePrefs(
    val selectedToolId: String? = null,
    val inkColors: Map<String, Int> = emptyMap(),
    val inkWidths: Map<String, Float> = emptyMap(),
    val areaEraserRadiusPx: Float? = null,
    val fingerDrawing: Boolean = false,
    // Phase 10.2 — shape fill + stroke style. Null = never saved.
    val shapeFillEnabled: Boolean? = null,
    val shapeFillColor: Int? = null,
    val shapeStrokeStyle: Int? = null,
    // Sub-phase 11.1 — sticky fill. Null = never saved.
    val stickyFillColor: Int? = null,
    // Sub-phase 14.1 — ink beautify toggle. Null = never saved.
    val inkBeautify: Boolean? = null,
)

/**
 * App-wide persistence for the editor's tool palette (selected tool, per-ink
 * tool colour and width, area-eraser radius) plus the "draw with finger"
 * switch. Sub-phase 1.6 shipped the palette deliberately volatile; this store
 * lifts that limitation so the editor reopens with the user's last setup
 * instead of resetting to Pen / black / 4 px every session.
 *
 * Scope: global (mirrors [FavoritesStore] / [RecentColorsStore]) — the palette
 * follows the user across notes, not per-note.
 */
@Singleton
class ToolPalettePrefsStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val dataStore: DataStore<Preferences> = context.toolPaletteDataStore

    val prefs: Flow<PalettePrefs> = dataStore.data.map { p ->
        PalettePrefs(
            selectedToolId = p[KEY_SELECTED_TOOL],
            inkColors = INK_TOOL_IDS.mapNotNull { id ->
                p[colorKey(id)]?.let { id to it }
            }.toMap(),
            inkWidths = INK_TOOL_IDS.mapNotNull { id ->
                p[widthKey(id)]?.let { id to it }
            }.toMap(),
            areaEraserRadiusPx = p[KEY_ERASER_RADIUS],
            fingerDrawing = p[KEY_FINGER_DRAWING] ?: false,
            shapeFillEnabled = p[KEY_SHAPE_FILL_ENABLED],
            shapeFillColor = p[KEY_SHAPE_FILL_COLOR],
            shapeStrokeStyle = p[KEY_SHAPE_STROKE_STYLE],
            stickyFillColor = p[KEY_STICKY_FILL_COLOR],
            inkBeautify = p[KEY_INK_BEAUTIFY],
        )
    }

    /** Persist the palette snapshot (everything except [PalettePrefs.fingerDrawing]). */
    suspend fun savePalette(
        selectedToolId: String,
        inkColors: Map<String, Int>,
        inkWidths: Map<String, Float>,
        areaEraserRadiusPx: Float,
        shapeFillEnabled: Boolean,
        shapeFillColor: Int,
        shapeStrokeStyle: Int,
        stickyFillColor: Int,
        inkBeautify: Boolean,
    ) {
        dataStore.edit { p ->
            p[KEY_SELECTED_TOOL] = selectedToolId
            for ((id, color) in inkColors) {
                if (id in INK_TOOL_IDS) p[colorKey(id)] = color
            }
            for ((id, width) in inkWidths) {
                if (id in INK_TOOL_IDS) p[widthKey(id)] = width
            }
            p[KEY_ERASER_RADIUS] = areaEraserRadiusPx
            p[KEY_SHAPE_FILL_ENABLED] = shapeFillEnabled
            p[KEY_SHAPE_FILL_COLOR] = shapeFillColor
            p[KEY_SHAPE_STROKE_STYLE] = shapeStrokeStyle
            p[KEY_STICKY_FILL_COLOR] = stickyFillColor
            p[KEY_INK_BEAUTIFY] = inkBeautify
        }
    }

    suspend fun setFingerDrawing(enabled: Boolean) {
        dataStore.edit { it[KEY_FINGER_DRAWING] = enabled }
    }

    companion object {
        /** Ink tool ids with a persisted colour/width slot — matches `Tool.isInk`. */
        val INK_TOOL_IDS: Set<String> = setOf("pen", "highlighter", "pencil")

        private val KEY_SELECTED_TOOL = stringPreferencesKey("selected_tool")
        private val KEY_ERASER_RADIUS = floatPreferencesKey("area_eraser_radius_px")
        private val KEY_FINGER_DRAWING = booleanPreferencesKey("finger_drawing")
        private val KEY_SHAPE_FILL_ENABLED = booleanPreferencesKey("shape_fill_enabled")
        private val KEY_SHAPE_FILL_COLOR = intPreferencesKey("shape_fill_color")
        private val KEY_SHAPE_STROKE_STYLE = intPreferencesKey("shape_stroke_style")
        private val KEY_STICKY_FILL_COLOR = intPreferencesKey("sticky_fill_color")
        private val KEY_INK_BEAUTIFY = booleanPreferencesKey("ink_beautify")

        private fun colorKey(toolId: String) = intPreferencesKey("ink_color_$toolId")
        private fun widthKey(toolId: String) = floatPreferencesKey("ink_width_$toolId")
    }
}

/** Private file so palette prefs don't share storage with chat settings. */
private val Context.toolPaletteDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "notes_tool_palette"
)
