package com.aichat.sandbox.data.remote

import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.ChatCompletionResponse
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.model.ToolDefinition
import kotlinx.coroutines.flow.Flow

/**
 * Provider-native HTTP adapter. Each implementation translates the
 * internal Chat/Message model into the provider's wire schema and parses
 * the streaming response back into [StreamEvent]s, so the rest of the
 * app stays provider-agnostic.
 *
 * [ApiClient] is now a thin router that picks an adapter by base URL —
 * see [matches]. Public [ApiResult] / [StreamEvent] types remain in
 * [ApiClient] to keep call-site imports stable.
 */
interface ProviderAdapter {

    /**
     * Whether this adapter handles requests against [baseUrl]. The
     * router asks each adapter in turn; first match wins, OpenAI is the
     * final fallback.
     */
    fun matches(baseUrl: String): Boolean

    suspend fun sendMessage(
        baseUrl: String,
        apiKey: String,
        chat: Chat,
        messages: List<Message>,
        onRetryAttempt: ((Int) -> Unit)? = null,
        tools: List<ToolDefinition>? = null,
    ): ApiResult<ChatCompletionResponse>

    fun sendMessageStream(
        baseUrl: String,
        apiKey: String,
        chat: Chat,
        messages: List<Message>,
        onRetryAttempt: ((Int) -> Unit)? = null,
        tools: List<ToolDefinition>? = null,
        extraImageOnLastUserTurn: ByteArray? = null,
        extraSystemSuffix: String? = null,
    ): Flow<StreamEvent>

    suspend fun generateTitle(
        baseUrl: String,
        apiKey: String,
        model: String,
        userMessage: String,
        assistantMessage: String,
    ): String?
}
