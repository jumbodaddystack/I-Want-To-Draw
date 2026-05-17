package com.aichat.sandbox.ui.screens.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.content.Intent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.aichat.sandbox.data.model.ApiProvider
import com.aichat.sandbox.data.model.ChatSettings
import com.aichat.sandbox.data.model.ImageMetadata
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.ui.components.MarkdownText
import com.aichat.sandbox.ui.components.ModelSelector
import com.aichat.sandbox.ui.components.SettingsSlider
import com.aichat.sandbox.ui.components.chat.SketchAttachmentSheet
import com.aichat.sandbox.ui.components.chat.SketchAttachmentState
import com.aichat.sandbox.data.model.MessageRole
import com.aichat.sandbox.ui.screens.notes.NotePickerSheet
import com.aichat.sandbox.ui.theme.AssistantBubble
import com.aichat.sandbox.ui.theme.UserBubble
import com.google.gson.Gson
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatId: String,
    onNavigateBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val customModels by viewModel.customModels.collectAsState()
    val chat = uiState.chat
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Sketch attachment state (sub-phase 3.4) lives in the composition so
    // the surface is torn down with the screen. Items / undo are cleared on
    // close so reopening starts blank.
    val sketchState = remember { SketchAttachmentState() }

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(uiState.messages.size, uiState.streamingContent, uiState.executingTool) {
        if (uiState.messages.isNotEmpty() || uiState.streamingContent.isNotEmpty()) {
            var targetIndex = uiState.messages.size
            // Account for the regenerate button item
            val lastIsAssistant = uiState.messages.lastOrNull()?.role == MessageRole.ASSISTANT.value
            if (!uiState.isLoading && lastIsAssistant) targetIndex++
            // Account for retry indicator
            if (uiState.retryAttempt > 0) targetIndex++
            // Account for tool execution indicator
            if (uiState.executingTool != null) targetIndex++
            // Account for streaming content
            if (uiState.streamingContent.isNotEmpty()) targetIndex++
            if (targetIndex > 0) {
                listState.animateScrollToItem(targetIndex - 1)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding()
    ) {
        // Top bar
        ChatTopBar(
            title = chat?.model ?: "Loading...",
            pinnedNoteTitle = uiState.pinnedNoteTitle,
            onBack = onNavigateBack,
            onInfoClick = { viewModel.toggleSystemMessageDialog() },
            onMenuClick = { viewModel.toggleSettingsPanel() },
            onPinNoteClick = { viewModel.openPinNotePicker() },
            onUnpinNoteClick = { viewModel.unpinNote() },
        )

        // Plugin notice
        if (chat != null) {
            Text(
                text = "Using model: ${chat.model}",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Messages or examples
        Box(modifier = Modifier.weight(1f)) {
            if (uiState.messages.isEmpty() && uiState.streamingContent.isEmpty()) {
                ExamplesView(
                    onExampleClick = { viewModel.sendMessage(it) }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(uiState.messages, key = { it.id }) { message ->
                        when {
                            message.contentType == "tool_call" -> {
                                ToolCallBubble(message = message)
                            }
                            message.role == "tool" -> {
                                ToolResultBubble(message = message)
                            }
                            else -> {
                                MessageBubble(
                                    message = message,
                                    onDelete = { viewModel.deleteMessage(message) },
                                    onEdit = if (message.role == "user") {
                                        { viewModel.startEditing(message) }
                                    } else null
                                )
                            }
                        }
                    }
                    // Regenerate button (visible when not streaming and last message is from assistant)
                    if (!uiState.isLoading &&
                        uiState.messages.lastOrNull()?.role == MessageRole.ASSISTANT.value
                    ) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.Start
                            ) {
                                OutlinedButton(
                                    onClick = { viewModel.regenerateLastResponse() },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Regenerate",
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        "Regenerate",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                    // Retry indicator
                    if (uiState.retryAttempt > 0) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Retrying... (attempt ${uiState.retryAttempt + 1})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    // Tool execution indicator
                    if (uiState.executingTool != null) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.Start,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Running tool: ${uiState.executingTool}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    // Streaming message
                    if (uiState.streamingContent.isNotEmpty()) {
                        item {
                            MessageBubble(
                                message = Message(
                                    chatId = chatId,
                                    role = "assistant",
                                    content = uiState.streamingContent
                                ),
                                isStreaming = true
                            )
                        }
                    }
                }
            }

            // Error snackbar
            uiState.error?.let { error ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.dismissError() }) {
                            Text("Dismiss")
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.error
                ) {
                    Text(error, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }

        // Sub-phase 4.4: pinned-note chip directly above the composer so
        // the user can see at a glance what context is riding along, and
        // unpin in one tap. Sits between the message list and the input
        // bar so it doesn't shift the scroll position when it appears.
        uiState.pinnedNoteTitle?.let { title ->
            PinnedNoteChip(
                title = title,
                onUnpin = { viewModel.unpinNote() },
            )
        }

        // Input bar
        ChatInputBar(
            isLoading = uiState.isLoading,
            onSend = { content ->
                if (uiState.editingMessageId != null) {
                    viewModel.submitEdit(content)
                } else {
                    viewModel.sendMessage(content)
                }
            },
            onStop = { viewModel.stopGenerating() },
            editingContent = uiState.editingContent,
            onCancelEdit = { viewModel.cancelEditing() },
            attachedImages = uiState.attachedImages,
            onAddImage = { uri -> viewModel.addImage(uri) },
            onRemoveImage = { uri -> viewModel.removeImage(uri) },
            onSketchClick = { sketchState.open() },
            // One-shot prefill from the `?draftText=` nav arg
            // (sub-phase 2.8). Consumed on first composition so it can't
            // re-trigger after rotation.
            consumeDraftText = viewModel::consumeDraftText,
        )
    }

    // Settings panel
    AnimatedVisibility(
        visible = uiState.showSettingsPanel,
        enter = slideInVertically { it },
        exit = slideOutVertically { it }
    ) {
        ChatSettingsPanel(
            chat = chat,
            onDismiss = { viewModel.toggleSettingsPanel() },
            onModelChange = { viewModel.updateModel(it) },
            onTemperatureChange = { viewModel.updateTemperature(it) },
            onTopPChange = { viewModel.updateTopP(it) },
            onMaxTokensChange = { viewModel.updateMaxTokens(it) },
            onPresencePenaltyChange = { viewModel.updatePresencePenalty(it) },
            onFrequencyPenaltyChange = { viewModel.updateFrequencyPenalty(it) },
            onClearHistory = { viewModel.clearHistory() },
            onShareMarkdown = { viewModel.getShareContentAsMarkdown() },
            onShareJson = { viewModel.getShareContentAsJson() },
            toolsEnabled = uiState.toolsEnabled,
            onToggleTools = { viewModel.toggleTools() },
            customModels = customModels,
            onAddCustomModel = { viewModel.addCustomModel(it) },
            onRemoveCustomModel = { viewModel.removeCustomModel(it) }
        )
    }

    // System message dialog
    if (uiState.showSystemMessageDialog) {
        SystemMessageDialog(
            currentMessage = chat?.systemMessage ?: "",
            onDismiss = { viewModel.toggleSystemMessageDialog() },
            onConfirm = {
                viewModel.updateSystemMessage(it)
                viewModel.toggleSystemMessageDialog()
            }
        )
    }

    // Sketch attachment sheet (sub-phase 3.4). Rasterized PNG is appended
    // to the same `attachedImages` list as photo-picker results.
    SketchAttachmentSheet(
        state = sketchState,
        onConfirm = { png ->
            viewModel.attachSketch(png)
            sketchState.close()
        },
        onDismiss = { sketchState.close() },
    )

    // Sub-phase 4.4: pin-a-note picker. Wired to the same notes list the
    // Notes tab uses; tapping a row pins and dismisses.
    if (uiState.showPinNotePicker) {
        val notes by viewModel.notesForPicker.collectAsState()
        NotePickerSheet(
            notes = notes,
            onPick = { noteId -> viewModel.pinNote(noteId) },
            onDismiss = { viewModel.dismissPinNotePicker() },
        )
    }
}

/**
 * Sub-phase 4.4 chat-side affordance: shows the pinned note's title with a
 * close action that unpins. Visually distinct from the photo attachment
 * strip — this isn't an attachment the user picked, it's context that rides
 * every send until removed.
 */
@Composable
private fun PinnedNoteChip(title: String, onUnpin: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Pinned: $title",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            IconButton(
                onClick = onUnpin,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Unpin",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    title: String,
    pinnedNoteTitle: String?,
    onBack: () -> Unit,
    onInfoClick: () -> Unit,
    onMenuClick: () -> Unit,
    onPinNoteClick: () -> Unit,
    onUnpinNoteClick: () -> Unit,
) {
    var pinMenuExpanded by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Chat,
                    contentDescription = "Chat",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Dialogue",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = "Options",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        actions = {
            IconButton(onClick = onInfoClick) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = "System message",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            // Sub-phase 4.4: pin / unpin a note as ambient context for this
            // chat. Wrapped in a Box so the DropdownMenu anchors under the
            // button; tap reveals "Pin a note" when unpinned, or "Unpin
            // {title}" when something's already pinned.
            Box {
                IconButton(onClick = { pinMenuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = if (pinnedNoteTitle != null) "Pinned note"
                            else "Pin a note",
                        tint = if (pinnedNoteTitle != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                    )
                }
                DropdownMenu(
                    expanded = pinMenuExpanded,
                    onDismissRequest = { pinMenuExpanded = false },
                ) {
                    if (pinnedNoteTitle != null) {
                        DropdownMenuItem(
                            text = { Text("Unpin “$pinnedNoteTitle”") },
                            onClick = {
                                pinMenuExpanded = false
                                onUnpinNoteClick()
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Pin a note") },
                            onClick = {
                                pinMenuExpanded = false
                                onPinNoteClick()
                            },
                        )
                    }
                }
            }
            IconButton(onClick = onMenuClick) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background
        )
    )
}

@Composable
private fun ExamplesView(onExampleClick: (String) -> Unit) {
    val examples = listOf(
        "Explain quantum computing in simple terms",
        "Got any creative ideas for a 10 year old's birthday?",
        "What is the best way to learn AI?",
        "How do I make an HTTP request in Javascript?"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Examples",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        examples.forEach { example ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable { onExampleClick(example) },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = example,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    isStreaming: Boolean = false,
    onDelete: () -> Unit = {},
    onEdit: (() -> Unit)? = null
) {
    val isUser = message.role == "user"
    val clipboardManager = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .let { if (isUser) it.align(Alignment.End) else it.align(Alignment.Start) }
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (isUser) UserBubble else AssistantBubble)
                .clickable { showMenu = true }
                .padding(12.dp)
        ) {
            Column {
                // Display inline image thumbnails for multimodal messages
                if (isUser && message.contentType == "multimodal" && message.metadata != null) {
                    val imageMetadata = remember(message.metadata) {
                        try {
                            Gson().fromJson(message.metadata, ImageMetadata::class.java)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    imageMetadata?.images?.forEach { img ->
                        AsyncImage(
                            model = img.dataUri,
                            contentDescription = "Attached image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .padding(bottom = 8.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                if (isUser) {
                    if (message.content.isNotBlank()) {
                        Text(
                            text = message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimary,
                            lineHeight = 22.sp
                        )
                    }
                } else {
                    MarkdownText(
                        text = message.content + if (isStreaming) "▊" else "",
                        modifier = if (isStreaming) Modifier.semantics {
                            contentDescription = "Assistant is typing"
                        } else Modifier,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Context menu
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Copy") },
                onClick = {
                    clipboardManager.setText(AnnotatedString(message.content))
                    showMenu = false
                },
                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = "Copy") }
            )
            if (isUser && onEdit != null) {
                DropdownMenuItem(
                    text = { Text("Edit") },
                    onClick = {
                        onEdit()
                        showMenu = false
                    },
                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                )
            }
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                onClick = {
                    onDelete()
                    showMenu = false
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            )
        }

        // Token info for assistant messages
        if (!isUser && !isStreaming && message.tokenCount > 0) {
            Text(
                text = "${message.tokenCount} tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 4.dp, top = 2.dp)
            )
        }
    }
}

@Composable
private fun ToolCallBubble(message: Message) {
    var expanded by remember { mutableStateOf(false) }
    val toolMeta = remember(message.metadata) {
        try {
            message.metadata?.let {
                Gson().fromJson(it, com.aichat.sandbox.data.model.ToolCallMetadata::class.java)
            }
        } catch (_: Exception) { null }
    }
    val toolCalls = toolMeta?.toolCalls ?: return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            onClick = { expanded = !expanded }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Build,
                        contentDescription = "Tool call",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Tool Call${if (toolCalls.size > 1) "s (${toolCalls.size})" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                    )
                }

                // Show tool names as chips
                toolCalls.forEach { tc ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = tc.function.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Show arguments when expanded
                AnimatedVisibility(visible = expanded) {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        toolCalls.forEach { tc ->
                            Text(
                                text = "${tc.function.name}(${tc.function.arguments})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(8.dp)
                                    .fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }

                // Show text content if present
                if (message.content.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    MarkdownText(
                        text = message.content,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolResultBubble(message: Message) {
    var expanded by remember { mutableStateOf(false) }
    val toolMeta = remember(message.metadata) {
        try {
            message.metadata?.let {
                Gson().fromJson(it, com.aichat.sandbox.data.model.ToolCallMetadata::class.java)
            }
        } catch (_: Exception) { null }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
            onClick = { expanded = !expanded }
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Tool result",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = toolMeta?.toolName ?: "Tool Result",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(8.dp)
                            .fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    isLoading: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    editingContent: String? = null,
    onCancelEdit: () -> Unit = {},
    attachedImages: List<Uri> = emptyList(),
    onAddImage: (Uri) -> Unit = {},
    onRemoveImage: (Uri) -> Unit = {},
    onSketchClick: () -> Unit = {},
    consumeDraftText: () -> String? = { null },
) {
    var text by remember { mutableStateOf("") }

    // Photo picker launcher (multiple images)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)
    ) { uris ->
        uris.forEach { uri -> onAddImage(uri) }
    }

    // One-shot composer prefill from the `?draftText=` nav arg
    // (sub-phase 2.8 — "Send to chat" reply action). Read once on first
    // composition and clear in the VM so rotation can't re-trigger.
    LaunchedEffect(Unit) {
        val draft = consumeDraftText()
        if (!draft.isNullOrEmpty() && text.isEmpty()) {
            text = draft
        }
    }

    // When editing starts, populate the text field
    LaunchedEffect(editingContent) {
        if (editingContent != null) {
            text = editingContent
        }
    }

    val isEditing = editingContent != null
    val canSend = text.isNotBlank() || attachedImages.isNotEmpty()

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Image attachment preview strip
            if (attachedImages.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    attachedImages.forEach { uri ->
                        Box(
                            modifier = Modifier.size(64.dp)
                        ) {
                            AsyncImage(
                                model = uri,
                                contentDescription = "Attached image",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                            // Remove button
                            IconButton(
                                onClick = { onRemoveImage(uri) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(20.dp)
                                    .background(
                                        MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                        CircleShape
                                    )
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove image",
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // Cancel edit button
                if (isEditing) {
                    IconButton(
                        onClick = {
                            text = ""
                            onCancelEdit()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Cancel edit",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Image attachment button
                if (!isEditing && !isLoading) {
                    IconButton(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = "Attach image",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    // Sketch attachment button (sub-phase 3.4) — opens the
                    // bottom-sheet drawing surface. Available without a stylus;
                    // finger sketches are explicitly supported.
                    IconButton(
                        onClick = onSketchClick,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Draw,
                            contentDescription = "Sketch",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Text input
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp, max = 120.dp)
                        .padding(horizontal = 4.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = if (isEditing) "Edit message" else "Send a message",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 5
                    )
                }

                // Send / Stop button
                if (isLoading) {
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (canSend) {
                                onSend(text)
                                text = ""
                            }
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = if (canSend) MaterialTheme.colorScheme.onSurface
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemMessageDialog(
    currentMessage: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(currentMessage) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = null,
        text = {
            Column {
                Text(
                    text = "System message is a global message sent with the chat every time. It can be used to set the context of the chat, such as the topic of the chat, the purpose of the chat, etc.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("System message") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text("OK", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.primary)
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatSettingsPanel(
    chat: com.aichat.sandbox.data.model.Chat?,
    onDismiss: () -> Unit,
    onModelChange: (String) -> Unit,
    onTemperatureChange: (Float) -> Unit,
    onTopPChange: (Float) -> Unit,
    onMaxTokensChange: (Int) -> Unit,
    onPresencePenaltyChange: (Float) -> Unit,
    onFrequencyPenaltyChange: (Float) -> Unit,
    onClearHistory: () -> Unit,
    onShareMarkdown: () -> String = { "" },
    onShareJson: () -> String = { "" },
    toolsEnabled: Boolean = true,
    onToggleTools: () -> Unit = {},
    customModels: List<String> = emptyList(),
    onAddCustomModel: ((String) -> Unit)? = null,
    onRemoveCustomModel: ((String) -> Unit)? = null
) {
    if (chat == null) return

    val builtInModels = ApiProvider.defaults.flatMap { it.models }
    val allModels = builtInModels + customModels.filter { it !in builtInModels }
    val context = LocalContext.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 32.dp)
        ) {
            // Share options
            TextButton(onClick = {
                val content = onShareMarkdown()
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, content)
                }
                context.startActivity(Intent.createChooser(intent, "Share as Markdown"))
            }) {
                Icon(Icons.Default.Share, contentDescription = "Share as Markdown", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Share as Markdown")
            }
            TextButton(onClick = {
                val content = onShareJson()
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_TEXT, content)
                }
                context.startActivity(Intent.createChooser(intent, "Share as JSON"))
            }) {
                Icon(Icons.Default.Share, contentDescription = "Share as JSON", modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Share as JSON")
            }

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Model selector
            ModelSelector(
                label = "Model",
                selectedModel = chat.model,
                models = allModels,
                onModelSelected = onModelChange,
                customModels = customModels,
                onAddCustomModel = onAddCustomModel,
                onRemoveCustomModel = onRemoveCustomModel
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Temperature
            SettingsSlider(
                label = "temperature",
                value = chat.temperature,
                valueRange = 0f..2f,
                onValueChange = onTemperatureChange,
                displayFormat = { String.format("%.1f", it) }
            )

            // Top P
            SettingsSlider(
                label = "top_p",
                value = chat.topP,
                valueRange = 0f..1f,
                onValueChange = onTopPChange,
                displayFormat = { String.format("%.1f", it) }
            )

            // Max tokens
            SettingsSlider(
                label = "Max tokens",
                value = chat.maxTokens.toFloat(),
                valueRange = 1f..ChatSettings.Defaults.MAX_TOKENS_LIMIT,
                onValueChange = { onMaxTokensChange(it.toInt()) },
                displayFormat = { it.toInt().toString() }
            )

            // Presence penalty
            SettingsSlider(
                label = "Presence penalty",
                value = chat.presencePenalty,
                valueRange = -2f..2f,
                onValueChange = onPresencePenaltyChange,
                displayFormat = { String.format("%.1f", it) }
            )

            // Frequency penalty
            SettingsSlider(
                label = "Frequency penalty",
                value = chat.frequencyPenalty,
                valueRange = -2f..2f,
                onValueChange = onFrequencyPenaltyChange,
                displayFormat = { String.format("%.1f", it) }
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Tools toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Tools",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "Enable function calling (calculator, date/time)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = toolsEnabled,
                    onCheckedChange = { onToggleTools() }
                )
            }
        }
    }
}

