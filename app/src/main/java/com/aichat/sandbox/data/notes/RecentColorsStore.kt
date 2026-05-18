package com.aichat.sandbox.data.notes

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide FIFO of the user's most recently picked custom colours
 * (sub-phase 5.3). Persisted via a small Preferences DataStore so the
 * twelve-cell recents row survives process death.
 *
 * Scope: shared across notes (not per-note). The decision keeps the row
 * visually stable: a user who lifts a colour off a Halloween doodle still
 * sees it on tomorrow's grocery list note. Per-note recents felt cluttered
 * in early sketches.
 *
 * Storage layout: a single comma-separated string of ARGB ints, most-recent
 * first. We avoid a JSON shape so future format changes can stay
 * backwards-compatible by ignoring unparseable tokens rather than tripping a
 * blanket "schema mismatch."
 */
@Singleton
class RecentColorsStore @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val dataStore: DataStore<Preferences> = context.notesColorsDataStore

    /** Live, deduplicated, capped at [MAX_ENTRIES], most-recent first. */
    val recentColors: Flow<List<Int>> = dataStore.data.map { prefs ->
        decode(prefs[KEY])
    }

    /**
     * Push [colorArgb] to the front of the row. Duplicates are deduplicated
     * (the existing entry's old position is dropped), and the list is
     * trimmed to [MAX_ENTRIES]. Safe to call from any coroutine context.
     */
    suspend fun record(colorArgb: Int) {
        dataStore.edit { prefs ->
            val existing = decode(prefs[KEY])
            val updated = buildList(existing.size + 1) {
                add(colorArgb)
                for (c in existing) {
                    if (c == colorArgb) continue
                    add(c)
                }
            }.take(MAX_ENTRIES)
            prefs[KEY] = encode(updated)
        }
    }

    /**
     * Clear every entry. Wired up for "Reset recents" if a future settings
     * screen wants it; not exposed in the v1 UI.
     */
    suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    private fun decode(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',')
            .mapNotNull { token ->
                token.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
            }
            .take(MAX_ENTRIES)
    }

    private fun encode(values: List<Int>): String =
        values.joinToString(",") { it.toString() }

    companion object {
        const val MAX_ENTRIES: Int = 12
        private val KEY = stringPreferencesKey("recent_colors_argb")
    }
}

/** Kept private to the notes module so the picker doesn't share storage with
 *  the chat settings DataStore. */
private val Context.notesColorsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "notes_recent_colors"
)
