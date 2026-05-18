package com.aichat.sandbox.ui.screens.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.repository.NoteSearchRepository
import com.aichat.sandbox.data.repository.NoteSearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteSearchScreen(
    onNavigateBack: () -> Unit,
    onOpenNote: (noteId: String) -> Unit,
    viewModel: NoteSearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::setQuery,
                        placeholder = { Text("Search handwriting & text") },
                        leadingIcon = {
                            Icon(Icons.Filled.Search, contentDescription = null)
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    )
                },
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            if (query.isBlank()) {
                Hint(text = "Type to search across all notes and notebooks.")
            } else if (results.isEmpty()) {
                Hint(text = "No matches for \"$query\".")
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(results, key = { it.noteId + it.snippet.hashCode() }) { result ->
                        ResultRow(
                            result = result,
                            onClick = { onOpenNote(result.noteId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ResultRow(result: NoteSearchResult, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.background,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (result.notebook != null) {
                    Icon(
                        imageVector = Icons.Filled.AutoStories,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                val headline = when {
                    result.notebook != null ->
                        "${result.notebook.title} · Page ${result.pageOrdinal + 1}"
                    else -> result.title.ifBlank { "Untitled" }
                }
                Text(
                    text = headline,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = highlightedSnippet(result.snippet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Parse the FTS4 snippet markers (`<b>…</b>`) into an [AnnotatedString]. */
private fun highlightedSnippet(snippet: String): AnnotatedString {
    val builder = AnnotatedString.Builder()
    var i = 0
    while (i < snippet.length) {
        val openIdx = snippet.indexOf("<b>", i)
        if (openIdx < 0) {
            builder.append(snippet.substring(i))
            break
        }
        builder.append(snippet.substring(i, openIdx))
        val closeIdx = snippet.indexOf("</b>", openIdx)
        if (closeIdx < 0) {
            builder.append(snippet.substring(openIdx))
            break
        }
        val match = snippet.substring(openIdx + 3, closeIdx)
        builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        builder.append(match)
        builder.pop()
        i = closeIdx + 4
    }
    return builder.toAnnotatedString()
}

@HiltViewModel
class NoteSearchViewModel @Inject constructor(
    private val repository: NoteSearchRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(value: String) { _query.value = value }

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<NoteSearchResult>> = _query
        .debounce(150L)
        .flatMapLatest { q ->
            flow {
                if (q.isBlank()) emit(emptyList())
                else emit(repository.search(q))
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )
}
