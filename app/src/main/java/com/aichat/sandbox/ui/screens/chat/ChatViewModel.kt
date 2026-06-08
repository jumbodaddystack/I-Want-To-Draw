package com.aichat.sandbox.ui.screens.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.ImageAttachment
import com.aichat.sandbox.data.model.ImageMetadata
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.model.MessageRole
import com.aichat.sandbox.data.model.ModelPricing
import com.aichat.sandbox.data.model.ToolCallMetadata
import com.aichat.sandbox.data.local.PreferencesManager
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.supportsVision
import com.aichat.sandbox.data.notes.PendingDraftStore
import com.aichat.sandbox.data.notes.PinnedNoteCache
import com.aichat.sandbox.data.remote.ApiResult
import com.aichat.sandbox.data.remote.StreamEvent
import com.aichat.sandbox.data.repository.ChatRepository
import com.aichat.sandbox.data.repository.NoteRepository
import com.aichat.sandbox.data.tools.ToolRegistry
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject

data class ChatUiState(
    val chat: Chat? = null,
    val messages: List<Message> = emptyList(),
    val isLoading: Boolean = false,
    val streamingContent: String = "",
    val error: String? = null,
    val showSettingsPanel: Boolean = false,
    val showSystemMessageDialog: Boolean = false,
    val retryAttempt: Int = 0,
    val editingMessageId: String? = null,
    val editingContent: String? = null,
    val attachedImages: List<Uri> = emptyList(),
    val toolsEnabled: Boolean = true,
    val executingTool: String? = null, // name of tool currently being executed
    /**
     * One-shot composer prefill supplied via the `?draftText=` nav arg
     * (sub-phase 2.8 — the AI side sheet's "Send to chat" reply action lands
     * here). [ChatScreen] consumes it on first composition via
     * [consumeDraftText] so rotation or recomposition can't re-trigger.
     */
    val draftText: String? = null,
    /**
     * Sub-phase 4.4: title of the currently-pinned note (or `null` if no pin
     * — or the note was deleted from under us). Drives the composer chip;
     * the pin itself lives on `Chat.pinnedNoteId`.
     */
    val pinnedNoteTitle: String? = null,
    /**
     * Sub-phase 4.4: whether the note-picker sheet is open. The list itself
     * is streamed separately via [ChatViewModel.notesForPicker] so an empty
     * list doesn't have to round-trip through the UI state.
     */
    val showPinNotePicker: Boolean = false,
    /**
     * True when the provider for the current chat's model has no API key set.
     * Drives a persistent inline banner that links to Settings, so a new user
     * isn't dead-ended on a raw 401. Re-evaluated whenever the model changes.
     */
    val needsApiKey: Boolean = false,
    /**
     * Set when a message edit would delete following messages — drives a
     * confirm dialog before the destructive truncate-and-regenerate. `null`
     * means no pending confirmation.
     */
    val pendingEditConfirm: PendingEditConfirm? = null,
)

/**
 * A message edit awaiting user confirmation. [followingCount] is the number of
 * messages after the edited one that will be removed when the edit proceeds.
 */
