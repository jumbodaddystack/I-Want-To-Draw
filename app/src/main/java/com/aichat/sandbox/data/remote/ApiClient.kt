package com.aichat.sandbox.data.remote

import android.util.Log
import com.aichat.sandbox.BuildConfig
import com.aichat.sandbox.data.model.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
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

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String) : ApiResult<Nothing>()
    data object Loading : ApiResult<Nothing>()
}

private fun JsonObject.intOrNull(key: String): Int? =
    get(key)?.takeIf { !it.isJsonNull }?.runCatching { asInt }?.getOrNull()

private fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { !it.isJsonNull }?.runCatching { asString }?.getOrNull()

sealed class StreamEvent {
    data class Delta(val content: String) : StreamEvent()
    data class ToolCallDelta(val toolCalls: List<com.aichat.sandbox.data.model.ToolCall>) : StreamEvent()
    data class Complete(val usage: Usage?, val toolCalls: List<com.aichat.sandbox.data.model.ToolCall>? = null) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}

@Singleton
class ApiClient @Inject constructor() : ChatStreamer {
    private val gson = Gson()
    private val apiCache = mutableMapOf<String, OpenAiApi>()
    private val retryPolicy = RetryPolicy()

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

    suspend fun sendMessage(
        baseUrl: String,
        apiKey: String,
        chat: Chat,
        messages: List<Message>,
        onRetryAttempt: ((Int) -> Unit)? = null,
        tools: List<com.aichat.sandbox.data.model.ToolDefinition>? = null
    ): ApiResult<ChatCompletionResponse> {
        return try {
            val api = buildApi(baseUrl, apiKey)
            val apiMessages = buildApiMessages(chat, messages)
            val request = ChatCompletionRequest(
                model = chat.model,
                messages = apiMessages,
                temperature = chat.temperature,
                topP = chat.topP,
                maxTokens = chat.maxTokens,
                presencePenalty = chat.presencePenalty,
                frequencyPenalty = chat.frequencyPenalty,
                stream = false,
                tools = tools?.ifEmpty { null },
                toolChoice = if (!tools.isNullOrEmpty()) "auto" else null
            )
            val response = retryWithBackoff(
                policy = retryPolicy,
                onRetryAttempt = onRetryAttempt
            ) {
                api.createChatCompletion(request)
            }
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
        tools: List<com.aichat.sandbox.data.model.ToolDefinition>?,
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
            val request = ChatCompletionRequest(
                model = chat.model,
                messages = apiMessages,
                temperature = chat.temperature,
                topP = chat.topP,
                maxTokens = chat.maxTokens,
                presencePenalty = chat.presencePenalty,
                frequencyPenalty = chat.frequencyPenalty,
                stream = true,
                tools = tools?.ifEmpty { null },
                toolChoice = if (!tools.isNullOrEmpty()) "auto" else null
            )
            val response = retryWithBackoff(
                policy = retryPolicy,
                onRetryAttempt = onRetryAttempt
            ) {
                api.createChatCompletionStream(request)
            }
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
        collector: kotlinx.coroutines.flow.FlowCollector<StreamEvent>
    ) {
        reader.use { r ->
            var line: String?
            // Accumulate tool calls across streaming deltas
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

                        // Handle text content delta
                        val content = delta?.get("content")?.takeIf { !it.isJsonNull }?.asString
                        if (content != null) {
                            collector.emit(StreamEvent.Delta(content))
                        }

                        // Handle tool call deltas
                        val toolCallsArr = delta?.getAsJsonArray("tool_calls")
                        if (toolCallsArr != null) {
                            for (tc in toolCallsArr) {
                                val tcObj = tc?.asJsonObject ?: continue
                                // `index` should always be present per the OpenAI
                                // streaming contract, but some compat backends
                                // omit it on the first delta — fall back to the
                                // next slot rather than NPE'ing the whole stream.
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

                        // Handle usage in final chunk
                        val usage = jsonObj.getAsJsonObject("usage")
                        if (usage != null) {
                            val parsedUsage = gson.fromJson(usage, Usage::class.java)
                            val toolCalls = buildAccumulatedToolCalls(toolCallAccumulator)
                            collector.emit(StreamEvent.Complete(parsedUsage, toolCalls))
                        }
                    } catch (t: Throwable) {
                        // One bad chunk shouldn't poison the whole stream.
                        // CancellationException needs to propagate so the
                        // job can actually cancel; everything else is logged
                        // and skipped.
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        Log.w("ApiClient", "Skipping malformed stream chunk: ${data.take(100)}", t)
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
        var arguments: StringBuilder = StringBuilder()
    )

    private fun buildAccumulatedToolCalls(
        accumulator: Map<Int, ToolCallAccumulator>
    ): List<com.aichat.sandbox.data.model.ToolCall>? {
        if (accumulator.isEmpty()) return null
        return accumulator.entries.sortedBy { it.key }.map { (_, acc) ->
            com.aichat.sandbox.data.model.ToolCall(
                id = acc.id,
                type = acc.type,
                function = com.aichat.sandbox.data.model.FunctionCall(
                    name = acc.functionName,
                    arguments = acc.arguments.toString()
                )
            )
        }
    }

    suspend fun generateTitle(
        baseUrl: String,
        apiKey: String,
        model: String,
        userMessage: String,
        assistantMessage: String
    ): String? {
        return try {
            val api = buildApi(baseUrl, apiKey)
            val messages = listOf(
                ApiMessage(
                    role = "system",
                    content = "Generate a concise 3-6 word title for this conversation. Return ONLY the title, no quotes, no punctuation at the end."
                ),
                ApiMessage(role = "user", content = userMessage),
                ApiMessage(role = "assistant", content = assistantMessage.take(500))
            )
            val request = ChatCompletionRequest(
                model = model,
                messages = messages,
                temperature = 0.7f,
                maxTokens = 20,
                stream = false
            )
            val response = api.createChatCompletion(request)
            if (response.isSuccessful) {
                (response.body()?.choices?.firstOrNull()?.message?.content as? String)?.trim()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w("ApiClient", "Failed to generate title", e)
            null
        }
    }

    private fun buildApiMessages(
        chat: Chat,
        messages: List<Message>,
        extraImageOnLastUserTurn: ByteArray? = null,
        extraSystemSuffix: String? = null,
    ): List<ApiMessage> {
        val apiMessages = mutableListOf<ApiMessage>()
        // Sub-phase 4.4 non-vision branch: the pinned note's OCR text rides
        // in the system stream so the model treats it as ambient context
        // rather than something the user just typed. Kept in the same system
        // turn (not merged onto `chat.systemMessage`) so the user's persisted
        // system prompt is never mutated.
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
        // Identify the last user-role index in the source list so we can
        // append the pinned-context image to *that* turn's content parts as
        // we walk through. Tool / assistant turns intermixed afterwards
        // don't see the injected image, which matches the spec: the image
        // is part of the user's most recent question, not the conversation
        // history.
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
                // Tool result message
                msg.role == "tool" && msg.metadata != null -> {
                    try {
                        val toolMeta = gson.fromJson(msg.metadata, ToolCallMetadata::class.java)
                        apiMessages.add(ApiMessage(
                            role = "tool",
                            content = msg.content,
                            toolCallId = toolMeta.toolCallId,
                            name = toolMeta.toolName
                        ))
                    } catch (e: Exception) {
                        Log.w("ApiClient", "Failed to parse tool metadata", e)
                        apiMessages.add(ApiMessage(role = msg.role, content = msg.content))
                    }
                }
                // Assistant message with tool calls
                msg.role == "assistant" && msg.contentType == "tool_call" && msg.metadata != null -> {
                    try {
                        val toolMeta = gson.fromJson(msg.metadata, ToolCallMetadata::class.java)
                        apiMessages.add(ApiMessage(
                            role = "assistant",
                            content = msg.content.ifBlank { null },
                            toolCalls = toolMeta.toolCalls
                        ))
                    } catch (e: Exception) {
                        Log.w("ApiClient", "Failed to parse tool call metadata", e)
                        apiMessages.add(ApiMessage(role = msg.role, content = msg.content))
                    }
                }
                // Multimodal message with images
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
                        Log.w("ApiClient", "Failed to parse image metadata", e)
                    }
                    if (attachExtraImage && extraImageDataUri != null) {
                        contentParts.add(
                            ImageContentPart(imageUrl = ImageUrl(url = extraImageDataUri))
                        )
                    }
                    apiMessages.add(ApiMessage(role = msg.role, content = contentParts))
                }
                // Standard text message — promotes to multimodal content
                // parts only when we need to inject the pinned-note image,
                // so the existing text path stays a plain string for the
                // overwhelming majority of turns.
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
