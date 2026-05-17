package com.aichat.sandbox.ui.screens.notes

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

@HiltViewModel
class NotesListViewModel @Inject constructor(
    private val repository: NoteRepository,
) : ViewModel() {

    val notes: StateFlow<List<Note>> = repository.observeNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        // One-shot pass for notes saved before thumbnails existed (or whose
        // cached PNG was wiped out from under us). Cheap when nothing's
        // missing; the DAO query short-circuits to an empty list.
        viewModelScope.launch { repository.renderMissingThumbnails() }
    }

    fun delete(note: Note) {
        viewModelScope.launch { repository.deleteNote(note.id) }
    }
}
