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
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Native adapter for Google's Gemini REST API.
 *
 * Wire-format translation:
 * - Role mapping: `user` → `user`, `assistant` → `model`.
 * - `system` ApiMessage → top-level `systemInstruction`.
 * - Plain text → `parts: [{text:…}]`; images → `parts: [{inlineData:{mimeType,data}}]`.
 * - Tool calls (assistant) → `parts: [{functionCall:{name,args}}]`.
 * - Tool results → a `user` (per Gemini contract) turn with
 *   `parts: [{functionResponse:{name, response:{result:…}}}]`.
 * - Sampling params nest under `generationConfig`. Gemini doesn't take
 *   presence/frequency penalties; those are dropped silently.
 *
 * Streaming uses `:streamGenerateContent?alt=sse` and emits JSON-per-data
 * SSE frames (no `[DONE]` sentinel — relies on stream EOF).
 */
@Singleton
class GeminiAdapter @Inject constructor() : ProviderAdapter {
    private val gson = Gson()
    private val apiCache = mutableMapOf<String, GeminiApi>()
    private val retryPolicy = RetryPolicy()

    override fun matches(baseUrl: String): Boolean =
        baseUrl.contains("generativelanguage.googleapis.com", ignoreCase = true)

    private fun buildApi(baseUrl: String): GeminiApi {
        return apiCache.getOrPut(baseUrl) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                        else HttpLoggingInterceptor.Level.NONE
            }
            val client = OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
            Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(GeminiApi::class.java)
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
            val api = buildApi(baseUrl)
            val request = buildRequest(chat, messages, tools, null, null)
            val response = retryWithBackoff(
                policy = retryPolicy,
                onRetryAttempt = onRetryAttempt,
            ) { api.generateContent(chat.model, apiKey, request) }
            if (response.isSuccessful) {
                val body = response.body() ?: return ApiResult.Error("Empty response body")
                body.error?.let { return ApiResult.Error(it.message ?: "Gemini error") }
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
            val api = buildApi(baseUrl)
            val request = buildRequest(chat, messages, tools, extraImageOnLastUserTurn, extraSystemSuffix)
            val response = retryWithBackoff(
                policy = retryPolicy,
                onRetryAttempt = onRetryAttempt,
            ) { api.streamGenerateContent(chat.model, apiKey, request = request) }
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

    private suspend fun processStream(
        reader: BufferedReader,
        collector: kotlinx.coroutines.flow.FlowCollector<StreamEvent>,
    ) {
        reader.use { r ->
            val toolAccumulators = mutableListOf<ToolCall>()
            var inputTokens = 0
            var outputTokens = 0
            var line: String?
            while (r.readLine().also { line = it } != null) {
                currentCoroutineContext().ensureActive()
                val l = line ?: continue
                if (!l.startsWith("data: ")) continue
                val data = l.removePrefix("data: ").trim()
                if (data.isEmpty()) continue
                try {
                    val resp = gson.fromJson(data, GeminiResponse::class.java) ?: continue
                    resp.error?.let {
                        collector.emit(StreamEvent.Error(it.message ?: "Gemini error"))
                        return
                    }
                    resp.usageMetadata?.let {
                        inputTokens = it.promptTokenCount
                        outputTokens = it.candidatesTokenCount
                    }
                    val parts = resp.candidates?.firstOrNull()?.content?.parts.orEmpty()
                    for (part in parts) {
                        part.text?.let { collector.emit(StreamEvent.Delta(it)) }
                        part.functionCall?.let { fc ->
                            // Gemini doesn't send IDs for tool calls; synthesize
                            // one so the round-trip with OpenAI-shaped storage
                            // keeps a stable handle.
                            toolAccumulators.add(ToolCall(
                                id = "gemini-${UUID.randomUUID()}",
                                type = "function",
                                function = FunctionCall(
                                    name = fc.name,
                                    arguments = gson.toJson(fc.args ?: emptyMap<String, Any?>()),
                                ),
                            ))
                        }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.w("GeminiAdapter", "Skipping malformed SSE chunk: ${data.take(100)}", t)
                }
            }
            val usage = Usage(
                promptTokens = inputTokens,
                completionTokens = outputTokens,
                totalTokens = inputTokens + outputTokens,
            )
            collector.emit(StreamEvent.Complete(usage, toolAccumulators.ifEmpty { null }))
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
            val api = buildApi(baseUrl)
            val request = GeminiRequest(
                systemInstruction = GeminiContent(
                    role = null,
                    parts = listOf(GeminiPart(text = "Generate a concise 3-6 word title for this conversation. Return ONLY the title, no quotes, no punctuation at the end.")),
                ),
                contents = listOf(
                    GeminiContent(
                        role = "user",
                        parts = listOf(GeminiPart(
                            text = "USER: $userMessage\nASSISTANT: ${assistantMessage.take(500)}",
                        )),
                    ),
                ),
                generationConfig = GeminiGenerationConfig(
                    temperature = 0.7f,
                    maxOutputTokens = 32,
                ),
            )
            val response = api.generateContent(model, apiKey, request)
            if (!response.isSuccessful) return null
            val text = response.body()?.candidates?.firstOrNull()?.content?.parts
                ?.mapNotNull { it.text }?.joinToString("")?.trim()
            text
        } catch (e: Exception) {
            Log.w("GeminiAdapter", "Failed to generate title", e)
            null
        }
    }

    private fun buildRequest(
        chat: Chat,
        messages: List<Message>,
        tools: List<ToolDefinition>?,
        extraImageOnLastUserTurn: ByteArray?,
        extraSystemSuffix: String?,
    ): GeminiRequest {
        val systemBase = chat.systemMessage.takeIf { it.isNotBlank() }
        val systemSuffix = extraSystemSuffix?.takeIf { it.isNotBlank() }
        val systemText = when {
            systemBase != null && systemSuffix != null -> "$systemBase\n\n$systemSuffix"
            systemBase != null -> systemBase
            systemSuffix != null -> systemSuffix
            else -> null
        }

        val lastUserIndex = if (extraImageOnLastUserTurn != null) {
            messages.indexOfLast { it.role == "user" }
        } else -1

        val contents = mutableListOf<GeminiContent>()
        messages.forEachIndexed { index, msg ->
            val attach = index == lastUserIndex
            val translated = translateMessage(msg, attach, extraImageOnLastUserTurn) ?: return@forEachIndexed
            contents.add(translated)
        }

        return GeminiRequest(
            contents = coalesceConsecutiveRoles(contents),
            systemInstruction = systemText?.let {
                GeminiContent(role = null, parts = listOf(GeminiPart(text = it)))
            },
            tools = tools?.takeIf { it.isNotEmpty() }?.let {
                listOf(GeminiTool(functionDeclarations = it.map { td ->
                    GeminiFunctionDeclaration(
                        name = td.function.name,
                        description = td.function.description,
                        parameters = td.function.parameters,
                    )
                }))
            },
            generationConfig = GeminiGenerationConfig(
                temperature = chat.temperature,
                topP = chat.topP,
                maxOutputTokens = chat.maxTokens.takeIf { it > 0 },
            ),
        )
    }

    private fun translateMessage(
        msg: Message,
        attachExtraImage: Boolean,
        extraImage: ByteArray?,
    ): GeminiContent? {
        return when {
            // Tool result → user turn carrying a functionResponse part.
            msg.role == "tool" && msg.metadata != null -> {
                val toolMeta = parseToolMeta(msg.metadata) ?: return null
                GeminiContent(
                    role = "user",
                    parts = listOf(GeminiPart(functionResponse = GeminiFunctionResponse(
                        name = toolMeta.toolName.orEmpty(),
                        response = mapOf("result" to msg.content),
                    ))),
                )
            }
            // Assistant turn that issued tool calls.
            msg.role == "assistant" && msg.contentType == "tool_call" && msg.metadata != null -> {
                val toolMeta = parseToolMeta(msg.metadata) ?: return null
                val parts = mutableListOf<GeminiPart>()
                if (msg.content.isNotBlank()) parts.add(GeminiPart(text = msg.content))
                toolMeta.toolCalls?.forEach { tc ->
                    parts.add(GeminiPart(functionCall = GeminiFunctionCall(
                        name = tc.function.name,
                        args = parseJsonArgs(tc.function.arguments),
                    )))
                }
                GeminiContent(role = "model", parts = parts)
            }
            msg.contentType == "multimodal" && msg.metadata != null -> {
                val parts = mutableListOf<GeminiPart>()
                if (msg.content.isNotBlank()) parts.add(GeminiPart(text = msg.content))
                runCatching {
                    gson.fromJson(msg.metadata, ImageMetadata::class.java)?.images?.forEach { img ->
                        decodeDataUri(img.dataUri)?.let { (mime, b64) ->
                            parts.add(GeminiPart(inlineData = GeminiInlineData(mimeType = mime, data = b64)))
                        }
                    }
                }
                if (attachExtraImage && extraImage != null) {
                    parts.add(GeminiPart(inlineData = GeminiInlineData(
                        mimeType = "image/png",
                        data = android.util.Base64.encodeToString(extraImage, android.util.Base64.NO_WRAP),
                    )))
                }
                GeminiContent(role = roleToGemini(msg.role), parts = parts)
            }
            else -> {
                val parts = mutableListOf<GeminiPart>()
                if (msg.content.isNotBlank()) parts.add(GeminiPart(text = msg.content))
                if (attachExtraImage && extraImage != null) {
                    parts.add(GeminiPart(inlineData = GeminiInlineData(
                        mimeType = "image/png",
                        data = android.util.Base64.encodeToString(extraImage, android.util.Base64.NO_WRAP),
                    )))
                }
                if (parts.isEmpty()) return null
                GeminiContent(role = roleToGemini(msg.role), parts = parts)
            }
        }
    }

    private fun roleToGemini(role: String): String = when (role) {
        "assistant" -> "model"
        "system" -> "user" // shouldn't get here — system goes to systemInstruction
        else -> "user"
    }

    private fun coalesceConsecutiveRoles(contents: List<GeminiContent>): List<GeminiContent> {
        if (contents.isEmpty()) return contents
        val out = mutableListOf<GeminiContent>()
        for (c in contents) {
            val last = out.lastOrNull()
            if (last != null && last.role == c.role) {
                out[out.lastIndex] = last.copy(parts = last.parts + c.parts)
            } else {
                out.add(c)
            }
        }
        return out
    }

    private fun parseToolMeta(metadata: String): ToolCallMetadata? = try {
        gson.fromJson(metadata, ToolCallMetadata::class.java)
    } catch (e: Exception) {
        Log.w("GeminiAdapter", "Failed to parse tool metadata", e)
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

    private fun decodeDataUri(uri: String): Pair<String, String>? {
        if (!uri.startsWith("data:")) return null
        val comma = uri.indexOf(',')
        if (comma < 0) return null
        val header = uri.substring(5, comma)
        val media = header.substringBefore(';').takeIf { it.isNotBlank() } ?: return null
        return media to uri.substring(comma + 1)
    }

    private fun parseErrorBody(body: String?, code: Int): String {
        if (body.isNullOrBlank()) return "HTTP $code"
        return try {
            gson.fromJson(body, GeminiErrorEnvelope::class.java)?.error?.message ?: body
        } catch (e: Exception) {
            body
        }
    }

    private fun GeminiResponse.toChatCompletionResponse(): ChatCompletionResponse {
        val parts = candidates?.firstOrNull()?.content?.parts.orEmpty()
        val text = parts.mapNotNull { it.text }.joinToString("")
        val toolCalls = parts.mapNotNull { p ->
            p.functionCall?.let { fc ->
                ToolCall(
                    id = "gemini-${UUID.randomUUID()}",
                    type = "function",
                    function = FunctionCall(
                        name = fc.name,
                        arguments = gson.toJson(fc.args ?: emptyMap<String, Any?>()),
                    ),
                )
            }
        }
        val u = usageMetadata ?: GeminiUsage()
        return ChatCompletionResponse(
            id = null,
            `object` = "chat.completion",
            created = null,
            model = null,
            choices = listOf(Choice(
                index = 0,
                message = ApiMessage(
                    role = "assistant",
                    content = text,
                    toolCalls = toolCalls.ifEmpty { null },
                ),
                delta = null,
                finishReason = candidates?.firstOrNull()?.finishReason,
            )),
            usage = Usage(
                promptTokens = u.promptTokenCount,
                completionTokens = u.candidatesTokenCount,
                totalTokens = if (u.totalTokenCount > 0) u.totalTokenCount
                              else u.promptTokenCount + u.candidatesTokenCount,
            ),
            error = null,
        )
    }
}
