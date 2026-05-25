package com.aichat.sandbox.data.repository

import com.aichat.sandbox.data.local.ChatDao
import com.aichat.sandbox.data.local.PreferencesManager
import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.remote.ApiClient
import com.aichat.sandbox.data.remote.ApiResult
import com.aichat.sandbox.data.remote.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val chatDao: ChatDao,
    private val apiClient: ApiClient,
    private val preferencesManager: PreferencesManager
) {
    fun getAllChats(): Flow<List<Chat>> = chatDao.getAllChats()

    fun getChatById(chatId: String): Flow<Chat?> = chatDao.getChatByIdFlow(chatId)

    fun getMessagesForChat(chatId: String): Flow<List<Message>> =
        chatDao.getMessagesForChat(chatId)

    suspend fun createChat(): Chat {
        val model = preferencesManager.defaultModel.first()
        val temperature = preferencesManager.defaultTemperature.first()
        val topP = preferencesManager.defaultTopP.first()
        val maxTokens = preferencesManager.defaultMaxTokens.first()
        val presencePenalty = preferencesManager.defaultPresencePenalty.first()
        val frequencyPenalty = preferencesManager.defaultFrequencyPenalty.first()

        val chat = Chat(
            model = model,
            temperature = temperature,
            topP = topP,
            maxTokens = maxTokens,
            presencePenalty = presencePenalty,
            frequencyPenalty = frequencyPenalty
        )
        chatDao.insertChat(chat)
        return chat
    }

    suspend fun updateChat(chat: Chat) {
        chatDao.updateChat(chat.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteChat(chatId: String) {
        chatDao.deleteChatById(chatId)
    }

    /**
     * Pin / unpin a note for [chatId] (sub-phase 4.4). `null` clears the pin.
     * Doesn't bump `updatedAt` because pinning is per-chat metadata, not a
     * conversation edit — leaving the timestamp alone keeps the chat list
     * sort stable when the user fiddles with pins.
     */
    suspend fun setPinnedNote(chatId: String, noteId: String?) {
        chatDao.updatePinnedNoteId(chatId, noteId)
    }

    suspend fun insertMessage(message: Message) {
        chatDao.insertMessage(message)
    }

    suspend fun deleteMessage(message: Message) {
        chatDao.deleteMessage(message)
    }

    suspend fun clearChatHistory(chatId: String) {
        chatDao.clearChatHistory(chatId)
    }

    // Message editing (1.2)
    suspend fun updateMessageContent(messageId: String, content: String) {
        chatDao.updateMessageContent(messageId, content)
    }

    suspend fun deleteMessagesFrom(chatId: String, timestamp: Long) {
        chatDao.deleteMessagesFrom(chatId, timestamp)
    }

    // Auto-title generation (1.6)
    suspend fun generateTitle(
        model: String,
        userMessage: String,
        assistantMessage: String
    ): String? {
        val creds = preferencesManager.credentialsFor(model)
        return apiClient.generateTitle(creds.baseUrl, creds.apiKey, model, userMessage, assistantMessage)
    }

    suspend fun isAutoGenerateTitlesEnabled(): Boolean =
        preferencesManager.autoGenerateTitles.first()

    // Conversation search (1.5)
    suspend fun searchMessages(query: String): List<Message> =
        chatDao.searchMessages(query)

    suspend fun searchChats(query: String): List<Chat> =
        chatDao.searchChats(query)

    // API calls with retry support (1.4)
    suspend fun sendMessage(
        chat: Chat,
        messages: List<Message>,
        onRetryAttempt: ((Int) -> Unit)? = null,
        tools: List<com.aichat.sandbox.data.model.ToolDefinition>? = null
    ): ApiResult<com.aichat.sandbox.data.model.ChatCompletionResponse> {
        val creds = preferencesManager.credentialsFor(chat.model)
        return apiClient.sendMessage(creds.baseUrl, creds.apiKey, chat, messages, onRetryAttempt, tools)
    }

    fun sendMessageStream(
        chat: Chat,
        messages: List<Message>,
        onRetryAttempt: ((Int) -> Unit)? = null,
        tools: List<com.aichat.sandbox.data.model.ToolDefinition>? = null,
        // Sub-phase 4.4 pinned-note hooks. Wired through verbatim so the
        // chat layer's vision-vs-OCR decision (made in `ChatViewModel`) is
        // the only place that needs to know about pinning.
        extraImageOnLastUserTurn: ByteArray? = null,
        extraSystemSuffix: String? = null,
    ): Flow<StreamEvent> {
        return kotlinx.coroutines.flow.flow {
            val creds = preferencesManager.credentialsFor(chat.model)
            apiClient.sendMessageStream(
                baseUrl = creds.baseUrl,
                apiKey = creds.apiKey,
                chat = chat,
                messages = messages,
                onRetryAttempt = onRetryAttempt,
                tools = tools,
                extraImageOnLastUserTurn = extraImageOnLastUserTurn,
                extraSystemSuffix = extraSystemSuffix,
            ).collect { emit(it) }
        }
    }
}
