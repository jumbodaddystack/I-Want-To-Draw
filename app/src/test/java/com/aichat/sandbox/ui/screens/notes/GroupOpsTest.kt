package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.model.NoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GroupOpsTest {

    private fun item(id: String, groupId: String? = null) = NoteItem(
        id = id,
        noteId = "note-1",
        zIndex = 0,
        kind = NoteItem.KIND_STROKE,
        tool = "pen",
        colorArgb = 0xFF000000.toInt(),
        baseWidthPx = 4f,
        payload = ByteArray(0),
        groupId = groupId,
    )

    @Test
    fun matchingOneMemberSelectsWholeGroup() {
        val items = listOf(
            item("a", groupId = "g1"),
            item("b", groupId = "g1"),
            item("c", groupId = "g2"),
            item("d"),
        )
        val expanded = expandSelectionToGroups(setOf("a"), items)
        assertEquals(setOf("a", "b"), expanded)
    }

    @Test
    fun ungroupedMatchesPassThrough() {
        val items = listOf(item("a"), item("b"))
        val matched = setOf("a")
        assertSame(matched, expandSelectionToGroups(matched, items))
    }

    @Test
    fun selectableFilterKeepsLockedMembersOut() {
        val items = listOf(
            item("a", groupId = "g1"),
            item("locked", groupId = "g1"),
        )
        val expanded = expandSelectionToGroups(setOf("a"), items) { it.id != "locked" }
        assertEquals(setOf("a"), expanded)
    }

    @Test
    fun multipleGroupsExpandTogether() {
        val items = listOf(
            item("a", groupId = "g1"),
            item("b", groupId = "g1"),
            item("c", groupId = "g2"),
            item("d", groupId = "g2"),
            item("e"),
        )
        val expanded = expandSelectionToGroups(setOf("a", "c", "e"), items)
        assertEquals(setOf("a", "b", "c", "d", "e"), expanded)
    }

    @Test
    fun remapKeepsCopiesGroupedUnderFreshIds() {
        val copies = listOf(
            item("a2", groupId = "g1"),
            item("b2", groupId = "g1"),
            item("c2", groupId = "g2"),
            item("d2"),
        )
        val remapped = remapGroupIds(copies)
        val g1a = remapped[0].groupId
        val g1b = remapped[1].groupId
        val g2 = remapped[2].groupId
        assertNotNull(g1a)
        assertEquals(g1a, g1b)
        assertNotNull(g2)
        assertNotEquals(g1a, g2)
        // Fresh ids — copies must not rejoin the source groups.
        assertNotEquals("g1", g1a)
        assertNotEquals("g2", g2)
        assertNull(remapped[3].groupId)
    }

    @Test
    fun remapWithoutGroupsReturnsSameList() {
        val copies = listOf(item("a2"), item("b2"))
        assertSame(copies, remapGroupIds(copies))
    }
}
