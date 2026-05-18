package com.aichat.sandbox.data.notes

import com.aichat.sandbox.data.model.NoteItem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StampPayloadCodecTest {

    @Test
    fun `round-trip preserves payload bytes`() {
        val items = listOf(
            NoteItem(
                id = "abc",
                noteId = "note-1",
                zIndex = 3,
                kind = "stroke",
                tool = "pen",
                colorArgb = 0xFF112233.toInt(),
                baseWidthPx = 4.5f,
                payload = byteArrayOf(1, 2, 3, 4, 5),
                layerId = "layer-1",
            ),
            NoteItem(
                id = "xyz",
                noteId = "note-1",
                zIndex = 1,
                kind = "shape",
                tool = null,
                colorArgb = 0,
                baseWidthPx = 2f,
                payload = byteArrayOf(7, 7, 7),
            ),
        )
        val bounds = floatArrayOf(10f, 20f, 100f, 200f)

        val json = StampPayloadCodec.encode(items, bounds)
        val parsed = StampPayloadCodec.parse(json)

        assertNotNull("encode/decode should round-trip", parsed)
        assertArrayEquals(bounds, parsed!!.bounds, 0f)
        assertEquals(2, parsed.items.size)
        val first = parsed.items[0]
        assertEquals("abc", first.id)
        assertEquals("pen", first.tool)
        assertEquals(3, first.zIndex)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), first.payload)
        // noteId is intentionally reset on parse so callers must reparent.
        assertEquals("", first.noteId)
    }

    @Test
    fun `parse returns null on malformed json`() {
        assertNull(StampPayloadCodec.parse(""))
        assertNull(StampPayloadCodec.parse("{}"))
        assertNull(StampPayloadCodec.parse("not even json"))
    }

    @Test
    fun `parse rejects future schema versions`() {
        val payload = """{"schema":99,"bounds":[0,0,1,1],"items":[]}"""
        assertNull(StampPayloadCodec.parse(payload))
    }
}
