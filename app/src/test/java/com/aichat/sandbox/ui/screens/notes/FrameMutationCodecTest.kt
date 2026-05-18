package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.model.NoteFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sub-phase 8.1 — `FrameMutation` lands on the undo log and round-trips
 * through [EditorActionCodec] alongside item actions.
 */
class FrameMutationCodecTest {

    @Test
    fun `frame mutation round-trips`() {
        val before = listOf(
            NoteFrame(
                id = "f1",
                noteId = "n1",
                name = "Frame 1",
                minX = 0f, minY = 0f, maxX = 100f, maxY = 200f,
                ordinal = 0,
                createdAt = 1234L,
            ),
        )
        val after = before + NoteFrame(
            id = "f2",
            noteId = "n1",
            name = "Frame 2",
            minX = 100f, minY = 100f, maxX = 300f, maxY = 300f,
            ordinal = 1,
            createdAt = 5678L,
        )
        val action = EditorAction.FrameMutation("Add frame", before = before, after = after)

        val json = EditorActionCodec.encode(past = listOf(action), future = emptyList())
        assertTrue(json != null && json.isNotEmpty())
        val decoded = EditorActionCodec.decode(json)

        assertEquals(1, decoded.past.size)
        val recovered = decoded.past[0] as EditorAction.FrameMutation
        assertEquals("Add frame", recovered.description)
        assertEquals(after.size, recovered.after.size)
        assertEquals("Frame 2", recovered.after[1].name)
        assertEquals(5678L, recovered.after[1].createdAt)
    }

    @Test
    fun `invert swaps before and after`() {
        val before = listOf(
            NoteFrame(
                id = "f1", noteId = "n1", name = "A",
                minX = 0f, minY = 0f, maxX = 10f, maxY = 10f,
                ordinal = 0, createdAt = 0L,
            ),
        )
        val after = emptyList<NoteFrame>()
        val mutation = EditorAction.FrameMutation("Delete", before, after)
        val inverse = mutation.invert() as EditorAction.FrameMutation
        assertEquals(after, inverse.before)
        assertEquals(before, inverse.after)
    }
}
