package com.aichat.sandbox.data.remote

import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.model.ToolDefinition
import kotlinx.coroutines.flow.Flow

/**
 * Minimal surface of the chat HTTP layer needed by callers that don't own a
 * full [ApiClient] — currently `NoteAiService` (sub-phase 2.5 of
 * `docs/STYLUS_NOTES_PHASE_2.md`). Extracting it lets the service be unit
 * tested with a fake stream while existing chat callers keep using the
 * concrete [ApiClient] unchanged.
 */
interface ChatStreamer {
    fun sendMessageStream(
        baseUrl: String,
        apiKey: String,
        chat: Chat,
        messages: List<Message>,
        onRetryAttempt: ((Int) -> Unit)? = null,
        tools: List<ToolDefinition>? = null,
        // Sub-phase 4.4 pinned-note hooks. Both default to `null` so existing
        // callers (NoteAiService, the chat send path before pinning lands)
        // don't need to know about them. Wiring contract: the caller decides
        // which side of the vision branch to populate — vision models get
        // [extraImageOnLastUserTurn] appended to the trailing user message's
        // content parts; non-vision models get [extraSystemSuffix] appended
        // to the system message so the OCR text rides as context, not as a
        // user turn.
        extraImageOnLastUserTurn: ByteArray? = null,
        extraSystemSuffix: String? = null,
    ): Flow<StreamEvent>
}
