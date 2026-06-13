package com.aichat.sandbox.ui.screens.icons

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.local.TagCount
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteFrame
import com.aichat.sandbox.data.notes.IconTags
import com.aichat.sandbox.data.notes.NoteRasterizer
import com.aichat.sandbox.data.repository.NoteRepository
import com.aichat.sandbox.data.repository.NoteSearchRepository
import com.aichat.sandbox.data.vector.AndroidVectorDrawableParser
import com.aichat.sandbox.data.vector.VectorImportDetector
import com.aichat.sandbox.data.vector.VectorImportFormat
import com.aichat.sandbox.data.vector.VectorSvgParser
import com.aichat.sandbox.data.vector.VectorTuneupFileReader
import com.aichat.sandbox.data.vector.notesbridge.DocumentToNoteItems
import com.aichat.sandbox.ui.components.notes.BackgroundLayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Backing VM for the dedicated Icons destination. Mirrors `NotesListViewModel`
 * but consumes the icon-filtered flow so icons get a first-class home instead
 * of mixing into the Notes list.
 *
 * Phase 16.2 adds vector import: a picked VectorDrawable `.xml` / `.svg`
 * parses through the tune-up lane's parsers, converts to editable path items
 * via [DocumentToNoteItems], and lands as a new icon note (graph background +
 * artboard frame, matching the editor's icon seed) that opens immediately.
 *
 * Phase 17.1 adds IconJar-style organization: tag chips that filter the
 * gallery (combining with search via a client-side id intersection), a tag
 * editor behind the tile long-press sheet, and "duplicate as variant" which
 * deep-copies an icon (shared tags, " — outlined" title) and opens the copy.
 */
@HiltViewModel
class IconsListViewModel @Inject constructor(
    private val repository: NoteRepository,
    private val searchRepository: NoteSearchRepository,
    private val fileReader: VectorTuneupFileReader,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    // Phase 16.3 — gallery search. Blank query = the live icon flow; a
    // non-blank query runs the FTS title/OCR search filtered to icons,
    // debounced so per-keystroke queries don't pile up.
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(value: String) {
        _query.value = value
    }

    // Phase 17.1 — tag-chip filter. Null = no filter. Combines with search:
    // FTS results are intersected client-side with the tagged ids (result
    // sets are capped at 100, so the in-memory filter is cheap).
    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    /** Tap toggles: selecting the active chip clears the filter. */
    fun toggleTag(tag: String) {
        _selectedTag.value = if (_selectedTag.value == tag) null else tag
    }

    /** Every icon tag + count, feeding the gallery chip row. */
    val tagCounts: StateFlow<List<TagCount>> = repository.observeIconTagCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val icons: StateFlow<List<Note>> = combine(
        _query.debounce { if (it.isBlank()) 0L else QUERY_DEBOUNCE_MS },
        _selectedTag,
    ) { q, tag -> q to tag }
        .flatMapLatest { (q, tag) ->
            when {
                q.isBlank() && tag == null -> repository.observeIcons()
                q.isBlank() && tag != null -> repository.observeIconsWithTag(tag)
                else -> flow {
                    val results = searchRepository.searchIcons(q)
                    emit(
                        if (tag == null) results
                        else {
                            val tagged = repository.getNoteIdsWithTag(tag).toHashSet()
                            results.filter { it.id in tagged }
                        },
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Outcome of an [importIcon] call, collected by the screen. */
    sealed interface ImportEvent {
        data class Opened(val noteId: String, val warningCount: Int) : ImportEvent
        data class Failed(val message: String) : ImportEvent
    }

    private val _importEvents = MutableSharedFlow<ImportEvent>()
    val importEvents: SharedFlow<ImportEvent> = _importEvents.asSharedFlow()

    init {
        // Backfill thumbnails for icons saved before thumbnails existed.
        viewModelScope.launch { repository.renderMissingThumbnails() }
        // If the active tag filter loses its last bearer (delete / re-tag),
        // drop the filter instead of pinning the gallery to an empty grid
        // behind a chip that no longer renders.
        viewModelScope.launch {
            tagCounts.collect { counts ->
                val selected = _selectedTag.value
                if (selected != null && counts.isNotEmpty() && counts.none { it.tag == selected }) {
                    _selectedTag.value = null
                }
            }
        }
    }

    fun delete(note: Note) {
        viewModelScope.launch { repository.deleteNote(note.id) }
    }

    // ── Phase 17.1 — tag editing + duplicate-as-variant ─────────────────

    /** Backing state for the "Edit tags" dialog. */
    data class TagEditState(
        val noteId: String,
        val title: String,
        val initialTags: List<String>,
    )

    private val _tagEdit = MutableStateFlow<TagEditState?>(null)
    val tagEdit: StateFlow<TagEditState?> = _tagEdit.asStateFlow()

    fun beginEditTags(note: Note) {
        viewModelScope.launch {
            _tagEdit.value = TagEditState(note.id, note.title, repository.getTags(note.id))
        }
    }

    fun dismissEditTags() {
        _tagEdit.value = null
    }

    /** Parse the dialog's free-form input and replace the note's tag set. */
    fun saveTags(input: String) {
        val state = _tagEdit.value ?: return
        _tagEdit.value = null
        viewModelScope.launch { repository.setTags(state.noteId, IconTags.parse(input)) }
    }

    private val _variantCreated = MutableSharedFlow<String>()

    /** New note ids from [duplicateAsVariant]; the screen opens them in the editor. */
    val variantCreated: SharedFlow<String> = _variantCreated.asSharedFlow()

    /**
     * Deep-copy [note] as a sibling variant (filled ↔ outlined workflow):
     * same content and tags, " — outlined" title suffix, then straight into
     * the editor so the user can restyle the copy.
     */
    fun duplicateAsVariant(note: Note) {
        viewModelScope.launch {
            val newId = repository.duplicateNote(note.id, IconTags.variantTitle(note.title))
                ?: return@launch
            repository.renderThumbnailAsync(newId)
            _variantCreated.emit(newId)
        }
    }

    fun importIcon(uri: Uri) {
        viewModelScope.launch {
            val text = fileReader.readText(uri).getOrElse { e ->
                val message = when ((e as? VectorTuneupFileReader.FileReadException)?.error) {
                    VectorTuneupFileReader.ReadError.TOO_LARGE -> "File is too large to import"
                    else -> "Couldn't read the file"
                }
                _importEvents.emit(ImportEvent.Failed(message))
                return@launch
            }
            val document = when (VectorImportDetector.detect(text)) {
                VectorImportFormat.ANDROID_VECTOR -> AndroidVectorDrawableParser.parse(text)
                VectorImportFormat.SVG -> VectorSvgParser.parse(text)
                else -> {
                    _importEvents.emit(
                        ImportEvent.Failed("Not a VectorDrawable .xml or .svg file"),
                    )
                    return@launch
                }
            }
            val noteId = UUID.randomUUID().toString()
            val converted = DocumentToNoteItems.convert(document, noteId)
            if (converted.items.isEmpty()) {
                _importEvents.emit(ImportEvent.Failed("No drawable paths found in the file"))
                return@launch
            }
            val bounds = NoteRasterizer.computeBounds(converted.items)
            val note = Note(
                id = noteId,
                title = displayNameFor(uri) ?: "Imported icon",
                backgroundStyle = BackgroundLayer.STYLE_GRAPH,
                schemaVersion = 1,
                minX = bounds?.get(0) ?: 0f,
                minY = bounds?.get(1) ?: 0f,
                maxX = bounds?.get(2) ?: DocumentToNoteItems.DEFAULT_ARTBOARD_WORLD,
                maxY = bounds?.get(3) ?: DocumentToNoteItems.DEFAULT_ARTBOARD_WORLD,
                thumbnailPath = null,
                ocrText = null,
                isIcon = true,
            )
            repository.saveNoteWithLayers(note, converted.items, emptyList())
            repository.saveFrames(
                noteId,
                listOf(
                    NoteFrame(
                        noteId = noteId,
                        name = "Artboard",
                        minX = 0f,
                        minY = 0f,
                        maxX = DocumentToNoteItems.DEFAULT_ARTBOARD_WORLD,
                        maxY = DocumentToNoteItems.DEFAULT_ARTBOARD_WORLD,
                        ordinal = 0,
                    ),
                ),
            )
            repository.renderThumbnailAsync(noteId)
            _importEvents.emit(
                ImportEvent.Opened(
                    noteId = noteId,
                    warningCount = document.warnings.size + converted.warnings.size,
                ),
            )
        }
    }

    private companion object {
        const val QUERY_DEBOUNCE_MS = 150L
    }

    /** The picked file's display name, without extension — used as the title. */
    private fun displayNameFor(uri: Uri): String? = try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?.substringBeforeLast('.')
            ?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }
}
