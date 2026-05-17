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
import com.aichat.sandbox.data.notes.PendingDraftStore
import com.aichat.sandbox.data.remote.ApiResult
import com.aichat.sandbox.data.remote.StreamEvent
import com.aichat.sandbox.data.repository.ChatRepository
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
    val draftText: String? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ChatRepository,
    private val toolRegistry: ToolRegistry,
    private val preferencesManager: PreferencesManager,
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

    init {
        viewModelScope.launch {
            repository.getChatById(chatId).collect { chat ->
                _uiState.update { it.copy(chat = chat) }
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

            _uiState.update { it.copy(isLoading = true, error = null, streamingContent = "", retryAttempt = 0) }

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
            repository.sendMessageStream(chat, initialMessages, onRetry, tools).collect { event ->
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

    fun startEditing(message: Message) {
        _uiState.update { it.copy(editingMessageId = message.id, editingContent = message.content) }
    }

    fun cancelEditing() {
        _uiState.update { it.copy(editingMessageId = null, editingContent = null) }
    }

    fun submitEdit(newContent: String) {
        val chat = _uiState.value.chat ?: return
        val editingId = _uiState.value.editingMessageId ?: return
        if (newContent.isBlank()) return

        val editedMessage = _uiState.value.messages.find { it.id == editingId } ?: return

        viewModelScope.launch {
            // Delete all messages at or after the edited message's timestamp
            // (this removes the old version and any subsequent assistant replies)
            repository.deleteMessagesFrom(chatId, editedMessage.createdAt)

            // Clear editing state
            _uiState.update { it.copy(editingMessageId = null, editingContent = null) }

            // Send the edited content as a new message (reuses existing sendMessage flow)
            sendMessage(newContent.trim())
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
    }
}
