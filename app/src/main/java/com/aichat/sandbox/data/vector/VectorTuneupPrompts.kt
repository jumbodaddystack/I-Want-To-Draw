package com.aichat.sandbox.data.vector

/**
 * Phase 4 — prompt constants for the model-guided vector tune-up.
 *
 * The system message constrains the model to the safe contract: it sees a
 * compact JSON summary (never raw XML), and must reply with only a fenced
 * `vector-edit-plan` block of operations the app validates and applies. Kept
 * separate so tests can pin the wire format without reaching into
 * [com.aichat.sandbox.data.vector.VectorTuneupAiService].
 */
object VectorTuneupPrompts {

    const val SYSTEM_MESSAGE: String =
        "You edit Android VectorDrawable artwork. You receive a compact JSON " +
            "summary of the drawing — viewport, metrics, per-path styles, sampled " +
            "points, and noise estimates — NOT the full XML. You never see or " +
            "return raw XML.\n\n" +
            "Reply with ONLY a fenced ```vector-edit-plan block matching this schema:\n\n" +
            "{ \"schema\": 1, \"mode\": \"tune_up\", \"summary\": \"<one short sentence>\",\n" +
            "  \"operations\": [ /* operations targeting paths by id or color */ ] }\n\n" +
            "Supported operations: simplify_paths, remove_paths, restyle_paths, " +
            "recolor_paths. Each has a \"target\" of { pathIds?, colors?, all?, " +
            "strokedOnly?, filledOnly? }.\n\n" +
            "Rules:\n" +
            "- Only target path IDs or colors that appear in the summary.\n" +
            "- Do not invent new paths, objects, or geometry.\n" +
            "- Keep the same viewport.\n" +
            "- You may simplify noisy stroke paths, remove obvious noise, restyle " +
            "stroke width/caps/joins, or recolor stroke/fill.\n" +
            "- Use conservative operations unless the user asks for stronger cleanup.\n" +
            "- If you cannot safely improve the drawing, return an empty operations " +
            "array with a short summary. Never reply outside the fenced block."

    fun buildUserPrompt(
        userPrompt: String,
        summaryJson: String,
    ): String = buildString {
        append("User request:\n")
        append(userPrompt)
        append("\n\n")
        append("Vector summary JSON:\n")
        append("```json\n")
        append(summaryJson)
        append("\n```")
    }
}
