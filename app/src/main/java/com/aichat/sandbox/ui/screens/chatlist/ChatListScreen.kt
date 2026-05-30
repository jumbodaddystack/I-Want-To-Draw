package com.aichat.sandbox.ui.screens.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.ui.components.AppScreenScaffold
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    onChatClick: (String) -> Unit,
    onNewChat: (String) -> Unit,
    viewModel: ChatListViewModel = hiltViewModel(),
    searchViewModel: SearchViewModel = hiltViewModel()
) {
    val chats by viewModel.chats.collectAsState()
    val searchState by searchViewModel.uiState.collectAsState()
    var isSearchActive by remember { mutableStateOf(false) }
    // The search field is collapsed by default — tapping the header icon
    // expands it inline (replaces the old always-on full-width pill that
    // wasted space at the top of the screen).
    var searchExpanded by remember { mutableStateOf(false) }

    AppScreenScaffold(
        title = "Chat",
        actions = {
            IconButton(onClick = {
                searchExpanded = !searchExpanded
                if (!searchExpanded) {
                    isSearchActive = false
                    searchViewModel.clearSearch()
                }
            }) {
                Icon(
                    imageVector = if (searchExpanded) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = if (searchExpanded) "Close search" else "Search conversations",
                )
            }
        },
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Inline search field — only visible once the header icon is tapped.
        if (searchExpanded) {
            SearchBarSection(
                query = searchState.query,
                isActive = isSearchActive,
                onQueryChange = { query ->
                    isSearchActive = query.isNotEmpty()
                    searchViewModel.search(query)
                },
                onClear = {
                    isSearchActive = false
                    searchViewModel.clearSearch()
                }
            )
        }

        // New Chat button
        if (!isSearchActive) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.createNewChat(onNewChat) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                color = MaterialTheme.colorScheme.background
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "New chat",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "New chat",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

        if (isSearchActive) {
            // Search results
            if (searchState.isSearching) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else if (searchState.results.isEmpty() && searchState.query.isNotBlank()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "No results",
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No results found",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    searchState.results.forEach { result ->
                        item(key = "header_${result.chat.id}") {
                            SearchResultHeader(chat = result.chat, onClick = { onChatClick(result.chat.id) })
                        }
                        items(
                            result.matchingMessages.take(3),
                            key = { "msg_${it.id}" }
                        ) { message ->
                            SearchResultMessage(
                                message = message,
                                query = searchState.query,
                                onClick = { onChatClick(result.chat.id) }
                            )
                        }
                        if (result.matchingMessages.size > 3) {
                            item(key = "more_${result.chat.id}") {
                                Text(
                                    text = "+${result.matchingMessages.size - 3} more matches",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(start = 52.dp, bottom = 8.dp)
                                        .clickable { onChatClick(result.chat.id) }
                                )
                            }
                        }
                    }
                }
            }
        } else if (chats.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Chat,
                        contentDescription = "No chats",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No chats yet",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap + New chat to get started",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(chats, key = { it.id }) { chat ->
                    ChatListItem(
                        chat = chat,
                        onClick = { onChatClick(chat.id) },
                        onDelete = { viewModel.deleteChat(chat.id) }
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun SearchBarSection(
    query: String,
    isActive: Boolean,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(
                        "Search conversations...",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium,
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent
                )
            )
            if (isActive) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultHeader(
    chat: Chat,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.background
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Chat,
                contentDescription = "Chat",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = chat.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SearchResultMessage(
    message: com.aichat.sandbox.data.model.Message,
    query: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.background
    ) {
        Row(
            modifier = Modifier.padding(start = 52.dp, end = 16.dp, top = 2.dp, bottom = 2.dp)
        ) {
            Text(
                text = buildSnippet(message.content, query),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun buildSnippet(content: String, query: String): String {
    val lowerContent = content.lowercase()
    val lowerQuery = query.lowercase().trim()
    val index = lowerContent.indexOf(lowerQuery)
    if (index < 0) return content.take(100)

    val start = (index - 30).coerceAtLeast(0)
    val end = (index + lowerQuery.length + 30).coerceAtMost(content.length)
    val prefix = if (start > 0) "..." else ""
    val suffix = if (end < content.length) "..." else ""
    return "$prefix${content.substring(start, end)}$suffix"
}

@Composable
private fun ChatListItem(
    chat: Chat,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val isToday = remember(chat.updatedAt) {
        val chatCal = Calendar.getInstance().apply { timeInMillis = chat.updatedAt }
        val todayCal = Calendar.getInstance()
        chatCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) &&
            chatCal.get(Calendar.DAY_OF_YEAR) == todayCal.get(Calendar.DAY_OF_YEAR)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.background
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Chat,
                contentDescription = "Chat",
                modifier = Modifier
                    .size(20.dp)
                    .padding(top = 2.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chat.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${formatTokens(chat.totalTokens)} tokens, $${formatCost(chat.totalCost)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = if (isToday) timeFormat.format(Date(chat.updatedAt))
                       else dateFormat.format(Date(chat.updatedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete chat?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }
}

private fun formatTokens(tokens: Int): String {
    return when {
        tokens >= 1000 -> String.format("%.1fK", tokens / 1000.0)
        else -> tokens.toString()
    }
}

private fun formatCost(cost: Double): String {
    return when {
        cost < 0.01 -> String.format("%.0f", cost)
        cost < 1.0 -> String.format("%.4f", cost)
        else -> String.format("%.2f", cost)
    }
}
