package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.Stamp

/**
 * Phase 17.5 follow-on — pure filtering for the stamp library's search + tag
 * chips, mirroring how the Icons gallery (17.1) combines a free-text query
 * with a tag filter. JVM-pure (no Android imports) so the match rules are
 * unit-tested and the drawer and any future surface share one implementation.
 *
 * Both filters are ANDed: an active [activeTag] chip narrows to stamps
 * carrying that tag, and a non-blank query then matches the stamp name OR any
 * of its tags (case-insensitive substring). Input order is preserved (the
 * caller supplies recency order).
 */
object StampSearch {

    fun filter(
        stamps: List<Stamp>,
        tagsByStamp: Map<String, List<String>>,
        query: String,
        activeTag: String? = null,
    ): List<Stamp> {
        val tag = activeTag?.let { IconTags.normalize(it) }?.takeIf { it.isNotEmpty() }
        val q = query.trim().lowercase()
        if (tag == null && q.isEmpty()) return stamps
        return stamps.filter { stamp ->
            val tags = tagsByStamp[stamp.id].orEmpty()
            if (tag != null && tag !in tags) return@filter false
            if (q.isEmpty()) return@filter true
            stamp.name.lowercase().contains(q) || tags.any { it.contains(q) }
        }
    }

    /** Fold a flat (stampId, tag) list into a `stampId → tags` lookup. */
    fun groupTags(rows: List<Pair<String, String>>): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for ((stampId, tag) in rows) out.getOrPut(stampId) { ArrayList() }.add(tag)
        return out
    }
}
