package com.aichat.sandbox.data.remote

import android.util.Log
import com.aichat.sandbox.BuildConfig
import com.aichat.sandbox.data.model.*
import com.google.gson.Gson
import com.google.gson.JsonObject
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
 * Adapter for OpenAI's `/v1/chat/completions` schema. Also serves as
 * the fallback adapter for any baseUrl no other adapter claims —
 * compatible proxies (LiteLLM, OpenRouter, etc.) typically expose the
 * same schema.
 */
@Singleton
class OpenAiAdapter @Inject constructor() : ProviderAdapter {
    private val gson = Gson()
    private val apiCache = mutableMapOf<String, OpenAiApi>()
    private val retryPolicy = RetryPolicy()

    override fun matches(baseUrl: String): Boolean = true // fallback

    private fun buildApi(baseUrl: String, apiKey: String): OpenAiApi {
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
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Content-Type", "application/json")
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
                .create(OpenAiApi::class.java)
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
            val apiMessages = buildApiMessages(chat, messages)
            val request = buildChatRequest(
                model = chat.model,
                messages = apiMessages,
                temperature = chat.temperature,
                topP = chat.topP,
                maxTokens = chat.maxTokens,
                presencePenalty = chat.presencePenalty,
                frequencyPenalty = chat.frequencyPenalty,
                stream = false,
                tools = tools,
            )
            val response = retryWithBackoff(
                policy = retryPolicy,
                onRetryAttempt = onRetryAttempt,
            ) { api.createChatCompletion(request) }
            if (response.isSuccessful) {
                response.body()?.let { ApiResult.Success(it) }
                    ?: ApiResult.Error("Empty response body")
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMsg = try {
                    gson.fromJson(errorBody, ApiErrorResponse::class.java).error.message
                        ?: "Unknown error"
                } catch (e: Exception) {
                    errorBody ?: "HTTP ${response.code()}"
                }
                ApiResult.Error(errorMsg)
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
            val apiMessages = buildApiMessages(
                chat = chat,
                messages = messages,
                extraImageOnLastUserTurn = extraImageOnLastUserTurn,
                extraSystemSuffix = extraSystemSuffix,
            )
            val request = buildChatRequest(
                model = chat.model,
                messages = apiMessages,
                temperature = chat.temperature,
                topP = chat.topP,
                maxTokens = chat.maxTokens,
                presencePenalty = chat.presencePenalty,
                frequencyPenalty = chat.frequencyPenalty,
                stream = true,
                tools = tools,
            )
            val response = retryWithBackoff(
                policy = retryPolicy,
                onRetryAttempt = onRetryAttempt,
            ) { api.createChatCompletionStream(request) }
            if (response.isSuccessful) {
                val reader = response.body()?.byteStream()?.bufferedReader()
                    ?: throw Exception("Empty response body")
                processStream(reader, this)
            } else {
                val errorBody = response.errorBody()?.string()
                val errorMsg = try {
                    gson.fromJson(errorBody, ApiErrorResponse::class.java).error.message
                        ?: "Unknown error"
                } catch (e: Exception) {
                    errorBody ?: "HTTP ${response.code()}"
                }
                emit(StreamEvent.Error(errorMsg))
            }
        } catch (e: Exception) {
            emit(StreamEvent.Error(e.message ?: "Network error"))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun processStream(
        reader: BufferedReader,
        collector: kotlinx.coroutines.flow.FlowCollector<StreamEvent>,
    ) {
        reader.use { r ->
            var line: String?
            val toolCallAccumulator = mutableMapOf<Int, ToolCallAccumulator>()
            while (r.readLine().also { line = it } != null) {
                currentCoroutineContext().ensureActive()
                val l = line ?: continue
                if (l.startsWith("data: ")) {
                    val data = l.removePrefix("data: ").trim()
                    if (data == "[DONE]") {
                        val toolCalls = buildAccumulatedToolCalls(toolCallAccumulator)
                        collector.emit(StreamEvent.Complete(null, toolCalls))
                        return
                    }
                    try {
                        val jsonObj = com.google.gson.JsonParser.parseString(data).asJsonObject
                        val choices = jsonObj.getAsJsonArray("choices")
                        val delta = choices?.takeIf { it.size() > 0 }
                            ?.get(0)?.asJsonObject?.getAsJsonObject("delta")

                        val content = delta?.get("content")?.takeIf { !it.isJsonNull }?.asString
                        if (content != null) {
                            collector.emit(StreamEvent.Delta(content))
                        }

                        val toolCallsArr = delta?.getAsJsonArray("tool_calls")
                        if (toolCallsArr != null) {
                            for (tc in toolCallsArr) {
                                val tcObj = tc?.asJsonObject ?: continue
                                val index = tcObj.intOrNull("index")
                                    ?: toolCallAccumulator.size
                                val acc = toolCallAccumulator.getOrPut(index) { ToolCallAccumulator() }
                                tcObj.stringOrNull("id")?.let { acc.id = it }
                                tcObj.stringOrNull("type")?.let { acc.type = it }
                                val funcObj = tcObj.getAsJsonObject("function")
                                if (funcObj != null) {
                                    funcObj.stringOrNull("name")?.let { acc.functionName = it }
                                    funcObj.stringOrNull("arguments")?.let { acc.arguments.append(it) }
                                }
                            }
                        }

                        val usage = jsonObj.getAsJsonObject("usage")
                        if (usage != null) {
                            val parsedUsage = gson.fromJson(usage, Usage::class.java)
                            val toolCalls = buildAccumulatedToolCalls(toolCallAccumulator)
                            collector.emit(StreamEvent.Complete(parsedUsage, toolCalls))
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        Log.w("OpenAiAdapter", "Skipping malformed stream chunk: ${data.take(100)}", t)
                    }
                }
            }
            val toolCalls = buildAccumulatedToolCalls(toolCallAccumulator)
            collector.emit(StreamEvent.Complete(null, toolCalls))
        }
    }

    private data class ToolCallAccumulator(
        var id: String = "",
        var type: String = "function",
        var functionName: String = "",
        var arguments: StringBuilder = StringBuilder(),
    )

    private fun buildAccumulatedToolCalls(
        accumulator: Map<Int, ToolCallAccumulator>,
    ): List<ToolCall>? {
        if (accumulator.isEmpty()) return null
        return accumulator.entries.sortedBy { it.key }.map { (_, acc) ->
            ToolCall(
                id = acc.id,
                type = acc.type,
                function = FunctionCall(
                    name = acc.functionName,
                    arguments = acc.arguments.toString(),
                ),
            )
        }
    }

    override suspend fun generateTitle(
        baseUrl: String,
        apiKey: String,
        model: String,
        userMessage: String,
        assistantMessage: String,
    ): String? {
        return try {
            val api = buildApi(baseUrl, apiKey)
            val messages = listOf(
                ApiMessage(
                    role = "system",
                    content = "Generate a concise 3-6 word title for this conversation. Return ONLY the title, no quotes, no punctuation at the end.",
                ),
                ApiMessage(role = "user", content = userMessage),
                ApiMessage(role = "assistant", content = assistantMessage.take(500)),
            )
            val request = buildChatRequest(
                model = model,
                messages = messages,
                temperature = 0.7f,
                topP = null,
                // Reasoning models spend part of this budget on hidden
                // reasoning tokens, so a 20-token cap can yield an empty
                // title. 256 leaves headroom for a short title either way.
                maxTokens = 256,
                presencePenalty = null,
                frequencyPenalty = null,
                stream = false,
            )
            val response = api.createChatCompletion(request)
            if (response.isSuccessful) {
                (response.body()?.choices?.firstOrNull()?.message?.content as? String)?.trim()
            } else null
        } catch (e: Exception) {
            Log.w("OpenAiAdapter", "Failed to generate title", e)
            null
        }
    }

    /**
     * Builds a [ChatCompletionRequest] shaped for the target model. GPT-5 /
     * o-series reasoning models reject `max_tokens` (need
     * `max_completion_tokens`) and reject custom sampling params
     * (temperature / top_p / penalties), so those are dropped or remapped
     * based on [ModelCapabilities]. Older models and OpenAI-compatible
     * proxies keep the legacy `max_tokens` + full sampling params.
     */
    private fun buildChatRequest(
        model: String,
        messages: List<ApiMessage>,
        temperature: Float?,
        topP: Float?,
        maxTokens: Int?,
        presencePenalty: Float?,
        frequencyPenalty: Float?,
        stream: Boolean,
        tools: List<ToolDefinition>? = null,
    ): ChatCompletionRequest {
        val caps = ModelCapabilities.of(model)
        val sampling = caps.supportsSamplingParams
        // GPT-5 caps total output (incl. reasoning tokens) at 128k; clamp to
        // avoid a "too large" follow-on error, mirroring the Anthropic adapter.
        val budget = maxTokens?.coerceIn(1, 128_000)
        return ChatCompletionRequest(
            model = model,
            messages = messages,
            temperature = if (sampling) temperature else null,
            topP = if (sampling) topP else null,
            maxTokens = if (caps.usesMaxCompletionTokens) null else budget,
            maxCompletionTokens = if (caps.usesMaxCompletionTokens) budget else null,
            presencePenalty = if (sampling) presencePenalty else null,
            frequencyPenalty = if (sampling) frequencyPenalty else null,
            stream = stream,
            tools = tools?.ifEmpty { null },
            toolChoice = if (!tools.isNullOrEmpty()) "auto" else null,
        )
    }

    private fun buildApiMessages(
        chat: Chat,
        messages: List<Message>,
        extraImageOnLastUserTurn: ByteArray? = null,
        extraSystemSuffix: String? = null,
    ): List<ApiMessage> {
        val apiMessages = mutableListOf<ApiMessage>()
        val systemBase = chat.systemMessage.takeIf { it.isNotBlank() }
        val systemSuffix = extraSystemSuffix?.takeIf { it.isNotBlank() }
        if (systemBase != null || systemSuffix != null) {
            val combined = when {
                systemBase != null && systemSuffix != null -> "$systemBase\n\n$systemSuffix"
                systemBase != null -> systemBase
                else -> systemSuffix!!
            }
            apiMessages.add(ApiMessage(role = "system", content = combined))
        }
        val lastUserIndex = if (extraImageOnLastUserTurn != null) {
            messages.indexOfLast { it.role == "user" }
        } else -1
        val extraImageDataUri: String? = if (lastUserIndex >= 0 && extraImageOnLastUserTurn != null) {
            "data:image/png;base64," +
                android.util.Base64.encodeToString(extraImageOnLastUserTurn, android.util.Base64.NO_WRAP)
        } else null
        messages.forEachIndexed { index, msg ->
            val attachExtraImage = index == lastUserIndex && extraImageDataUri != null
            when {
                msg.role == "tool" && msg.metadata != null -> {
                    try {
                        val toolMeta = gson.fromJson(msg.metadata, ToolCallMetadata::class.java)
                        apiMessages.add(ApiMessage(
                            role = "tool",
                            content = msg.content,
                            toolCallId = toolMeta.toolCallId,
                            name = toolMeta.toolName,
                        ))
                    } catch (e: Exception) {
                        Log.w("OpenAiAdapter", "Failed to parse tool metadata", e)
                        apiMessages.add(ApiMessage(role = msg.role, content = msg.content))
                    }
                }
                msg.role == "assistant" && msg.contentType == "tool_call" && msg.metadata != null -> {
                    try {
                        val toolMeta = gson.fromJson(msg.metadata, ToolCallMetadata::class.java)
                        apiMessages.add(ApiMessage(
                            role = "assistant",
                            content = msg.content.ifBlank { null },
                            toolCalls = toolMeta.toolCalls,
                        ))
                    } catch (e: Exception) {
                        Log.w("OpenAiAdapter", "Failed to parse tool call metadata", e)
                        apiMessages.add(ApiMessage(role = msg.role, content = msg.content))
                    }
                }
                msg.contentType == "multimodal" && msg.metadata != null -> {
                    val contentParts = mutableListOf<Any>()
                    if (msg.content.isNotBlank()) {
                        contentParts.add(TextContentPart(text = msg.content))
                    }
                    try {
                        val imageList = gson.fromJson(msg.metadata, ImageMetadata::class.java)
                        imageList?.images?.forEach { imageData ->
                            contentParts.add(
                                ImageContentPart(imageUrl = ImageUrl(url = imageData.dataUri))
                            )
                        }
                    } catch (e: Exception) {
                        Log.w("OpenAiAdapter", "Failed to parse image metadata", e)
                    }
                    if (attachExtraImage && extraImageDataUri != null) {
                        contentParts.add(
                            ImageContentPart(imageUrl = ImageUrl(url = extraImageDataUri))
                        )
                    }
                    apiMessages.add(ApiMessage(role = msg.role, content = contentParts))
                }
                attachExtraImage && extraImageDataUri != null -> {
                    val contentParts = mutableListOf<Any>()
                    if (msg.content.isNotBlank()) {
                        contentParts.add(TextContentPart(text = msg.content))
                    }
                    contentParts.add(
                        ImageContentPart(imageUrl = ImageUrl(url = extraImageDataUri))
                    )
                    apiMessages.add(ApiMessage(role = msg.role, content = contentParts))
                }
                else -> {
                    apiMessages.add(ApiMessage(role = msg.role, content = msg.content))
                }
            }
        }
        return apiMessages
    }
}

internal fun JsonObject.intOrNull(key: String): Int? =
    get(key)?.takeIf { !it.isJsonNull }?.runCatching { asInt }?.getOrNull()

internal fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull }?.runCatching { asString }?.getOrNull()
