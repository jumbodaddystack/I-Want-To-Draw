package com.aichat.sandbox.data.model

/**
 * Single source of truth for "what can model X do?". Phase 2 of the stylus notes
 * plan branches its AI request pipeline on `supportsVision`; the registry exists
 * so that branch lives in one well-tested place rather than scattered string
 * checks.
 */
object ModelCapabilities {

    data class Caps(
        val supportsVision: Boolean,
        val notes: String = "",
    )

    private val table: Map<String, Caps> = mapOf(
        // OpenAI GPT-5 series — all vision-capable.
        "gpt-5.5" to Caps(true),
        "gpt-5.4" to Caps(true),
        "gpt-5.4-pro" to Caps(true),
        "gpt-5.4-mini" to Caps(true),
        "gpt-5.4-nano" to Caps(true),
        "gpt-5.2" to Caps(true),
        "gpt-5.1" to Caps(true),

        // Anthropic Claude 4.x — all vision-capable.
        "claude-opus-4-7" to Caps(true),
        "claude-sonnet-4-6" to Caps(true),
        "claude-haiku-4-5" to Caps(true),

        // Google Gemini 3.x + 2.5 fallback — all vision-capable.
        "gemini-3.1-pro-preview" to Caps(true),
        "gemini-3.1-flash-lite" to Caps(true),
        "gemini-3-pro-preview" to Caps(true),
        "gemini-2.5-flash" to Caps(true),
    )

    fun of(modelId: String): Caps {
        if (modelId.isBlank()) return Caps(false)
        return table[modelId] ?: inferFromName(modelId)
    }

    private fun inferFromName(id: String): Caps {
        val l = id.lowercase()
        val vision = l.contains("vision") ||
            l.contains("gpt-5") ||
            l.contains("gpt-4o") ||
            l.contains("claude") ||
            l.contains("gemini") ||
            l.contains("multimodal")
        return Caps(vision, notes = "inferred")
    }
}

fun String.supportsVision(): Boolean = ModelCapabilities.of(this).supportsVision

fun Chat.supportsVision(): Boolean = model.supportsVision()
