package com.aichat.sandbox.ui.screens.notebooks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.model.NoteFrame
import com.aichat.sandbox.data.repository.NoteRepository
import com.aichat.sandbox.data.repository.NotebookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class KidsNotebookViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val noteRepository: NoteRepository,
    private val notebookRepository: NotebookRepository,
) : ViewModel() {

    private val noteId: String = checkNotNull(savedStateHandle["noteId"])

    private val _state = MutableStateFlow(KidsNotebookUiState())
    val state: StateFlow<KidsNotebookUiState> = _state.asStateFlow()

    private val _pendingOpenEditor = MutableStateFlow<PendingOpen?>(null)
    val pendingOpenEditor: StateFlow<PendingOpen?> = _pendingOpenEditor.asStateFlow()

    init {
        refresh()
    }

    fun consumePendingOpen() {
        _pendingOpenEditor.value = null
    }

    fun addSheet() {
        viewModelScope.launch {
            val newFrame = notebookRepository.addNotebookPage(noteId) ?: return@launch
            refresh()
            _pendingOpenEditor.value = PendingOpen(noteId, newFrame.id)
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            val note = noteRepository.getNote(noteId)
            val notebook = note?.notebookId?.let { notebookRepository.getNotebook(it) }
            val frames = noteRepository.getFrames(noteId)
            _state.value = KidsNotebookUiState(
                noteId = note?.id,
                notebookTitle = notebook?.title ?: note?.title ?: "Notebook",
                frames = frames,
            )
        }
    }
}

data class KidsNotebookUiState(
    val noteId: String? = null,
    val notebookTitle: String = "Notebook",
    val frames: List<NoteFrame> = emptyList(),
)

data class PendingOpen(val noteId: String, val frameId: String)
