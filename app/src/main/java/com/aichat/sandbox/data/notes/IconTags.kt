package com.aichat.sandbox.data.notes

/**
 * Phase 17.1 — pure tag-set logic for icon tagging. JVM-pure (no Android
 * imports) so the parsing/normalization rules are unit-testable; the dialog
 * and DAO both go through here so the stored form and the displayed form
 * can never drift.
 *
 * Stored form: trimmed, lowercased, inner whitespace collapsed to single
 * spaces. Equality on the stored form is what the chip filter and the
 * per-tag count query group by.
 */
object IconTags {

    /** Title suffix applied by "Duplicate as variant" (filled → outlined sibling). */
    const val VARIANT_TITLE_SUFFIX = " — outlined"

    /** Cap per tag — keeps chips renderable; longer input is dropped, not clipped. */
    const val MAX_TAG_LENGTH = 40

    /** Cap per note — a tag *set*, not free-form keywords. */
    const val MAX_TAGS_PER_NOTE = 16

    /**
     * Normalize one raw tag to its stored form. Returns the empty string
     * when nothing usable remains (blank, or longer than [MAX_TAG_LENGTH]
     * after collapsing).
     */
    fun normalize(raw: String): String {
        val collapsed = raw.trim()
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return if (collapsed.length > MAX_TAG_LENGTH) "" else collapsed
    }

    /**
     * Parse free-form dialog input ("Nav, settings; filled") into the
     * normalized, de-duplicated tag set, preserving first-seen order.
     * Separators: comma, semicolon, newline.
     */
    fun parse(input: String): List<String> =
        input.split(',', ';', '\n')
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_TAGS_PER_NOTE)

    /** Inverse of [parse] for pre-filling the edit dialog. */
    fun format(tags: List<String>): String = tags.joinToString(", ")

    /**
     * Title for a "duplicate as variant" copy: blank titles get a stand-in
     * before the suffix so the copy never renders as just "— outlined".
     */
    fun variantTitle(title: String): String =
        title.ifBlank { "Untitled" } + VARIANT_TITLE_SUFFIX
}
