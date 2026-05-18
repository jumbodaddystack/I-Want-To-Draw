package com.aichat.sandbox.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * Google Gemini API DTOs. Schema spec:
 * https://ai.google.dev/api/generate-content
 *
 * Notable differences vs OpenAI:
 * - Role names: `user` / `model` (not `assistant`).
 * - `systemInstruction` is a top-level field, not a turn.
 * - Each turn is `{role, parts:[{text}|{inlineData}|{functionCall}|{functionResponse}]}`.
 * - Tool calls are `parts[].functionCall`; results are `parts[].functionResponse`.
 * - Sampling params nest under `generationConfig`.
 */
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null,
    val tools: List<GeminiTool>? = null,
    val generationConfig: GeminiGenerationConfig? = null,
)

data class GeminiContent(
    val role: String? = null, // "user" | "model" ; null in systemInstruction
    val parts: List<GeminiPart>,
)

/**
 * Polymorphic part. Only one of the fields is populated; the rest are
 * left null so Gson omits them. Gemini's REST schema uses an implicit
 * discriminator (whichever field is present).
 */
data class GeminiPart(
    val text: String? = null,
    val inlineData: GeminiInlineData? = null,
    val functionCall: GeminiFunctionCall? = null,
    val functionResponse: GeminiFunctionResponse? = null,
)

data class GeminiInlineData(
    val mimeType: String,
    val data: String,
)

data class GeminiFunctionCall(
    val name: String,
    val args: Map<String, Any?>? = null,
)

data class GeminiFunctionResponse(
    val name: String,
    val response: Map<String, Any?>,
)

data class GeminiTool(
    val functionDeclarations: List<GeminiFunctionDeclaration>,
)

data class GeminiFunctionDeclaration(
    val name: String,
    val description: String,
    val parameters: Map<String, Any?>?,
)

data class GeminiGenerationConfig(
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxOutputTokens: Int? = null,
)

// --- Response ----------------------------------------------------------

data class GeminiResponse(
    val candidates: List<GeminiCandidate>?,
    val usageMetadata: GeminiUsage?,
    val error: GeminiError?,
)

data class GeminiCandidate(
    val content: GeminiContent?,
    val finishReason: String?,
    val index: Int?,
)

data class GeminiUsage(
    val promptTokenCount: Int = 0,
    val candidatesTokenCount: Int = 0,
    val totalTokenCount: Int = 0,
)

data class GeminiError(val code: Int?, val message: String?, val status: String?)
data class GeminiErrorEnvelope(val error: GeminiError?)
