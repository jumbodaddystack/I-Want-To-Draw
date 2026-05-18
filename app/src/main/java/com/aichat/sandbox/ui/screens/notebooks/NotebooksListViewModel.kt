package com.aichat.sandbox.ui.screens.notebooks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.model.Notebook
import com.aichat.sandbox.data.model.NotebookPageSize
import com.aichat.sandbox.data.repository.NotebookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotebooksListViewModel @Inject constructor(
    private val repository: NotebookRepository,
) : ViewModel() {

    private val refreshSignal = MutableStateFlow(0L)

    /**
     * Notebooks paired with their cached page count. The page count is
     * refreshed lazily — observing it as a `Flow` over an arbitrary
     * cross-table query would balloon the surface area; for the cover
     * caption a stale-by-one-edit count is fine.
     */
    val notebooks: StateFlow<List<NotebookCard>> = combine(
        repository.observeNotebooks(),
        refreshSignal,
    ) { books, _ ->
        books.map { book ->
            NotebookCard(
                notebook = book,
                pageCount = repository.pageCount(book.id),
                noteId = repository.underlyingNoteId(book.id),
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = emptyList(),
    )

    private val _pendingNavigation = MutableStateFlow<String?>(null)
    /** Note id to open after [createNotebook] returns. The screen consumes + clears. */
    val pendingNavigation: StateFlow<String?> = _pendingNavigation.asStateFlow()

    fun consumeNavigation() { _pendingNavigation.value = null }

    fun createNotebook(
        title: String,
        pageSize: NotebookPageSize,
        pageStyle: String,
        coverColorArgb: Int,
    ) {
        viewModelScope.launch {
            val created = repository.createNotebook(
                title = title,
                pageSize = pageSize,
                pageStyle = pageStyle,
                coverColorArgb = coverColorArgb,
            )
            _pendingNavigation.value = created.noteId
            refreshSignal.value = System.currentTimeMillis()
        }
    }

    fun rename(notebook: Notebook, newTitle: String) {
        viewModelScope.launch {
            repository.rename(notebook.id, newTitle)
            refreshSignal.value = System.currentTimeMillis()
        }
    }

    fun delete(notebook: Notebook) {
        viewModelScope.launch {
            repository.delete(notebook.id)
            refreshSignal.value = System.currentTimeMillis()
        }
    }

    data class NotebookCard(
        val notebook: Notebook,
        val pageCount: Int,
        val noteId: String?,
    )
}
