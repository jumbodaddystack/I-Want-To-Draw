package com.aichat.sandbox.data.model

import com.google.gson.annotations.SerializedName

/** Message payload for OpenAI-compatible chat completion requests. */
data class ApiMessage(
    val role: String,
    val content: String,
)

/** Chat completion request with legacy and reasoning-token budget fields. */
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val temperature: Float? = null,
    @SerializedName("top_p") val topP: Float? = null,
    @SerializedName("max_tokens") val maxTokens: Int? = null,
    @SerializedName("max_completion_tokens") val maxCompletionTokens: Int? = null,
)
