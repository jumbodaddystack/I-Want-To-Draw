package com.aichat.sandbox.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiProviderTest {

    @Test
    fun builtInModelsResolveToTheirProvider() {
        assertEquals("OpenAI", ApiProvider.providerNameFor("gpt-5.5"))
        assertEquals("OpenAI", ApiProvider.providerNameFor("gpt-5.4-mini"))
        assertEquals("Anthropic", ApiProvider.providerNameFor("claude-opus-4-7"))
        assertEquals("Google", ApiProvider.providerNameFor("gemini-3.1-pro-preview"))
    }

    @Test
    fun unknownModelFallsBackToOpenAi() {
        assertEquals("OpenAI", ApiProvider.providerNameFor("some-unknown-model"))
        assertEquals("OpenAI", ApiProvider.providerNameFor(""))
    }

    @Test
    fun customModelResolvesToItsStoredProvider() {
        val customs = mapOf("Anthropic" to listOf("claude-custom-x"))
        assertEquals("Anthropic", ApiProvider.providerNameFor("claude-custom-x", customs))
    }

    @Test
    fun defaultBaseUrlMatchesProviderEndpoint() {
        assertEquals(ApiProvider.OpenAI.baseUrl, ApiProvider.defaultBaseUrlFor("OpenAI"))
        assertEquals(ApiProvider.Anthropic.baseUrl, ApiProvider.defaultBaseUrlFor("Anthropic"))
        assertEquals(ApiProvider.Google.baseUrl, ApiProvider.defaultBaseUrlFor("Google"))
        // Unknown provider name falls back to OpenAI's endpoint.
        assertEquals(ApiProvider.OpenAI.baseUrl, ApiProvider.defaultBaseUrlFor("Custom"))
    }
}
