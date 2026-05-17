package com.aichat.sandbox.data.remote

import android.util.Log
import com.aichat.sandbox.BuildConfig
import com.aichat.sandbox.data.model.*
import com.google.gson.Gson
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
        tools: List<com.aichat.sandbox.data.model.ToolDefinition>?
    ): Flow<StreamEvent> = flow {
        try {
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
                        val delta = choices?.get(0)?.asJsonObject?.getAsJsonObject("delta")

                        // Handle text content delta
                        val content = delta?.get("content")?.takeIf { !it.isJsonNull }?.asString
                        if (content != null) {
                            collector.emit(StreamEvent.Delta(content))
                        }

                        // Handle tool call deltas
                        val toolCallsArr = delta?.getAsJsonArray("tool_calls")
                        if (toolCallsArr != null) {
                            for (tc in toolCallsArr) {
                                val tcObj = tc.asJsonObject
                                val index = tcObj.get("index").asInt
                                val acc = toolCallAccumulator.getOrPut(index) { ToolCallAccumulator() }
                                tcObj.get("id")?.takeIf { !it.isJsonNull }?.asString?.let { acc.id = it }
                                tcObj.get("type")?.takeIf { !it.isJsonNull }?.asString?.let { acc.type = it }
                                val funcObj = tcObj.getAsJsonObject("function")
                                if (funcObj != null) {
                                    funcObj.get("name")?.takeIf { !it.isJsonNull }?.asString?.let { acc.functionName = it }
                                    funcObj.get("arguments")?.takeIf { !it.isJsonNull }?.asString?.let { acc.arguments.append(it) }
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
                    } catch (e: JsonSyntaxException) {
                        Log.w("ApiClient", "Skipping malformed stream chunk: ${data.take(100)}", e)
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

    private fun buildApiMessages(chat: Chat, messages: List<Message>): List<ApiMessage> {
        val apiMessages = mutableListOf<ApiMessage>()
        if (chat.systemMessage.isNotBlank()) {
            apiMessages.add(ApiMessage(role = "system", content = chat.systemMessage))
        }
        messages.forEach { msg ->
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
                    apiMessages.add(ApiMessage(role = msg.role, content = contentParts))
                }
                // Standard text message
                else -> {
                    apiMessages.add(ApiMessage(role = msg.role, content = msg.content))
                }
            }
        }
        return apiMessages
    }
}
