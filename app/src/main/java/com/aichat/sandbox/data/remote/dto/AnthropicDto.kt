package com.aichat.sandbox.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * Anthropic Messages API DTOs. Schema spec:
 * https://docs.anthropic.com/en/api/messages
 *
 * Notable shape differences vs OpenAI:
 * - `system` is a top-level string, NOT a message in the `messages`
 *   array.
 * - `content` is always a list of typed blocks (`text`, `image`,
 *   `tool_use`, `tool_result`) — even plain text is wrapped.
 * - Tool results are sent as a `user` turn containing
 *   `[{type: tool_result, …}]` blocks.
 * - `max_tokens` is **required**.
 */
data class AnthropicMessageRequest(
    val model: String,
    @SerializedName("max_tokens")
    val maxTokens: Int,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    val temperature: Float? = null,
    @SerializedName("top_p")
    val topP: Float? = null,
    val tools: List<AnthropicToolDefinition>? = null,
    val stream: Boolean = false,
)

data class AnthropicMessage(
    val role: String, // "user" | "assistant"
    val content: List<AnthropicContentBlock>,
)

/**
 * Polymorphic content block. Serialized with a `type` discriminator;
 * only the fields appropriate to that type should be populated, the
 * rest left null so Gson omits them.
 */
data class AnthropicContentBlock(
    val type: String, // "text" | "image" | "tool_use" | "tool_result"
    val text: String? = null,
    val source: AnthropicImageSource? = null,
    // tool_use
    val id: String? = null,
    val name: String? = null,
    val input: Map<String, Any?>? = null,
    // tool_result
    @SerializedName("tool_use_id")
    val toolUseId: String? = null,
    val content: Any? = null, // String or List<AnthropicContentBlock>
)

data class AnthropicImageSource(
    val type: String = "base64",
    @SerializedName("media_type")
    val mediaType: String,
    val data: String,
)

data class AnthropicToolDefinition(
    val name: String,
    val description: String,
    @SerializedName("input_schema")
    val inputSchema: Map<String, Any?>,
)

// --- Non-streaming response ---------------------------------------------

data class AnthropicMessageResponse(
    val id: String?,
    val type: String?,
    val role: String?,
    val model: String?,
    val content: List<AnthropicContentBlock>?,
    @SerializedName("stop_reason")
    val stopReason: String?,
    val usage: AnthropicUsage?,
)

data class AnthropicUsage(
    @SerializedName("input_tokens")
    val inputTokens: Int = 0,
    @SerializedName("output_tokens")
    val outputTokens: Int = 0,
)

// --- Streaming SSE payloads ---------------------------------------------
// Anthropic uses `event: <name>` framing with one JSON payload per event.
// We only need to parse a few fields; the rest are ignored.

data class AnthropicStreamMessageStart(
    val message: AnthropicMessageResponse?,
)

data class AnthropicStreamContentBlockStart(
    val index: Int = 0,
    @SerializedName("content_block")
    val contentBlock: AnthropicContentBlock?,
)

data class AnthropicStreamContentBlockDelta(
    val index: Int = 0,
    val delta: AnthropicStreamDelta?,
)

data class AnthropicStreamDelta(
    val type: String? = null, // "text_delta" | "input_json_delta"
    val text: String? = null,
    @SerializedName("partial_json")
    val partialJson: String? = null,
)

data class AnthropicStreamMessageDelta(
    val delta: AnthropicStreamMessageDeltaInner?,
    val usage: AnthropicUsage?,
)

data class AnthropicStreamMessageDeltaInner(
    @SerializedName("stop_reason")
    val stopReason: String? = null,
)

// Error envelope returned on 4xx/5xx (and as an `error` SSE event).
data class AnthropicErrorEnvelope(val error: AnthropicError?)
data class AnthropicError(val type: String?, val message: String?)
