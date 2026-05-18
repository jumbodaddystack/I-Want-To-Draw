package com.aichat.sandbox.data.model

data class ChatSettings(
    val temperature: Float = Defaults.TEMPERATURE,
    val topP: Float = Defaults.TOP_P,
    val maxTokens: Int = Defaults.MAX_TOKENS,
    val presencePenalty: Float = Defaults.PRESENCE_PENALTY,
    val frequencyPenalty: Float = Defaults.FREQUENCY_PENALTY
) {
    object Defaults {
        const val MODEL = "gpt-5.4"
        const val TEMPERATURE = 0.1f
        const val TOP_P = 1.0f
        const val MAX_TOKENS = 131072
        const val MAX_TOKENS_LIMIT = 131072f
        const val PRESENCE_PENALTY = 0.0f
        const val FREQUENCY_PENALTY = 0.0f
        const val API_BASE_URL = "https://api.openai.com/v1/"
        const val DARK_MODE = true
    }
}

data class ApiProvider(
    val name: String,
    val baseUrl: String,
    val models: List<String>
) {
    companion object {
        // Model IDs reflect the May 2026 lineup. Adapter layer
        // (ProviderAdapter) translates each provider's native schema, so
        // baseUrls now point at the real provider endpoints rather than
        // OpenAI-compatible proxies.
        val OpenAI = ApiProvider(
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1/",
            models = listOf(
                "gpt-5.5",
                "gpt-5.4",
                "gpt-5.4-pro",
                "gpt-5.4-mini",
                "gpt-5.4-nano",
                "gpt-5.2",
                "gpt-5.1"
            )
        )
        val Anthropic = ApiProvider(
            name = "Anthropic",
            baseUrl = "https://api.anthropic.com/v1/",
            models = listOf(
                "claude-opus-4-7",
                "claude-sonnet-4-6",
                "claude-haiku-4-5"
            )
        )
        val Google = ApiProvider(
            name = "Google",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/",
            models = listOf(
                "gemini-3.1-pro-preview",
                "gemini-3.1-flash-lite",
                "gemini-3-pro-preview",
                "gemini-2.5-flash"
            )
        )

        val defaults = listOf(OpenAI, Anthropic, Google)

        // All known IDs across all providers — used by PreferencesManager
        // to coerce stale persisted defaults onto a model that still
        // exists in the registry.
        val allKnownModels: Set<String> = defaults.flatMap { it.models }.toSet()
    }
}
