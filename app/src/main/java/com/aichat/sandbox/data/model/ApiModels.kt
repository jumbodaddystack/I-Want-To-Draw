package com.aichat.sandbox.data.model

import com.google.gson.annotations.SerializedName

// OpenAI Vision API content parts
data class TextContentPart(
    val type: String = "text",
    val text: String
)

data class ImageUrl(
    val url: String
)

data class ImageContentPart(
    val type: String = "image_url",
    @SerializedName("image_url")
    val imageUrl: ImageUrl
)

// OpenAI API request/response models
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val temperature: Float? = null,
    @SerializedName("top_p")
    val topP: Float? = null,
    @SerializedName("max_tokens")
    val maxTokens: Int? = null,
    @SerializedName("presence_penalty")
    val presencePenalty: Float? = null,
    @SerializedName("frequency_penalty")
    val frequencyPenalty: Float? = null,
    val stream: Boolean = false,
    val tools: List<ToolDefinition>? = null,
    @SerializedName("tool_choice")
    val toolChoice: Any? = null // "auto", "none", or specific tool object
)

data class ApiMessage(
    val role: String,
    // Can be String (text-only) or List<Any> (vision: TextContentPart + ImageContentPart)
    val content: Any? = null,
    @SerializedName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
    @SerializedName("tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null // function name for tool result messages
)

data class ChatCompletionResponse(
    val id: String?,
    val `object`: String?,
    val created: Long?,
    val model: String?,
    val choices: List<Choice>?,
    val usage: Usage?,
    val error: ApiError?
)

data class Choice(
    val index: Int?,
    val message: ApiMessage?,
    val delta: ApiMessage?,
    @SerializedName("finish_reason")
    val finishReason: String?
)

data class Usage(
    @SerializedName("prompt_tokens")
    val promptTokens: Int = 0,
    @SerializedName("completion_tokens")
    val completionTokens: Int = 0,
    @SerializedName("total_tokens")
    val totalTokens: Int = 0
)

data class ApiError(
    val message: String?,
    val type: String?,
    val code: String?
)

data class ApiErrorResponse(
    val error: ApiError
)

// Metadata stored in Message.metadata for multi-modal messages
data class ImageAttachment(
    val dataUri: String, // base64 data URI: "data:image/jpeg;base64,..."
    val width: Int = 0,
    val height: Int = 0
)

data class ImageMetadata(
    val images: List<ImageAttachment> = emptyList()
)
