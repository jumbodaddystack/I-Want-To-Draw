package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem

/**
 * Inputs for `NoteAiService.ask` (sub-phase 2.5 of
 * `docs/STYLUS_NOTES_PHASE_2.md`).
 *
 * The caller supplies `allItems` so the service doesn't need a [NoteDao]
 * dependency — `NoteEditorViewModel` already keeps the live item list in
 * memory and can pass it through unchanged. `selection == null` means "ask
 * about the whole note"; a non-null selection narrows the scope (lasso
 * Ask in 2.7). `baseUrl` / `apiKey` come from the same preferences the chat
 * pipeline reads, captured at request time so a setting change mid-stream
 * doesn't affect an in-flight ask.
 */
data class AskRequest(
    val note: Note,
    val allItems: List<NoteItem>,
    val selection: List<NoteItem>?,
    val userPrompt: String,
    val modelId: String,
    val baseUrl: String,
    val apiKey: String,
)
