package com.aichat.sandbox.data.model

import com.google.gson.Gson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionRequestTest {

    private val gson = Gson()

    @Test
    fun reasoningStyleSerializesMaxCompletionTokensAndOmitsLegacyParams() {
        val json = gson.toJson(
            ChatCompletionRequest(
                model = "gpt-5.4",
                messages = listOf(ApiMessage(role = "user", content = "hi")),
                maxCompletionTokens = 1000,
            )
        )
        assertTrue("expected max_completion_tokens", json.contains("\"max_completion_tokens\":1000"))
        assertFalse("legacy max_tokens must be absent", json.contains("\"max_tokens\""))
        // Null sampling params must be omitted by Gson, not sent as null.
        assertFalse(json.contains("\"temperature\""))
        assertFalse(json.contains("\"top_p\""))
    }

    @Test
    fun legacyStyleSerializesMaxTokensAndSamplingParams() {
        val json = gson.toJson(
            ChatCompletionRequest(
                model = "gpt-4o",
                messages = listOf(ApiMessage(role = "user", content = "hi")),
                temperature = 0.1f,
                maxTokens = 1000,
            )
        )
        assertTrue("expected max_tokens", json.contains("\"max_tokens\":1000"))
        assertFalse("new max_completion_tokens must be absent", json.contains("\"max_completion_tokens\""))
        assertTrue(json.contains("\"temperature\""))
    }
}
