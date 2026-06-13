package com.aichat.sandbox.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 17.1 — tag-set logic. The dialog input and the stored form both go
 * through [IconTags], so these tests pin the normalization contract the
 * chip-filter and per-tag count queries rely on (plain string equality).
 */
class IconTagsTest {

    // ── normalize ────────────────────────────────────────────────────────

    @Test
    fun `normalize trims lowercases and collapses inner whitespace`() {
        assertEquals("nav bar", IconTags.normalize("  Nav   Bar "))
        assertEquals("settings", IconTags.normalize("SETTINGS"))
    }

    @Test
    fun `normalize of blank input is empty`() {
        assertEquals("", IconTags.normalize(""))
        assertEquals("", IconTags.normalize("   \t "))
    }

    @Test
    fun `normalize drops overlong tags instead of clipping`() {
        val overlong = "x".repeat(IconTags.MAX_TAG_LENGTH + 1)
        assertEquals("", IconTags.normalize(overlong))
        // Exactly at the cap survives.
        val atCap = "x".repeat(IconTags.MAX_TAG_LENGTH)
        assertEquals(atCap, IconTags.normalize(atCap))
    }

    // ── parse ────────────────────────────────────────────────────────────

    @Test
    fun `parse splits on comma semicolon and newline`() {
        assertEquals(
            listOf("nav", "settings", "filled"),
            IconTags.parse("nav, settings; filled"),
        )
        assertEquals(
            listOf("a", "b"),
            IconTags.parse("a\nb"),
        )
    }

    @Test
    fun `parse dedupes case-insensitively preserving first-seen order`() {
        assertEquals(
            listOf("nav", "settings"),
            IconTags.parse("Nav, settings, NAV, nav"),
        )
    }

    @Test
    fun `parse drops blank segments`() {
        assertEquals(listOf("a", "b"), IconTags.parse(",a,, ,b,"))
        assertTrue(IconTags.parse("").isEmpty())
        assertTrue(IconTags.parse(" , ; \n ").isEmpty())
    }

    @Test
    fun `parse caps the tag set size`() {
        val input = (1..IconTags.MAX_TAGS_PER_NOTE + 5).joinToString(",") { "tag$it" }
        assertEquals(IconTags.MAX_TAGS_PER_NOTE, IconTags.parse(input).size)
    }

    // ── format round-trip ────────────────────────────────────────────────

    @Test
    fun `format then parse round-trips a normalized tag set`() {
        val tags = listOf("nav", "settings", "two words")
        assertEquals(tags, IconTags.parse(IconTags.format(tags)))
    }

    @Test
    fun `format of empty set is empty string`() {
        assertEquals("", IconTags.format(emptyList()))
    }

    // ── variantTitle ─────────────────────────────────────────────────────

    @Test
    fun `variantTitle appends the outlined suffix`() {
        assertEquals("Home — outlined", IconTags.variantTitle("Home"))
    }

    @Test
    fun `variantTitle substitutes Untitled for blank titles`() {
        assertEquals("Untitled — outlined", IconTags.variantTitle(""))
        assertEquals("Untitled — outlined", IconTags.variantTitle("   "))
    }
}
