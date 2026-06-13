package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.Stamp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 17.5 follow-on — stamp-library search + tag filtering.
 */
class StampSearchTest {

    private fun stamp(id: String, name: String) =
        Stamp(id = id, name = name, thumbnailPath = "", payloadJson = "{}")

    private val arrow = stamp("a", "Arrow")
    private val gear = stamp("g", "Settings Gear")
    private val star = stamp("s", "Star")
    private val all = listOf(arrow, gear, star)
    private val tags = mapOf(
        "a" to listOf("nav", "ui"),
        "g" to listOf("ui", "config"),
        // star has no tags
    )

    @Test
    fun blankQueryNoTagReturnsEverything() {
        assertEquals(all, StampSearch.filter(all, tags, query = "", activeTag = null))
    }

    @Test
    fun queryMatchesNameCaseInsensitive() {
        assertEquals(listOf(gear), StampSearch.filter(all, tags, query = "gear", activeTag = null))
    }

    @Test
    fun queryMatchesTag() {
        // "config" is only a tag on the gear, not in any name.
        assertEquals(listOf(gear), StampSearch.filter(all, tags, query = "config", activeTag = null))
    }

    @Test
    fun activeTagNarrowsToCarriers() {
        assertEquals(listOf(arrow, gear), StampSearch.filter(all, tags, query = "", activeTag = "ui"))
    }

    @Test
    fun tagAndQueryAreAnded() {
        // tag=ui keeps {arrow, gear}; query "gear" then narrows to gear.
        assertEquals(listOf(gear), StampSearch.filter(all, tags, query = "gear", activeTag = "ui"))
        // tag=ui + query "star" → nothing (star isn't tagged ui).
        assertEquals(emptyList<Stamp>(), StampSearch.filter(all, tags, query = "star", activeTag = "ui"))
    }

    @Test
    fun activeTagIsNormalizedBeforeMatching() {
        // Mixed-case / padded chip value still matches the stored lowercase tag.
        assertEquals(listOf(arrow, gear), StampSearch.filter(all, tags, query = "", activeTag = "  UI "))
    }

    @Test
    fun preservesInputOrder() {
        val reversed = listOf(star, gear, arrow)
        assertEquals(listOf(gear, arrow), StampSearch.filter(reversed, tags, query = "", activeTag = "ui"))
    }

    @Test
    fun groupTagsFoldsRowsByStamp() {
        val grouped = StampSearch.groupTags(listOf("a" to "nav", "a" to "ui", "g" to "config"))
        assertEquals(listOf("nav", "ui"), grouped["a"])
        assertEquals(listOf("config"), grouped["g"])
    }
}
