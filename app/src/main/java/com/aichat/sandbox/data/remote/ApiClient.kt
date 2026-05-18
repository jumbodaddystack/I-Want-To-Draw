package com.aichat.sandbox.data.remote

import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.ChatCompletionResponse
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.model.ToolDefinition
import com.aichat.sandbox.data.model.Usage
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String) : ApiResult<Nothing>()
    data object Loading : ApiResult<Nothing>()
}

sealed class StreamEvent {
    data class Delta(val content: String) : StreamEvent()
    data class ToolCallDelta(val toolCalls: List<com.aichat.sandbox.data.model.ToolCall>) : StreamEvent()
    data class Complete(val usage: Usage?, val toolCalls: List<com.aichat.sandbox.data.model.ToolCall>? = null) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}

/**
 * Routes each request to the [ProviderAdapter] that claims its
 * baseUrl. OpenAI is the final fallback — anything that doesn't match
 * Anthropic or Gemini goes through the OpenAI chat-completions schema,
 * which covers OpenAI itself plus any OpenAI-compatible proxy
 * (OpenRouter, LiteLLM, local llama.cpp servers, etc.).
 *
 * Implements [ChatStreamer] so existing callers (NoteAiService, the
 * chat pipeline) keep their imports.
 */
@Singleton
class ApiClient @Inject constructor(
    private val openAi: OpenAiAdapter,
    private val anthropic: AnthropicAdapter,
    private val gemini: GeminiAdapter,
) : ChatStreamer {

    // OpenAI must be checked last because its `matches` returns true
    // unconditionally (it's the fallback). The first specific match
    // wins.
    private val orderedAdapters: List<ProviderAdapter> = listOf(anthropic, gemini, openAi)

    private fun pick(baseUrl: String): ProviderAdapter =
        orderedAdapters.first { it.matches(baseUrl) }

    suspend fun sendMessage(
        baseUrl: String,
        apiKey: String,
        chat: Chat,
        messages: List<Message>,
        onRetryAttempt: ((Int) -> Unit)? = null,
        tools: List<ToolDefinition>? = null,
    ): ApiResult<ChatCompletionResponse> =
        pick(baseUrl).sendMessage(baseUrl, apiKey, chat, messages, onRetryAttempt, tools)

    override fun sendMessageStream(
        baseUrl: String,
        apiKey: String,
        chat: Chat,
        messages: List<Message>,
        onRetryAttempt: ((Int) -> Unit)?,
        tools: List<ToolDefinition>?,
        extraImageOnLastUserTurn: ByteArray?,
        extraSystemSuffix: String?,
    ): Flow<StreamEvent> = pick(baseUrl).sendMessageStream(
        baseUrl, apiKey, chat, messages, onRetryAttempt, tools,
        extraImageOnLastUserTurn, extraSystemSuffix,
    )

    suspend fun generateTitle(
        baseUrl: String,
        apiKey: String,
        model: String,
        userMessage: String,
        assistantMessage: String,
    ): String? = pick(baseUrl).generateTitle(baseUrl, apiKey, model, userMessage, assistantMessage)
}
