package com.aichat.sandbox.data.model

data class ModelPricing(
    val inputPricePerMillion: Double,
    val outputPricePerMillion: Double
) {
    fun estimateCost(promptTokens: Int, completionTokens: Int): Double {
        return (promptTokens * inputPricePerMillion / 1_000_000.0) +
            (completionTokens * outputPricePerMillion / 1_000_000.0)
    }

    companion object {
        // Pricing table for the May 2026 lineup. Order matters: `forModel`
        // matches by substring, so longer / more specific IDs (e.g.
        // "gpt-5.4-pro") must precede shorter prefixes ("gpt-5.4", "gpt-5").
        // Per-million-token prices in USD; updated alongside provider docs.
        private val pricingTable = listOf(
            // OpenAI GPT-5 series — pro / base / mini / nano ordered
            // longest-first so substring matching resolves deterministically.
            "gpt-5.5" to ModelPricing(5.00, 20.00),
            "gpt-5.4-pro" to ModelPricing(15.00, 60.00),
            "gpt-5.4-mini" to ModelPricing(0.50, 2.00),
            "gpt-5.4-nano" to ModelPricing(0.15, 0.60),
            "gpt-5.4" to ModelPricing(3.00, 12.00),
            "gpt-5.2" to ModelPricing(2.50, 10.00),
            "gpt-5.1" to ModelPricing(2.00, 8.00),
            "gpt-5" to ModelPricing(2.50, 10.00),

            // Anthropic Claude 4.x.
            "claude-opus-4-7" to ModelPricing(15.00, 75.00),
            "claude-sonnet-4-6" to ModelPricing(3.00, 15.00),
            "claude-haiku-4-5" to ModelPricing(0.80, 4.00),

            // Google Gemini 3.x + 2.5 fallback.
            "gemini-3.1-pro-preview" to ModelPricing(1.50, 12.00),
            "gemini-3.1-flash-lite" to ModelPricing(0.10, 0.40),
            "gemini-3-pro-preview" to ModelPricing(1.25, 10.00),
            "gemini-2.5-flash" to ModelPricing(0.15, 0.60),

            // Generic fallbacks for substring inference (custom user models).
            "claude-opus" to ModelPricing(15.00, 75.00),
            "claude-sonnet" to ModelPricing(3.00, 15.00),
            "claude-haiku" to ModelPricing(0.80, 4.00),
            "gemini" to ModelPricing(1.25, 10.00),
        )

        private val defaultPricing = ModelPricing(2.50, 10.00)

        fun forModel(model: String): ModelPricing {
            return pricingTable.firstOrNull { model.contains(it.first) }?.second
                ?: defaultPricing
        }
    }
}
