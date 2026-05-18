package com.aichat.sandbox.data.notes

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sub-phase 8.4 — favorites bar storage.
 *
 * Six slots, each holding an optional brush-preset id. Persisted via
 * `Preferences DataStore` as a single JSON blob so we only pay one
 * read-modify-write per assignment.
 *
 * Per-note favorites are explicitly out of scope (phase doc) — every slot
 * is global to the app, matching Concepts' personalizable tool wheel.
 */
data class FavoriteSlot(
    val index: Int,
    val brushPresetId: String?,
)

private val Context.favoritesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "favorites_bar",
)

@Singleton
class FavoritesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val gson = Gson()
    private val slotsKey = stringPreferencesKey("slots_json")

    /** Live slot list. Always emits exactly [SLOT_COUNT] entries. */
    fun observe(): Flow<List<FavoriteSlot>> =
        context.favoritesDataStore.data.map { prefs ->
            decode(prefs[slotsKey])
        }

    suspend fun assignSlot(index: Int, brushPresetId: String?) {
        if (index !in 0 until SLOT_COUNT) return
        context.favoritesDataStore.edit { prefs ->
            val current = decode(prefs[slotsKey])
            val updated = current.map {
                if (it.index == index) it.copy(brushPresetId = brushPresetId) else it
            }
            prefs[slotsKey] = encode(updated)
        }
    }

    private fun decode(json: String?): List<FavoriteSlot> {
        if (json.isNullOrBlank()) return DEFAULT_SLOTS
        return try {
            val type = object : TypeToken<List<FavoriteSlot>>() {}.type
            val parsed: List<FavoriteSlot> = gson.fromJson(json, type) ?: DEFAULT_SLOTS
            // Defensive fill: always return exactly SLOT_COUNT items so the
            // bar's row indexing stays stable across schema drift.
            val byIndex = parsed.associateBy { it.index }
            (0 until SLOT_COUNT).map { i ->
                byIndex[i] ?: FavoriteSlot(i, null)
            }
        } catch (_: Throwable) {
            DEFAULT_SLOTS
        }
    }

    private fun encode(slots: List<FavoriteSlot>): String = gson.toJson(slots)

    companion object {
        const val SLOT_COUNT: Int = 6

        /** Six empty slots — the user fills them via long-press → assign. */
        val DEFAULT_SLOTS: List<FavoriteSlot> = (0 until SLOT_COUNT).map {
            FavoriteSlot(index = it, brushPresetId = null)
        }
    }
}
