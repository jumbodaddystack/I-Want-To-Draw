package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.model.NoteItem
import java.util.UUID

/**
 * Phase 10.4 — flat, single-level item grouping helpers.
 *
 * Groups are a `groupId` tag on [NoteItem]; all group semantics reduce to
 * "selecting one member selects them all". Expansion happens at selection
 * time (the lasso commit), which keeps every downstream action — transform,
 * duplicate, delete, copy — group-agnostic.
 *
 * Pure functions so the policy is unit-testable without the ViewModel.
 */

/**
 * Expand [matchedIds] to include every item sharing a groupId with a match.
 * [selectable] gates which items may join (the caller passes its locked-layer
 * filter so a group member on a locked layer stays inert, mirroring the
 * lasso's own rule).
 */
fun expandSelectionToGroups(
    matchedIds: Set<String>,
    items: List<NoteItem>,
    selectable: (NoteItem) -> Boolean = { true },
): Set<String> {
    if (matchedIds.isEmpty()) return matchedIds
    val groupIds = HashSet<String>()
    for (item in items) {
        if (item.id in matchedIds) item.groupId?.let { groupIds.add(it) }
    }
    if (groupIds.isEmpty()) return matchedIds
    val expanded = HashSet(matchedIds)
    for (item in items) {
        if (item.groupId in groupIds && selectable(item)) expanded.add(item.id)
    }
    return expanded
}

/**
 * Re-key the groupIds of freshly duplicated / pasted items: members of the
 * same source group stay grouped with each other, but under a fresh UUID so
 * the copies are independent of the originals.
 */
fun remapGroupIds(copies: List<NoteItem>): List<NoteItem> {
    if (copies.none { it.groupId != null }) return copies
    val remap = HashMap<String, String>()
    return copies.map { item ->
        val source = item.groupId ?: return@map item
        val fresh = remap.getOrPut(source) { UUID.randomUUID().toString() }
        item.copy(groupId = fresh)
    }
}
