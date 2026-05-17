package com.aichat.sandbox.data.model

/**
 * Single source of truth for "what can model X do?". Phase 2 of the stylus notes
 * plan branches its AI request pipeline on `supportsVision`; the registry exists
 * so that branch lives in one well-tested place rather than scattered string
 * checks. See `docs/STYLUS_NOTES_PHASE_2.md` sub-phase 2.1.
 */
object ModelCapabilities {

    data class Caps(
        val supportsVision: Boolean,
        val notes: String = "",
    )

    private val table: Map<String, Caps> = mapOf(
        // OpenAI
        "gpt-4o" to Caps(true),
        "gpt-4o-mini" to Caps(true),
        "gpt-4-turbo" to Caps(true),
        "gpt-4.1" to Caps(true),
        "gpt-4.1-mini" to Caps(true),
        "gpt-4.1-nano" to Caps(true),
        "gpt-4" to Caps(false),
        "gpt-3.5-turbo" to Caps(false),
        "o3" to Caps(true),
        "o3-mini" to Caps(false),
        "o4-mini" to Caps(true),

        // Anthropic
        "claude-opus-4-7" to Caps(true),
        "claude-opus-4-6" to Caps(true),
        "claude-sonnet-4-6" to Caps(true),
        "claude-haiku-4-5" to Caps(true),
        "claude-haiku-4-5-20251001" to Caps(true),
        "claude-sonnet-4-20250514" to Caps(true),
        "claude-opus-4-20250514" to Caps(true),

        // Google
        "gemini-2.5-pro" to Caps(true),
        "gemini-2.5-flash" to Caps(true),
        "gemini-2.0-flash" to Caps(true),
    )

    fun of(modelId: String): Caps {
        if (modelId.isBlank()) return Caps(false)
        return table[modelId] ?: inferFromName(modelId)
    }

    private fun inferFromName(id: String): Caps {
        val l = id.lowercase()
        val vision = l.contains("vision") ||
            l.contains("4o") ||
            l.contains("claude") ||
            l.contains("gemini") ||
            l.contains("multimodal")
        return Caps(vision, notes = "inferred")
    }
}

fun String.supportsVision(): Boolean = ModelCapabilities.of(this).supportsVision

fun Chat.supportsVision(): Boolean = model.supportsVision()
