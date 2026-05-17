package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.model.NoteItem

/**
 * Cross-note clipboard for lasso copy / cut / paste (sub-phase 1.8).
 *
 * Process-scoped singleton — items survive navigating between notes but are
 * lost on app death. Persisting the clipboard across launches is explicitly
 * out of scope for v1 (see parent plan's "Explicit non-goals" for 1.8).
 *
 * Pastes re-ID every item and reassign `noteId` at the call site so the same
 * payload can be dropped onto multiple notes without colliding with the
 * source's primary keys.
 */
object NoteClipboard {

    private val items: MutableList<NoteItem> = mutableListOf()

    fun put(items: List<NoteItem>) {
        this.items.clear()
        this.items.addAll(items)
    }

    fun peek(): List<NoteItem> = items.toList()

    fun isEmpty(): Boolean = items.isEmpty()

    fun clear() {
        items.clear()
    }
}
