package com.aichat.sandbox.data.notes

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-scoped in-memory handover store for the "Send to chat" flow
 * (sub-phase 4.3 of `docs/STYLUS_NOTES_PHASE_4.md`).
 *
 * The note editor renders a PNG of the note (or AI reply scope), stashes it
 * here keyed by the destination chat id, and navigates to that chat. The
 * chat screen reads the entry exactly once on first composition and clears
 * it, so a rotation or a return-trip to the chat can't re-prefill.
 *
 * **Why not a query-string blob?** Decision B in the phase doc: PNG bytes
 * exceed Android's URI length budget once base64-encoded, and stuffing them
 * through navigation args leaks the body into logcat. An in-memory store is
 * the lowest-friction alternative — process death loses the draft, which
 * the phase doc explicitly accepts.
 */
object PendingDraftStore {

    data class Entry(
        /** Image rendered by [NoteExporter]; `null` means text-only draft. */
        val imageUri: Uri?,
        /** Prefill for the composer; `null` means no text was supplied. */
        val draftText: String?,
    )

    private val pending = ConcurrentHashMap<String, Entry>()

    /**
     * Stash a draft for [chatId]. Overwrites any prior pending draft for the
     * same chat — last-write wins, matching the user's most recent intent if
     * they fire two sends in quick succession.
     */
    fun put(chatId: String, imageUri: Uri?, draftText: String?) {
        if (imageUri == null && draftText.isNullOrEmpty()) return
        pending[chatId] = Entry(imageUri, draftText)
    }

    /**
     * Take and remove the pending draft for [chatId]. Subsequent calls
     * return `null` until something is put again.
     */
    fun consume(chatId: String): Entry? = pending.remove(chatId)
}
