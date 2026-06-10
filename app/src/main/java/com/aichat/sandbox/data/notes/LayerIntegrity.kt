package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer

/**
 * Save-time integrity checks for the note → layers → items aggregate.
 *
 * `note_items.layerId` carries no SQL foreign key to `note_layers` — orphan
 * references are tolerated at render time ([LayerLookup] resolves them to the
 * virtual default layer) — so nothing in the schema catches drift between the
 * in-memory editor state and what lands in the database. These checks run on
 * the save path and surface that drift early instead of letting it accumulate
 * silently.
 *
 * Pure (no Android dependencies) so it stays testable in plain JVM unit tests;
 * callers decide what to do with the findings (the repository logs them).
 */
object LayerIntegrity {

    /**
     * Validate one save payload. Returns a human-readable description per
     * problem found, or an empty list when the aggregate is consistent.
     *
     * Checks:
     *  - every layer belongs to [note]
     *  - every item belongs to [note]
     *  - every non-null `item.layerId` references a layer in [layers]
     *    (`null` is the legitimate "default layer" and is never flagged)
     */
    fun findDrift(
        note: Note,
        items: List<NoteItem>,
        layers: List<NoteLayer>,
    ): List<String> {
        val problems = ArrayList<String>()
        val layerIds = layers.mapTo(HashSet()) { it.id }
        for (layer in layers) {
            if (layer.noteId != note.id) {
                problems += "layer ${layer.id} (\"${layer.name}\") belongs to " +
                    "note ${layer.noteId}, expected ${note.id}"
            }
        }
        for (item in items) {
            if (item.noteId != note.id) {
                problems += "item ${item.id} belongs to note ${item.noteId}, " +
                    "expected ${note.id}"
            }
            val ref = item.layerId
            if (ref != null && ref !in layerIds) {
                problems += "item ${item.id} references missing layer $ref"
            }
        }
        return problems
    }
}
