package com.aichat.sandbox.data.remote

import android.util.Log
import com.aichat.sandbox.BuildConfig
import com.aichat.sandbox.data.model.*
import com.aichat.sandbox.data.remote.dto.*
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.BufferedReader
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native adapter for Anthropic's Messages API (`POST /v1/messages`).
 *
 * Wire-format translation:
 * - `system` ApiMessage → top-level `system` string (split out of the
 *   messages array).
 * - Each turn becomes `{role, content:[blocks]}` — even plain text is
 *   wrapped in `[{type:"text", text:…}]`.
 * - Image data URIs → `{type:"image", source:{type:"base64", media_type,
 *   data}}`.
 * - Assistant tool calls → `{type:"tool_use", id, name, input}`.
 * - Tool results → a `user` turn carrying `{type:"tool_result",
 *   tool_use_id, content}`.
 *
 * Streaming uses named SSE events (`message_start`,
 * `content_block_delta`, `message_delta`, `message_stop`). `text_delta`s
 * → [StreamEvent.Delta]; `input_json_delta`s are accumulated per
 * content block and emitted as `ToolCall`s on `message_stop`.
 */
@Singleton
class AnthropicAdapter @Inject constructor() : ProviderAdapter {
    private val gson = Gson()
    private val apiCache = mutableMapOf<String, AnthropicApi>()
    private val retryPolicy = RetryPolicy()

    override fun matches(baseUrl: String): Boolean =
        baseUrl.contains("api.anthropic.com", ignoreCase = true)

