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
    ): Flow<StreamEvent>
}
