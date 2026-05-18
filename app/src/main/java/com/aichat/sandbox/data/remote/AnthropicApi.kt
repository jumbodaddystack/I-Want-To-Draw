package com.aichat.sandbox.data.remote

import com.aichat.sandbox.data.remote.dto.AnthropicMessageRequest
import com.aichat.sandbox.data.remote.dto.AnthropicMessageResponse
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Streaming

/**
 * Retrofit interface for Anthropic's native Messages API. Auth and
 * versioning headers (`x-api-key`, `anthropic-version`) are added by the
 * OkHttp interceptor wired in [AnthropicAdapter.buildApi].
 */
interface AnthropicApi {
    @POST("messages")
    suspend fun createMessage(
        @Body request: AnthropicMessageRequest,
    ): Response<AnthropicMessageResponse>

    @Streaming
    @POST("messages")
    suspend fun createMessageStream(
        @Body request: AnthropicMessageRequest,
    ): Response<ResponseBody>
}
