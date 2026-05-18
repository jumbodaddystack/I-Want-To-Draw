package com.aichat.sandbox.data.remote

import com.aichat.sandbox.data.remote.dto.GeminiRequest
import com.aichat.sandbox.data.remote.dto.GeminiResponse
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * Retrofit interface for Google Gemini's REST API. Auth happens via the
 * `?key=` query param (no Authorization header), which is OK since
 * Gemini's API keys are scoped via Google Cloud rather than acting as a
 * bearer token.
 *
 * Streaming uses `:streamGenerateContent?alt=sse` to get an SSE response;
 * without `alt=sse` Gemini returns NDJSON which is harder to parse with
 * BufferedReader.
 */
interface GeminiApi {
    @POST("models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GeminiRequest,
    ): Response<GeminiResponse>

    @Streaming
    @POST("models/{model}:streamGenerateContent")
    suspend fun streamGenerateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Query("alt") alt: String = "sse",
        @Body request: GeminiRequest,
    ): Response<ResponseBody>
}
