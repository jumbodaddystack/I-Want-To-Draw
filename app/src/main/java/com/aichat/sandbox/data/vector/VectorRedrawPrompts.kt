package com.aichat.sandbox.data.vector

/**
 * Phase 5 — prompt constants for the semantic redraw workflow.
 *
 * Where [VectorTuneupPrompts] constrains the model to safe edits of existing
 * paths, these instruct it to reconstruct the drawing as a clean scene of
 * primitive objects. The model still only ever sees the compact
 * [VectorSummaryJson] (never raw XML) and must reply with only a fenced
 * `vector-scene` block, which [VectorSceneParser] validates and
 * [VectorSceneCompiler] turns into the candidate XML — the model never returns
 * raw XML itself.
 */
object VectorRedrawPrompts {

    const val SYSTEM_MESSAGE: String =
        "You redraw Android VectorDrawable artwork as a clean vector scene. You " +
            "receive a compact JSON summary of the drawing — viewport, metrics, " +
            "per-path styles, sampled points — NOT the full XML. You never see or " +
            "return raw XML.\n\n" +
            "Reply with ONLY a fenced ```vector-scene block of JSON matching this schema:\n\n" +
            "{ \"schema\": 1,\n" +
            "  \"viewport\": { \"widthDp\": W, \"heightDp\": H, \"viewportWidth\": W, \"viewportHeight\": H },\n" +
            "  \"styleIntent\": \"<short phrase>\",\n" +
            "  \"objects\": [ /* primitive objects */ ] }\n\n" +
            "Each object has an \"id\", a \"type\", geometry, and style " +
            "(\"stroke\", \"fill\", \"strokeWidth\", optional \"lineCap\"/\"lineJoin\"/\"strokeAlpha\"/\"fillAlpha\").\n" +
            "Supported types and their geometry:\n" +
            "- path: \"pathData\" (SVG/Android path string)\n" +
            "- line: \"x0\",\"y0\",\"x1\",\"y1\"\n" +
            "- rect: \"x\",\"y\",\"width\",\"height\",optional \"radius\"\n" +
            "- ellipse: \"cx\",\"cy\",\"rx\",\"ry\",optional \"rotation\"\n" +
            "- polygon / polyline: \"points\": [[x,y],...], optional \"closed\"\n\n" +
            "Rules:\n" +
            "- You MAY create new primitive objects; you are not limited to the " +
            "original paths.\n" +
            "- Keep the SAME viewport as the summary.\n" +
            "- Keep the object count reasonably small and prefer simple geometry.\n" +
            "- Preserve the original subject matter and color palette unless the " +
            "user asks otherwise.\n" +
            "- Keep ALL geometry inside the viewport.\n" +
            "- Never return raw XML; only the fenced vector-scene JSON.\n" +
            "- If you cannot infer the drawing safely, return an empty \"objects\" " +
            "array with a styleIntent summary."

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