    private fun buildApi(baseUrl: String, apiKey: String): AnthropicApi {
        val cacheKey = "$baseUrl|$apiKey"
        return apiCache.getOrPut(cacheKey) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                        else HttpLoggingInterceptor.Level.NONE
            }
            val client = OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .addHeader("x-api-key", apiKey)
                        .addHeader("anthropic-version", ANTHROPIC_VERSION)
                        .addHeader("content-type", "application/json")
                        .build()
                    chain.proceed(request)
                }
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(AnthropicApi::class.java)
        }
    }

    override suspend fun sendMessage(
        baseUrl: String,
        apiKey: String,
        chat: Chat,
        messages: List<Message>,
        onRetryAttempt: ((Int) -> Unit)?,
        tools: List<ToolDefinition>?,
    ): ApiResult<ChatCompletionResponse> {
        return try {
            val api = buildApi(baseUrl, apiKey)
            val (system, anthropicMessages) = buildAnthropicMessages(chat, messages)
            val request = AnthropicMessageRequest(
                model = chat.model,
                // Anthropic caps `max_tokens` per model (claude-opus-4-7 currently
                // tops out around 64K). The app's default slider goes to 131072
                // for OpenAI-style requests; coerce here so a stray default
                // doesn't 400-out at the wire.
                maxTokens = chat.maxTokens.coerceIn(1, 64_000),
                messages = anthropicMessages,
                system = system,
                temperature = chat.temperature,
                topP = chat.topP,
                tools = tools?.takeIf { it.isNotEmpty() }?.map { it.toAnthropic() },
                stream = false,
            )
            val response = retryWithBackoff(
                policy = retryPolicy,
                onRetryAttempt = onRetryAttempt,
            ) { api.createMessage(request) }
            if (response.isSuccessful) {
                val body = response.body() ?: return ApiResult.Error("Empty response body")
                ApiResult.Success(body.toChatCompletionResponse())
            } else {
                ApiResult.Error(parseErrorBody(response.errorBody()?.string(), response.code()))
            }
        } catch (e: Exception) {
            ApiResult.Error(e.message ?: "Network error")
        }
    }

    override fun sendMessageStream(
        baseUrl: String,
        apiKey: String,
        chat: Chat,
        messages: List<Message>,
        onRetryAttempt: ((Int) -> Unit)?,
        tools: List<ToolDefinition>?,
        extraImageOnLastUserTurn: ByteArray?,
        extraSystemSuffix: String?,
    ): Flow<StreamEvent> = flow {
        try {
            val api = buildApi(baseUrl, apiKey)
            val (system, anthropicMessages) = buildAnthropicMessages(
                chat = chat,
                messages = messages,
                extraImageOnLastUserTurn = extraImageOnLastUserTurn,
                extraSystemSuffix = extraSystemSuffix,
            )
            val request = AnthropicMessageRequest(
                model = chat.model,
                // Anthropic caps `max_tokens` per model (claude-opus-4-7 currently
                // tops out around 64K). The app's default slider goes to 131072
                // for OpenAI-style requests; coerce here so a stray default
                // doesn't 400-out at the wire.
                maxTokens = chat.maxTokens.coerceIn(1, 64_000),
                messages = anthropicMessages,
                system = system,
                temperature = chat.temperature,
                topP = chat.topP,
                tools = tools?.takeIf { it.isNotEmpty() }?.map { it.toAnthropic() },
                stream = true,
            )
            val response = retryWithBackoff(
                policy = retryPolicy,
                onRetryAttempt = onRetryAttempt,
            ) { api.createMessageStream(request) }
            if (response.isSuccessful) {
                val reader = response.body()?.byteStream()?.bufferedReader()
                    ?: throw Exception("Empty response body")
                processStream(reader, this)
            } else {
                emit(StreamEvent.Error(parseErrorBody(response.errorBody()?.string(), response.code())))
            }
        } catch (e: Exception) {
            emit(StreamEvent.Error(e.message ?: "Network error"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Parse an Anthropic SSE stream. Frames look like:
     *
     *     event: content_block_delta
     *     data: {"type":"content_block_delta", ...}
     *
     * We key off `event:` and parse the matching DTO. State machine
     * lives in [StreamState]; tool-call input arrives as
     * `input_json_delta` fragments keyed by content-block index.
     */
    private suspend fun processStream(
        reader: BufferedReader,
        collector: kotlinx.coroutines.flow.FlowCollector<StreamEvent>,
    ) {
        reader.use { r ->
            val state = StreamState()
            var event: String? = null
            var line: String?
            while (r.readLine().also { line = it } != null) {
                currentCoroutineContext().ensureActive()
                val l = line ?: continue
                when {
                    l.startsWith("event: ") -> event = l.removePrefix("event: ").trim()
                    l.startsWith("data: ") -> {
                        val data = l.removePrefix("data: ").trim()
                        try {
                            handleEvent(event, data, state, collector)
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            Log.w("AnthropicAdapter", "Skipping malformed SSE chunk: ${data.take(100)}", t)
                        }
                    }
                    l.isBlank() -> event = null // event frames are blank-line separated
                }
            }
            // Stream ended without an explicit message_stop — flush whatever
            // we accumulated.
            collector.emit(StreamEvent.Complete(state.usage(), state.buildToolCalls()))
        }
    }

    private suspend fun handleEvent(
        event: String?,
        data: String,
        state: StreamState,
        collector: kotlinx.coroutines.flow.FlowCollector<StreamEvent>,
    ) {
        when (event) {
            "message_start" -> {
                val payload = gson.fromJson(data, AnthropicStreamMessageStart::class.java)
                payload?.message?.usage?.let { state.inputTokens = it.inputTokens }
            }
            "content_block_start" -> {
                val payload = gson.fromJson(data, AnthropicStreamContentBlockStart::class.java)
                val block = payload?.contentBlock ?: return
                if (block.type == "tool_use") {
                    state.toolBlocks[payload.index] = ToolBlockAccumulator(
                        id = block.id.orEmpty(),
                        name = block.name.orEmpty(),
                    )
                }
            }
            "content_block_delta" -> {
                val payload = gson.fromJson(data, AnthropicStreamContentBlockDelta::class.java)
                val delta = payload?.delta ?: return
                when (delta.type) {
                    "text_delta" -> {
                        delta.text?.let { collector.emit(StreamEvent.Delta(it)) }
                    }
                    "input_json_delta" -> {
                        val acc = state.toolBlocks[payload.index] ?: return
                        delta.partialJson?.let { acc.arguments.append(it) }
                    }
                }
            }
            "message_delta" -> {
                val payload = gson.fromJson(data, AnthropicStreamMessageDelta::class.java)
                payload?.usage?.outputTokens?.let { state.outputTokens = it }
            }
            "message_stop" -> {
                collector.emit(StreamEvent.Complete(state.usage(), state.buildToolCalls()))
            }
            "error" -> {
                val env = gson.fromJson(data, AnthropicErrorEnvelope::class.java)
                collector.emit(StreamEvent.Error(env?.error?.message ?: "Stream error"))
            }
            else -> { /* ping, etc. — ignore */ }
        }
    }

    private class StreamState {
        var inputTokens: Int = 0
        var outputTokens: Int = 0
        val toolBlocks: MutableMap<Int, ToolBlockAccumulator> = mutableMapOf()
        fun usage(): Usage = Usage(
            promptTokens = inputTokens,
            completionTokens = outputTokens,
            totalTokens = inputTokens + outputTokens,
        )
        fun buildToolCalls(): List<ToolCall>? {
            if (toolBlocks.isEmpty()) return null
            return toolBlocks.entries.sortedBy { it.key }.map { (_, acc) ->
                ToolCall(
                    id = acc.id,
                    type = "function",
                    function = FunctionCall(name = acc.name, arguments = acc.arguments.toString()),
                )
            }
        }
    }

    private data class ToolBlockAccumulator(
        var id: String,
        var name: String,
        var arguments: StringBuilder = StringBuilder(),
    )

    override suspend fun generateTitle(
        baseUrl: String,
        apiKey: String,
        model: String,
        userMessage: String,
        assistantMessage: String,
    ): String? {
        return try {
            val api = buildApi(baseUrl, apiKey)
            val request = AnthropicMessageRequest(
                model = model,
                maxTokens = 32,
                system = "Generate a concise 3-6 word title for this conversation. Return ONLY the title, no quotes, no punctuation at the end.",
                messages = listOf(
                    AnthropicMessage(
                        role = "user",
                        content = listOf(AnthropicContentBlock(
                            type = "text",
                            text = "USER: $userMessage\nASSISTANT: ${assistantMessage.take(500)}",
                        )),
                    ),
                ),
                temperature = 0.7f,
                stream = false,
            )
            val response = api.createMessage(request)
            if (!response.isSuccessful) return null
            response.body()?.content
                ?.firstOrNull { it.type == "text" }
                ?.text?.trim()
        } catch (e: Exception) {
            Log.w("AnthropicAdapter", "Failed to generate title", e)
            null
        }
    }

    private fun buildAnthropicMessages(
        chat: Chat,
        messages: List<Message>,
        extraImageOnLastUserTurn: ByteArray? = null,
        extraSystemSuffix: String? = null,
    ): Pair<String?, List<AnthropicMessage>> {
        val systemBase = chat.systemMessage.takeIf { it.isNotBlank() }
        val systemSuffix = extraSystemSuffix?.takeIf { it.isNotBlank() }
        val system = when {
            systemBase != null && systemSuffix != null -> "$systemBase\n\n$systemSuffix"
            systemBase != null -> systemBase
            systemSuffix != null -> systemSuffix
            else -> null
        }

        val lastUserIndex = if (extraImageOnLastUserTurn != null) {
            messages.indexOfLast { it.role == "user" }
        } else -1
        val extraImageBase64: String? =
            if (lastUserIndex >= 0 && extraImageOnLastUserTurn != null) {
                android.util.Base64.encodeToString(extraImageOnLastUserTurn, android.util.Base64.NO_WRAP)
            } else null

        val out = mutableListOf<AnthropicMessage>()
        messages.forEachIndexed { index, msg ->
            val attachExtraImage = index == lastUserIndex && extraImageBase64 != null
            val anth = translateMessage(msg, attachExtraImage, extraImageBase64) ?: return@forEachIndexed
            out.add(anth)
        }
        return system to coalesceConsecutiveRoles(out)
    }

    /**
     * Anthropic requires alternating user/assistant turns. If two
     * consecutive messages share a role, fold their content blocks
     * together — happens when, e.g., a tool result (user role) follows
     * a user prompt.
     */
    private fun coalesceConsecutiveRoles(messages: List<AnthropicMessage>): List<AnthropicMessage> {
        if (messages.isEmpty()) return messages
        val out = mutableListOf<AnthropicMessage>()
        for (m in messages) {
            val last = out.lastOrNull()
            if (last != null && last.role == m.role) {
                out[out.lastIndex] = last.copy(content = last.content + m.content)
            } else {
                out.add(m)
            }
        }
        return out
    }

    private fun translateMessage(
        msg: Message,
        attachExtraImage: Boolean,
        extraImageBase64: String?,
    ): AnthropicMessage? {
        return when {
            // Tool result (sent BACK to model) — Anthropic models these as
            // a user turn with a tool_result content block.
            msg.role == "tool" && msg.metadata != null -> {
                val toolMeta = parseToolMeta(msg.metadata) ?: return null
                AnthropicMessage(
                    role = "user",
                    content = listOf(
                        AnthropicContentBlock(
                            type = "tool_result",
                            toolUseId = toolMeta.toolCallId.orEmpty(),
                            content = msg.content,
                        ),
                    ),
                )
            }
            // Assistant tool call — emitted by the model in a previous
            // turn; reconstruct from persisted metadata.
            msg.role == "assistant" && msg.contentType == "tool_call" && msg.metadata != null -> {
                val toolMeta = parseToolMeta(msg.metadata) ?: return null
                val blocks = mutableListOf<AnthropicContentBlock>()
                if (msg.content.isNotBlank()) {
                    blocks.add(AnthropicContentBlock(type = "text", text = msg.content))
                }
                toolMeta.toolCalls?.forEach { tc ->
                    blocks.add(AnthropicContentBlock(
                        type = "tool_use",
                        id = tc.id,
                        name = tc.function.name,
                        input = parseJsonArgs(tc.function.arguments),
                    ))
                }
                AnthropicMessage(role = "assistant", content = blocks)
            }
            // Multimodal user message: text + one or more image blocks.
            msg.contentType == "multimodal" && msg.metadata != null -> {
                val blocks = mutableListOf<AnthropicContentBlock>()
                if (msg.content.isNotBlank()) {
                    blocks.add(AnthropicContentBlock(type = "text", text = msg.content))
                }
                runCatching {
                    gson.fromJson(msg.metadata, ImageMetadata::class.java)?.images?.forEach { img ->
                        decodeDataUri(img.dataUri)?.let { (media, b64) ->
                            blocks.add(AnthropicContentBlock(
                                type = "image",
                                source = AnthropicImageSource(mediaType = media, data = b64),
                            ))
                        }
                    }
                }
                if (attachExtraImage && extraImageBase64 != null) {
                    blocks.add(AnthropicContentBlock(
                        type = "image",
                        source = AnthropicImageSource(mediaType = "image/png", data = extraImageBase64),
                    ))
                }
                AnthropicMessage(role = msg.role, content = blocks)
            }
            // Plain text turn — possibly with a pinned-note image injected.
            else -> {
                val blocks = mutableListOf<AnthropicContentBlock>()
                if (msg.content.isNotBlank()) {
                    blocks.add(AnthropicContentBlock(type = "text", text = msg.content))
                }
                if (attachExtraImage && extraImageBase64 != null) {
                    blocks.add(AnthropicContentBlock(
                        type = "image",
                        source = AnthropicImageSource(mediaType = "image/png", data = extraImageBase64),
                    ))
                }
                if (blocks.isEmpty()) return null
                AnthropicMessage(role = msg.role, content = blocks)
            }
        }
    }

    private fun parseToolMeta(metadata: String): ToolCallMetadata? = try {
        gson.fromJson(metadata, ToolCallMetadata::class.java)
    } catch (e: Exception) {
        Log.w("AnthropicAdapter", "Failed to parse tool metadata", e)
        null
    }

    private fun parseJsonArgs(arguments: String): Map<String, Any?> {
        if (arguments.isBlank()) return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, Any?>>() {}.type
            gson.fromJson(arguments, type) ?: emptyMap()
        } catch (e: JsonSyntaxException) {
            emptyMap()
        }
    }

    /** Extract `(mediaType, base64Data)` from a data URI; returns null on malformed input. */
    private fun decodeDataUri(uri: String): Pair<String, String>? {
        // Expected: data:image/jpeg;base64,XXXX
        if (!uri.startsWith("data:")) return null
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val header = uri.substring(5, comma) // e.g. "image/jpeg;base64"
        val media = header.substringBefore(';').takeIf { it.isNotBlank() } ?: return null
        return media to uri.substring(comma + 1)
    }

    private fun parseErrorBody(body: String?, code: Int): String {
        if (body.isNullOrBlank()) return "HTTP $code"
        return try {
            gson.fromJson(body, AnthropicErrorEnvelope::class.java)?.error?.message ?: body
        } catch (e: Exception) {
            body
        }
    }

    /** Map Anthropic's response shape onto the existing OpenAI-shaped surface so callers don't branch. */
    private fun AnthropicMessageResponse.toChatCompletionResponse(): ChatCompletionResponse {
        val textBlocks = content?.filter { it.type == "text" }?.mapNotNull { it.text }.orEmpty()
        val toolUseBlocks = content?.filter { it.type == "tool_use" }.orEmpty()
        val toolCalls = toolUseBlocks.mapNotNull { block ->
            val name = block.name ?: return@mapNotNull null
            ToolCall(
                id = block.id.orEmpty(),
                type = "function",
                function = FunctionCall(name = name, arguments = gson.toJson(block.input ?: emptyMap<String, Any?>())),
            )
        }
        val message = ApiMessage(
            role = role ?: "assistant",
            content = textBlocks.joinToString(""),
            toolCalls = toolCalls.ifEmpty { null },
        )
        val u = usage ?: AnthropicUsage()
        return ChatCompletionResponse(
            id = id,
            `object` = "chat.completion",
            created = null,
            model = model,
            choices = listOf(Choice(
                index = 0,
                message = message,
                delta = null,
                finishReason = stopReason,
            )),
            usage = Usage(
                promptTokens = u.inputTokens,
                completionTokens = u.outputTokens,
                totalTokens = u.inputTokens + u.outputTokens,
            ),
            error = null,
        )
    }

    private fun ToolDefinition.toAnthropic(): AnthropicToolDefinition {
        return AnthropicToolDefinition(
            name = function.name,
            description = function.description,
            inputSchema = function.parameters ?: emptyMap(),
        )
    }

    companion object {
        private const val ANTHROPIC_VERSION = "2023-06-01"
    }
}