data class PendingEditConfirm(
    val newContent: String,
    val followingCount: Int,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ChatRepository,
    private val toolRegistry: ToolRegistry,
    private val preferencesManager: PreferencesManager,
    private val noteRepository: NoteRepository,
    private val pinnedNoteCache: PinnedNoteCache,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val gson = Gson()

    private val chatId: String = savedStateHandle.get<String>("chatId") ?: ""

    // Composer prefill: collapse the sub-phase 2.8 nav-arg path and the
    // sub-phase 4.3 [PendingDraftStore] handover into the same initial
    // state. The store wins when present (the 4.3 picker always writes
    // there); the URL arg is the legacy fallback for any caller still
    // navigating with `?draftText=`. Both are consumed exactly once on
    // VM construction so rotation can't re-prefill.
    private val initialDraft: PendingDraftStore.Entry? = PendingDraftStore.consume(chatId)

    private val _uiState = MutableStateFlow(
        ChatUiState(
            draftText = initialDraft?.draftText
                ?: savedStateHandle.get<String>("draftText")
                    ?.takeIf { it.isNotBlank() },
            attachedImages = listOfNotNull(initialDraft?.imageUri),
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null

    private val _customModels = MutableStateFlow<List<String>>(emptyList())
    val customModels: StateFlow<List<String>> = _customModels.asStateFlow()

    /**
     * Notes available to the pin picker (sub-phase 4.4). Streamed straight
     * off [NoteRepository]; mirrors the Notes tab's order so the picker feels
     * like a slim copy of that list. WhileSubscribed so we don't keep the
     * upstream warm when the picker is closed.
     */
    val notesForPicker: StateFlow<List<Note>> = noteRepository.observeNotes()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList(),
        )

    /**
     * Live pinned-note row for the currently-active chat (sub-phase 4.4).
     * `null` whenever the chat isn't pinned or the pinned note row has been
     * deleted from under us. Drives the composer chip via
     * [ChatUiState.pinnedNoteTitle].
     */
    val pinnedNote: StateFlow<Note?> = repository.getChatById(chatId)
        .flatMapLatest { chat ->
            val pinId = chat?.pinnedNoteId
            if (pinId.isNullOrBlank()) flowOf(null) else noteRepository.observeNote(pinId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = null,
        )

    init {
        viewModelScope.launch {
            repository.getChatById(chatId).collect { chat ->
                _uiState.update { it.copy(chat = chat) }
                // Re-check credentials whenever the chat (and thus its model)
                // changes so the "add an API key" banner reflects the model in
                // use — switching to a provider you haven't keyed surfaces it.
                if (chat != null) {
                    val hasKey = preferencesManager.hasApiKeyFor(chat.model)
                    _uiState.update { it.copy(needsApiKey = !hasKey) }
                }
            }
        }
        viewModelScope.launch {
            repository.getMessagesForChat(chatId).collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
        viewModelScope.launch {
            preferencesManager.customModels.collect { modelsMap ->
                _customModels.value = modelsMap.values.flatten()
            }
        }
        // Pinned-note chip projection — collapses the pinned-note row's
        // title into the UI state so the chat composer can show it without
        // having to collect another flow itself.
        viewModelScope.launch {
            pinnedNote.collect { note ->
                _uiState.update { it.copy(pinnedNoteTitle = note?.title?.ifBlank { "Untitled" }) }
            }
        }
    }

    /**
     * Read and clear the one-shot composer prefill. Idempotent — subsequent
     * calls return null. The screen calls this from a `LaunchedEffect` on
     * first composition so a rotation or recomposition can't re-prefill the
     * text field after the user has already started typing.
     */
    fun consumeDraftText(): String? {
        val current = _uiState.value.draftText ?: return null
        _uiState.update { it.copy(draftText = null) }
        return current
    }

    fun addImage(uri: Uri) {
        _uiState.update { it.copy(attachedImages = it.attachedImages + uri) }
    }

    /**
     * Attach a PNG produced by the chat composer's sketch sheet
     * (sub-phase 3.4). The bytes are written to the app's cache dir and
     * the resulting file Uri is appended to [ChatUiState.attachedImages],
     * so the existing send-time encoder ([encodeImageToBase64]) picks it
     * up alongside any photo-picked images and converts it to the
     * data-URI form the API expects.
     */
    fun attachSketch(pngBytes: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(appContext.cacheDir, SKETCH_CACHE_DIR).apply { mkdirs() }
            val file = File(dir, "sketch-${UUID.randomUUID()}.png")
            try {
                file.outputStream().use { it.write(pngBytes) }
            } catch (_: Exception) {
                return@launch
            }
            withContext(Dispatchers.Main) {
                addImage(Uri.fromFile(file))
            }
        }
    }

    fun removeImage(uri: Uri) {
        _uiState.update { it.copy(attachedImages = it.attachedImages - uri) }
    }

    fun clearImages() {
        _uiState.update { it.copy(attachedImages = emptyList()) }
    }

    fun sendMessage(content: String) {
        val chat = _uiState.value.chat ?: return
        if (content.isBlank() && _uiState.value.attachedImages.isEmpty()) return
        if (content.length > MAX_MESSAGE_LENGTH) {
            _uiState.update { it.copy(error = "Message too long (max ${MAX_MESSAGE_LENGTH / 1000}K characters)") }
            return
        }

        val imageUris = _uiState.value.attachedImages.toList()

        viewModelScope.launch {
            // Pre-flight the credentials so we never fire a request that's
            // guaranteed to 401. Surface the inline "add a key" banner instead
            // and leave the conversation untouched (no half-sent user turn).
            if (!preferencesManager.hasApiKeyFor(chat.model)) {
                _uiState.update { it.copy(needsApiKey = true) }
                return@launch
            }

            // Encode images to base64 if present
            val hasImages = imageUris.isNotEmpty()
            var metadata: String? = null
            if (hasImages) {
                val imageAttachments = withContext(Dispatchers.IO) {
                    imageUris.mapNotNull { uri -> encodeImageToBase64(uri) }
                }
                if (imageAttachments.isNotEmpty()) {
                    metadata = gson.toJson(ImageMetadata(images = imageAttachments))
                }
            }

            // Save user message
            val userMessage = Message(
                chatId = chatId,
                role = MessageRole.USER.value,
                content = content.trim(),
                contentType = if (hasImages && metadata != null) "multimodal" else "text",
                metadata = metadata
            )
            repository.insertMessage(userMessage)

            // Clear attached images
            _uiState.update { it.copy(attachedImages = emptyList()) }

            val isFirstMessage = _uiState.value.messages.isEmpty()

            // Update chat title with placeholder if it's the first message
            if (isFirstMessage) {
                val title = content.trim().take(40)
                repository.updateChat(chat.copy(title = title))
            }

            _uiState.update { it.copy(isLoading = true, error = null, streamingContent = "", retryAttempt = 0, needsApiKey = false) }

            // Get all messages including the new one
            val allMessages = _uiState.value.messages + userMessage

            // Start the streaming + tool loop
            streamWithToolLoop(chat, allMessages, isFirstMessage, content.trim())
        }
    }

    /**
     * Streams a response from the API. If the response contains tool calls,
     * executes them and sends the results back, repeating until the assistant
     * responds with plain text or the safety limit is reached.
     */
    private fun streamWithToolLoop(
        chat: Chat,
        initialMessages: List<Message>,
        isFirstMessage: Boolean = false,
        userContent: String = "",
        toolRound: Int = 0
    ) {
        val tools = if (_uiState.value.toolsEnabled && toolRegistry.hasTools())
            toolRegistry.getToolDefinitions() else null

        streamJob = viewModelScope.launch {
            val streamContent = StringBuilder()
            val onRetry: (Int) -> Unit = { attempt ->
                _uiState.update { it.copy(retryAttempt = attempt) }
            }
            // Sub-phase 4.4: resolve the pinned-note context once per send
            // (not per tool round) so the model sees the same note across
            // every tool-loop iteration. Empty pin → both extras null and
            // the call site behaves as if pinning weren't there.
            val pinned = resolvePinnedContext(chat)
            repository.sendMessageStream(
                chat = chat,
                messages = initialMessages,
                onRetryAttempt = onRetry,
                tools = tools,
                extraImageOnLastUserTurn = pinned.image,
                extraSystemSuffix = pinned.systemSuffix,
            ).collect { event ->
                when (event) {
                    is StreamEvent.Delta -> {
                        streamContent.append(event.content)
                        _uiState.update { it.copy(streamingContent = streamContent.toString()) }
                    }
                    is StreamEvent.ToolCallDelta -> {
                        // Tool call deltas are accumulated inside processStream
                    }
                    is StreamEvent.Complete -> {
                        val assistantContent = streamContent.toString()
                        val toolCalls = event.toolCalls

                        if (!toolCalls.isNullOrEmpty() && toolRound < MAX_TOOL_ROUNDS) {
                            // Save assistant message with tool_calls metadata
                            val toolCallMeta = gson.toJson(ToolCallMetadata(toolCalls = toolCalls))
                            val assistantMessage = Message(
                                chatId = chatId,
                                role = MessageRole.ASSISTANT.value,
                                content = assistantContent,
                                contentType = "tool_call",
                                metadata = toolCallMeta,
                                tokenCount = event.usage?.totalTokens ?: estimateTokens(assistantContent)
                            )
                            repository.insertMessage(assistantMessage)
                            _uiState.update { it.copy(streamingContent = "") }

                            // Execute each tool call and insert result messages
                            val toolResultMessages = mutableListOf<Message>()
                            for (tc in toolCalls) {
                                _uiState.update { it.copy(executingTool = tc.function.name) }
                                val result = toolRegistry.executeTool(tc.function.name, tc.function.arguments)
                                val toolResultMeta = gson.toJson(ToolCallMetadata(
                                    toolCallId = tc.id,
                                    toolName = tc.function.name,
                                    toolResult = result
                                ))
                                val toolMessage = Message(
                                    chatId = chatId,
                                    role = MessageRole.TOOL.value,
                                    content = result,
                                    contentType = "tool_result",
                                    metadata = toolResultMeta
                                )
                                repository.insertMessage(toolMessage)
                                toolResultMessages.add(toolMessage)
                            }
                            _uiState.update { it.copy(executingTool = null) }

                            // Continue the loop with updated messages
                            val updatedMessages = _uiState.value.messages
                            streamWithToolLoop(chat, updatedMessages, isFirstMessage, userContent, toolRound + 1)
                        } else {
                            // Normal completion (no tool calls or limit reached)
                            val assistantMessage = Message(
                                chatId = chatId,
                                role = MessageRole.ASSISTANT.value,
                                content = assistantContent,
                                tokenCount = event.usage?.totalTokens ?: estimateTokens(assistantContent)
                            )
                            repository.insertMessage(assistantMessage)

                            val totalTokens = (event.usage?.totalTokens ?: estimateTokens(assistantContent))
                            val cost = estimateCost(chat.model, event.usage?.promptTokens ?: 0, event.usage?.completionTokens ?: 0)
                            repository.updateChat(chat.copy(
                                totalTokens = chat.totalTokens + totalTokens,
                                totalCost = chat.totalCost + cost
                            ))

                            _uiState.update { it.copy(isLoading = false, streamingContent = "", retryAttempt = 0, executingTool = null) }

                            if (isFirstMessage && assistantContent.isNotBlank()) {
                                generateAiTitle(chat, userContent, assistantContent)
                            }
                        }
                    }
                    is StreamEvent.Error -> {
                        handleNonStreamingFallback(chat, initialMessages, isFirstMessage, userContent)
                    }
                }
            }
        }
    }

    private suspend fun handleNonStreamingFallback(
        chat: Chat,
        messages: List<Message>,
        isFirstMessage: Boolean = false,
        userContent: String = ""
    ) {
        val onRetry: (Int) -> Unit = { attempt ->
            _uiState.update { it.copy(retryAttempt = attempt) }
        }
        val tools = if (_uiState.value.toolsEnabled && toolRegistry.hasTools())
            toolRegistry.getToolDefinitions() else null
        // sendMessage uses the non-streaming path; pinned-note context
        // applies there too but doesn't flow through the existing
        // ApiClient.sendMessage signature today. Leaving the fallback as-is
        // is correct for the user-facing pin contract (the streaming path
        // is the normal route); when the streaming attempt errors we already
        // surface the failure rather than silently dropping context.
        when (val result = repository.sendMessage(chat, messages, onRetry, tools)) {
            is ApiResult.Success -> {
                val response = result.data
                val content = response.choices?.firstOrNull()?.message?.content?.toString() ?: ""
                val usage = response.usage

                val assistantMessage = Message(
                    chatId = chatId,
                    role = MessageRole.ASSISTANT.value,
                    content = content,
                    tokenCount = usage?.totalTokens ?: estimateTokens(content)
                )
                repository.insertMessage(assistantMessage)

                val totalTokens = usage?.totalTokens ?: estimateTokens(content)
                val cost = estimateCost(chat.model, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0)
                repository.updateChat(chat.copy(
                    totalTokens = chat.totalTokens + totalTokens,
                    totalCost = chat.totalCost + cost
                ))

                _uiState.update { it.copy(isLoading = false, streamingContent = "", retryAttempt = 0, executingTool = null) }

                // Auto-generate title after first assistant response
                if (isFirstMessage && content.isNotBlank()) {
                    generateAiTitle(chat, userContent, content)
                }
            }
            is ApiResult.Error -> {
                _uiState.update { it.copy(isLoading = false, error = result.message, streamingContent = "", retryAttempt = 0, executingTool = null) }
            }
            is ApiResult.Loading -> {}
        }
    }

    fun stopGenerating() {
        streamJob?.cancel()
        streamJob = null
        val streamingContent = _uiState.value.streamingContent
        if (streamingContent.isNotEmpty()) {
            viewModelScope.launch {
                val assistantMessage = Message(
                    chatId = chatId,
                    role = MessageRole.ASSISTANT.value,
                    content = streamingContent
                )
                repository.insertMessage(assistantMessage)
            }
        }
        _uiState.update { it.copy(isLoading = false, streamingContent = "", executingTool = null) }
    }

    fun regenerateLastResponse() {
        val chat = _uiState.value.chat ?: return
        val messages = _uiState.value.messages
        if (messages.isEmpty()) return

        // Find the last assistant message
        val lastMessage = messages.last()
        if (lastMessage.role != MessageRole.ASSISTANT.value) return

        viewModelScope.launch {
            // Delete the last assistant message
            repository.deleteMessage(lastMessage)

            // Re-send using the remaining message history (which ends with the user message)
            val remainingMessages = messages.dropLast(1)
            if (remainingMessages.isEmpty()) return@launch

            _uiState.update { it.copy(isLoading = true, error = null, streamingContent = "", retryAttempt = 0) }

            streamWithToolLoop(chat, remainingMessages)
        }
    }

    /**
     * Re-send the conversation after a failed turn. On error the user's
     * message is already persisted (the assistant reply never arrived), so the
     * current history already ends with it — we just re-run the stream loop
     * rather than making the user retype. No-op if there's nothing to send.
     */
    fun retryLastSend() {
        val chat = _uiState.value.chat ?: return
        if (_uiState.value.isLoading) return
        val messages = _uiState.value.messages
        if (messages.isEmpty()) return
        _uiState.update { it.copy(isLoading = true, error = null, streamingContent = "", retryAttempt = 0) }
        streamWithToolLoop(chat, messages)
    }

    fun startEditing(message: Message) {
        _uiState.update { it.copy(editingMessageId = message.id, editingContent = message.content) }
        // 3.3: re-attach a multimodal message's images so editing it doesn't
        // silently drop them. The originals only survive as base64 data URIs
        // in the message metadata, so decode each back into a cache file the
        // existing send-time encoder ([encodeImageToBase64]) can re-open.
        if (message.contentType == "multimodal" && message.metadata != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val meta = try {
                    gson.fromJson(message.metadata, ImageMetadata::class.java)
                } catch (_: Exception) { null }
                val uris = meta?.images.orEmpty().mapNotNull { dataUriToCacheFile(it.dataUri) }
                if (uris.isNotEmpty()) withContext(Dispatchers.Main) {
                    // Only apply if the user is still editing the same message.
                    if (_uiState.value.editingMessageId == message.id) {
                        _uiState.update { it.copy(attachedImages = uris) }
                    }
                }
            }
        }
    }

    fun cancelEditing() {
        // Drop any images we re-attached for the edit so they can't leak into
        // a subsequent fresh message.
        _uiState.update { it.copy(editingMessageId = null, editingContent = null, attachedImages = emptyList()) }
    }

    fun submitEdit(newContent: String) {
        val editingId = _uiState.value.editingMessageId ?: return
        if (newContent.isBlank()) return

        val messages = _uiState.value.messages
        val idx = messages.indexOfFirst { it.id == editingId }
        if (idx < 0) return

        // Everything strictly after the edited message is removed when the edit
        // goes through. Confirm first when that would destroy replies; if the
        // edited message is the last one, there's nothing to lose — just go.
        val followingCount = messages.size - idx - 1
        if (followingCount > 0) {
            _uiState.update {
                it.copy(pendingEditConfirm = PendingEditConfirm(newContent.trim(), followingCount))
            }
        } else {
            performEdit(newContent.trim())
        }
    }

    /** Proceed with a previously-confirmed destructive edit. */
    fun confirmPendingEdit() {
        val pending = _uiState.value.pendingEditConfirm ?: return
        _uiState.update { it.copy(pendingEditConfirm = null) }
        performEdit(pending.newContent)
    }

    /** Dismiss the edit-confirm dialog and stay in edit mode. */
    fun dismissPendingEdit() {
        _uiState.update { it.copy(pendingEditConfirm = null) }
    }

    /**
     * Truncate the conversation at the edited message and re-send the new
     * content. Deletes by an explicit id set (not by `createdAt`) so messages
     * that share a millisecond — common when a tool loop inserts in bursts —
     * can't be over- or under-deleted.
     */
    private fun performEdit(newContent: String) {
        val editingId = _uiState.value.editingMessageId ?: return
        val messages = _uiState.value.messages
        val idx = messages.indexOfFirst { it.id == editingId }
        if (idx < 0) return
        val idsToDelete = messages.drop(idx).map { it.id }

        viewModelScope.launch {
            repository.deleteMessagesByIds(idsToDelete)
            // Clear editing state but keep any re-attached images so they ride
            // along on the re-send, then reuse the normal send flow.
            _uiState.update { it.copy(editingMessageId = null, editingContent = null) }
            sendMessage(newContent)
        }
    }

    fun deleteMessage(message: Message) {
        viewModelScope.launch {
            repository.deleteMessage(message)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearChatHistory(chatId)
            _uiState.value.chat?.let { chat ->
                repository.updateChat(chat.copy(totalTokens = 0, totalCost = 0.0))
            }
        }
    }

    fun updateModel(model: String) {
        viewModelScope.launch {
            _uiState.value.chat?.let { chat ->
                repository.updateChat(chat.copy(model = model))
            }
        }
    }

    fun updateSystemMessage(message: String) {
        viewModelScope.launch {
            _uiState.value.chat?.let { chat ->
                repository.updateChat(chat.copy(systemMessage = message))
            }
        }
    }

    fun updateTemperature(value: Float) {
        viewModelScope.launch {
            _uiState.value.chat?.let { chat ->
                repository.updateChat(chat.copy(temperature = value))
            }
        }
    }

    fun updateTopP(value: Float) {
        viewModelScope.launch {
            _uiState.value.chat?.let { chat ->
                repository.updateChat(chat.copy(topP = value))
            }
        }
    }

    fun updateMaxTokens(value: Int) {
        viewModelScope.launch {
            _uiState.value.chat?.let { chat ->
                repository.updateChat(chat.copy(maxTokens = value))
            }
        }
    }

    fun updatePresencePenalty(value: Float) {
        viewModelScope.launch {
            _uiState.value.chat?.let { chat ->
                repository.updateChat(chat.copy(presencePenalty = value))
            }
        }
    }

    fun updateFrequencyPenalty(value: Float) {
        viewModelScope.launch {
            _uiState.value.chat?.let { chat ->
                repository.updateChat(chat.copy(frequencyPenalty = value))
            }
        }
    }

    fun toggleSettingsPanel() {
        _uiState.update { it.copy(showSettingsPanel = !it.showSettingsPanel) }
    }

    fun toggleSystemMessageDialog() {
        _uiState.update { it.copy(showSystemMessageDialog = !it.showSystemMessageDialog) }
    }

    fun dismissError() {
        _uiState.update { it.copy(error = null) }
    }

    fun getShareContentAsMarkdown(): String {
        val chat = _uiState.value.chat ?: return ""
        val messages = _uiState.value.messages
        val sb = StringBuilder()
        sb.appendLine("## ${chat.title}")
        sb.appendLine("Model: ${chat.model}\n")
        messages.forEach { msg ->
            val role = if (msg.role == "user") "**User**" else "**Assistant**"
            sb.appendLine("$role:\n${msg.content}\n")
        }
        return sb.toString()
    }

    fun getShareContentAsJson(): String {
        val chat = _uiState.value.chat ?: return "{}"
        val messages = _uiState.value.messages
        val data = mapOf(
            "title" to chat.title,
            "model" to chat.model,
            "messages" to messages.map { mapOf("role" to it.role, "content" to it.content) }
        )
        return GsonBuilder().setPrettyPrinting().create().toJson(data)
    }

    /** Resolved pinned-note attachment for a single send (sub-phase 4.4). */
    private data class PinnedContext(
        val image: ByteArray?,
        val systemSuffix: String?,
    )

    /**
     * Resolve the pinned-note attachment for [chat]'s next send. Returns
     * empty extras when nothing is pinned, when the pin's target row has
     * vanished, or when the note has no renderable content. Vision-capable
     * chats get a fresh PNG (cache-backed by [PinnedNoteCache], invalidated
     * by `Note.updatedAt`); non-vision chats get the OCR text suffix.
     *
     * Failures here are non-fatal — the send proceeds without the pinned
     * context rather than blocking the user on a render glitch.
     */
    private suspend fun resolvePinnedContext(chat: Chat): PinnedContext {
        val pinId = chat.pinnedNoteId ?: return PinnedContext(null, null)
        val note = noteRepository.getNote(pinId) ?: return PinnedContext(null, null)
        return if (chat.supportsVision()) {
            val items = noteRepository.getItems(pinId)
            val bytes = try {
                pinnedNoteCache.getOrRender(note, items)
            } catch (_: Throwable) {
                null
            }
            PinnedContext(image = bytes, systemSuffix = null)
        } else {
            val ocr = note.ocrText.orEmpty().take(PINNED_OCR_CHARS).trim()
            if (ocr.isEmpty()) PinnedContext(null, null)
            else PinnedContext(
                image = null,
                systemSuffix = "Pinned note \"${note.title.ifBlank { "Untitled" }}\" (OCR):\n$ocr",
            )
        }
    }

    /**
     * Decode a `data:image/...;base64,...` URI back into a cache file and
     * return its file Uri, so a re-attached image (from editing a multimodal
     * message) flows through the same [encodeImageToBase64] path as a freshly
     * picked photo. Returns null on any malformed/unreadable input.
     */
    private fun dataUriToCacheFile(dataUri: String): Uri? {
        return try {
            val comma = dataUri.indexOf(',')
            if (comma < 0 || !dataUri.startsWith("data:")) return null
            val bytes = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT)
            if (bytes.isEmpty()) return null
            val dir = File(appContext.cacheDir, EDIT_IMAGE_CACHE_DIR).apply { mkdirs() }
            val file = File(dir, "edit-${UUID.randomUUID()}.jpg")
            file.outputStream().use { it.write(bytes) }
            Uri.fromFile(file)
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeImageToBase64(uri: Uri): ImageAttachment? {
        return try {
            val inputStream = appContext.contentResolver.openInputStream(uri) ?: return null
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (originalBitmap == null) return null

            // Scale down to max 1024px on longest side
            val maxDim = 1024
            val scaledBitmap = if (originalBitmap.width > maxDim || originalBitmap.height > maxDim) {
                val scale = maxDim.toFloat() / maxOf(originalBitmap.width, originalBitmap.height)
                val newWidth = (originalBitmap.width * scale).toInt()
                val newHeight = (originalBitmap.height * scale).toInt()
                Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true).also {
                    if (it !== originalBitmap) originalBitmap.recycle()
                }
            } else {
                originalBitmap
            }

            // Compress to JPEG quality 80
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            val bytes = outputStream.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val dataUri = "data:image/jpeg;base64,$base64"

            val attachment = ImageAttachment(
                dataUri = dataUri,
                width = scaledBitmap.width,
                height = scaledBitmap.height
            )
            scaledBitmap.recycle()
            attachment
        } catch (e: Exception) {
            null
        }
    }

    private fun generateAiTitle(chat: Chat, userMessage: String, assistantMessage: String) {
        viewModelScope.launch {
            try {
                if (!repository.isAutoGenerateTitlesEnabled()) return@launch
                val title = repository.generateTitle(chat.model, userMessage, assistantMessage)
                if (!title.isNullOrBlank()) {
                    // Re-fetch the latest chat state to avoid overwriting other updates
                    val currentChat = _uiState.value.chat ?: return@launch
                    repository.updateChat(currentChat.copy(title = title.take(60)))
                }
            } catch (_: Exception) {
                // Silently fail - placeholder title remains
            }
        }
    }

    private fun estimateTokens(text: String): Int {
        return (text.length / 4.0).toInt()
    }

    private fun estimateCost(model: String, promptTokens: Int, completionTokens: Int): Double {
        return ModelPricing.forModel(model).estimateCost(promptTokens, completionTokens)
    }

    fun toggleTools() {
        _uiState.update { it.copy(toolsEnabled = !it.toolsEnabled) }
    }

    // ── Pin note as context (sub-phase 4.4) ──────────────────────────────

    /** Show the note picker sheet. No-op if no chat is loaded yet. */
    fun openPinNotePicker() {
        if (_uiState.value.chat == null) return
        _uiState.update { it.copy(showPinNotePicker = true) }
    }

    fun dismissPinNotePicker() {
        _uiState.update { it.copy(showPinNotePicker = false) }
    }

    /** Pin [noteId] to the active chat and close the picker. */
    fun pinNote(noteId: String) {
        viewModelScope.launch {
            repository.setPinnedNote(chatId, noteId)
            _uiState.update { it.copy(showPinNotePicker = false) }
        }
    }

    /**
     * Drop the chat's pin. Also drops the cached render so a future re-pin
     * starts from a clean rasterizer pass — cheap insurance against stale
     * bytes outliving the pin in an unrelated chat.
     */
    fun unpinNote() {
        val current = pinnedNote.value?.id
        viewModelScope.launch {
            repository.setPinnedNote(chatId, null)
            if (current != null) pinnedNoteCache.invalidate(current)
        }
    }

    fun addCustomModel(model: String) {
        viewModelScope.launch { preferencesManager.addCustomModel("Custom", model) }
    }

    fun removeCustomModel(model: String) {
        viewModelScope.launch {
            // Try to remove from all providers
            val modelsMap = _customModels.value
            preferencesManager.removeCustomModel("Custom", model)
            preferencesManager.removeCustomModel("OpenAI", model)
            preferencesManager.removeCustomModel("Anthropic", model)
            preferencesManager.removeCustomModel("Google", model)
        }
    }

    companion object {
        const val MAX_MESSAGE_LENGTH = 100_000
        const val MAX_TOOL_ROUNDS = 10
        private const val SKETCH_CACHE_DIR = "chat-sketches"
        private const val EDIT_IMAGE_CACHE_DIR = "chat-edit-images"

        /**
         * Cap on OCR text injected into the system prompt for non-vision
         * pinned-note attachments (sub-phase 4.4). 2000 chars — enough to
         * cover a typical multi-paragraph note while staying well below
         * common context budgets so the user's actual prompt + history
         * isn't crowded out by the pin.
         */
        private const val PINNED_OCR_CHARS = 2_000
    }
}
