package com.aichat.sandbox.ui.screens.notes

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.repository.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

const val NOTE_ID_NEW = "new"
private const val DEFAULT_TITLE = "Untitled"
private const val DEFAULT_BACKGROUND_STYLE = "plain"
private const val CURRENT_SCHEMA_VERSION = 1

@HiltViewModel
class NoteEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: NoteRepository,
) : ViewModel() {

    private val routeArg: String = savedStateHandle["noteId"] ?: NOTE_ID_NEW

    // Stable id for the "new" path: generated once, reused across saves so rapid
    // back-presses don't create duplicate notes.
    private val resolvedNoteId: String =
        if (routeArg == NOTE_ID_NEW) UUID.randomUUID().toString() else routeArg

    private val _note = MutableStateFlow(emptyNote(resolvedNoteId))
    val note: StateFlow<Note> = _note.asStateFlow()

    val items: SnapshotStateList<NoteItem> = mutableStateListOf()

    private var nextZIndex: Int = 0

    init {
        if (routeArg != NOTE_ID_NEW) {
            viewModelScope.launch {
                repository.getNote(routeArg)?.let { loaded -> _note.value = loaded }
                val loaded = repository.getItems(routeArg)
                items.clear()
                items.addAll(loaded)
                nextZIndex = (loaded.maxOfOrNull { it.zIndex } ?: -1) + 1
            }
        }
    }

    fun setTitle(title: String) {
        _note.update { it.copy(title = title) }
    }

    /**
     * Append a freshly drawn item to the in-memory list. The DrawingSurface emits items
     * without a note id or zIndex; this method assigns both before storage.
     */
    fun addItem(item: NoteItem) {
        items.add(
            item.copy(
                noteId = resolvedNoteId,
                zIndex = nextZIndex++,
            )
        )
    }

    /**
     * Persist the current in-memory note. Returns the resolved note id so callers on
     * the `note/new` route can navigate to the canonical `note/<id>` route if needed.
     */
    suspend fun save(): String {
        val current = _note.value
        val sanitizedTitle = current.title.ifBlank { DEFAULT_TITLE }
        val toPersist = current.copy(
            title = sanitizedTitle,
            updatedAt = System.currentTimeMillis(),
        )
        repository.saveNote(toPersist, items = items.toList())
        _note.value = toPersist
        return toPersist.id
    }

    private fun emptyNote(id: String) = Note(
        id = id,
        title = "",
        backgroundStyle = DEFAULT_BACKGROUND_STYLE,
        schemaVersion = CURRENT_SCHEMA_VERSION,
        minX = 0f,
        minY = 0f,
        maxX = 0f,
        maxY = 0f,
        thumbnailPath = null,
        ocrText = null,
    )
}
