package com.aichat.sandbox.ui.screens.icons

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.repository.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backing VM for the dedicated Icons destination. Mirrors `NotesListViewModel`
 * but consumes the icon-filtered flow so icons get a first-class home instead
 * of mixing into the Notes list.
 */
@HiltViewModel
class IconsListViewModel @Inject constructor(
    private val repository: NoteRepository,
) : ViewModel() {

    val icons: StateFlow<List<Note>> = repository.observeIcons()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // Backfill thumbnails for icons saved before thumbnails existed.
        viewModelScope.launch { repository.renderMissingThumbnails() }
    }

    fun delete(note: Note) {
        viewModelScope.launch { repository.deleteNote(note.id) }
    }
}
