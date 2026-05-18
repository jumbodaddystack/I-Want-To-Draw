package com.aichat.sandbox.ui.screens.notes

import com.aichat.sandbox.data.model.NoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorActionCodecTest {

    private fun stroke(id: String, body: String = "abc"): NoteItem = NoteItem(
        id = id,
        noteId = "note-1",
        zIndex = 7,
        kind = "stroke",
        tool = "pen",
        colorArgb = 0xFF112233.toInt(),
        baseWidthPx = 3.5f,
        payload = body.toByteArray(Charsets.UTF_8),
    )

    private fun text(id: String): NoteItem = NoteItem(
        id = id,
        noteId = "note-1",
        zIndex = 4,
        kind = "text",
        tool = null,
        colorArgb = 0xFF000000.toInt(),
        baseWidthPx = 0f,
        payload = byteArrayOf(0x01, 0x02, 0x03, 0x04),
    )

    @Test
    fun nullStacksEncodeAsNull() {
        assertNull(EditorActionCodec.encode(emptyList(), emptyList()))
    }

    @Test
    fun emptyOrNullJsonDecodesEmpty() {
        assertEquals(EditorActionCodec.Decoded.EMPTY, EditorActionCodec.decode(null))
        assertEquals(EditorActionCodec.Decoded.EMPTY, EditorActionCodec.decode(""))
        assertEquals(EditorActionCodec.Decoded.EMPTY, EditorActionCodec.decode("   "))
    }

    @Test
    fun malformedJsonDecodesEmpty() {
        assertEquals(EditorActionCodec.Decoded.EMPTY, EditorActionCodec.decode("{not json"))
        assertEquals(
            EditorActionCodec.Decoded.EMPTY,
            EditorActionCodec.decode("[]"), // wrong shape — codec expects object
        )
    }

    @Test
    fun unknownSchemaDecodesEmpty() {
        val raw = """{"schema":999,"past":[],"future":[]}"""
        assertEquals(EditorActionCodec.Decoded.EMPTY, EditorActionCodec.decode(raw))
    }

    @Test
    fun addItemsRoundTrips() {
        val a = EditorAction.AddItems(listOf(stroke("a"), text("b")))
        val json = EditorActionCodec.encode(listOf(a), emptyList())
        assertNotNull(json)
        val decoded = EditorActionCodec.decode(json)
        assertEquals(1, decoded.past.size)
        assertEquals(0, decoded.future.size)
        assertEquals(a, decoded.past[0])
    }

    @Test
    fun removeItemsRoundTrips() {
        val r = EditorAction.RemoveItems(listOf(stroke("a", body = "long-body-bytes")))
        val json = EditorActionCodec.encode(emptyList(), listOf(r))
        val decoded = EditorActionCodec.decode(json)
        assertEquals(0, decoded.past.size)
        assertEquals(1, decoded.future.size)
        assertEquals(r, decoded.future[0])
    }

    @Test
    fun transformItemsRoundTripsMatrix() {
        val m = floatArrayOf(2f, 0f, 10f, 0f, 2f, 20f, 0f, 0f, 1f)
        val t = EditorAction.TransformItems(listOf("a", "b"), m)
        val json = EditorActionCodec.encode(listOf(t), emptyList())
        val decoded = EditorActionCodec.decode(json)
        assertEquals(1, decoded.past.size)
        val out = decoded.past[0] as EditorAction.TransformItems
        assertEquals(listOf("a", "b"), out.ids)
        assertTrue(m.contentEquals(out.matrix))
    }

    @Test
    fun updateTextRoundTripsBodies() {
        val u = EditorAction.UpdateText("id-1", "hello", "hello, world")
        val json = EditorActionCodec.encode(listOf(u), emptyList())
        val decoded = EditorActionCodec.decode(json)
        assertEquals(u, decoded.past[0])
    }

    @Test
    fun mixedStacksPreserveOrder() {
        val past = listOf(
            EditorAction.AddItems(listOf(stroke("a1"))),
            EditorAction.AddItems(listOf(stroke("a2"))),
            EditorAction.RemoveItems(listOf(stroke("a1"))),
        )
        val future = listOf(
            EditorAction.UpdateText("t1", "x", "y"),
        )
        val json = EditorActionCodec.encode(past, future)
        val decoded = EditorActionCodec.decode(json)
        assertEquals(past, decoded.past)
        assertEquals(future, decoded.future)
    }

    @Test
    fun unknownActionTypesAreSkippedNotFatal() {
        val raw = """
            {
                "schema": 1,
                "past": [
                    {"type":"AddItems","items":[]},
                    {"type":"FutureAction","payload":42},
                    {"type":"UpdateText","id":"x","oldBody":"a","newBody":"b"}
                ],
                "future": []
            }
        """.trimIndent()
        val decoded = EditorActionCodec.decode(raw)
        assertEquals(2, decoded.past.size)
        assertTrue(decoded.past[0] is EditorAction.AddItems)
        assertTrue(decoded.past[1] is EditorAction.UpdateText)
    }

    @Test
    fun encodingRespectsSizeCapByEvictingFromPast() {
        // Synthesize a beefy AddItems list so a single repeat exceeds the cap.
        val bigPayload = ByteArray(8_000) { (it % 251).toByte() }
        val many = (0 until 200).map {
            EditorAction.AddItems(
                listOf(
                    NoteItem(
                        id = "id-$it",
                        noteId = "note-1",
                        zIndex = it,
                        kind = "stroke",
                        tool = "pen",
                        colorArgb = -1,
                        baseWidthPx = 4f,
                        payload = bigPayload,
                    )
                )
            )
        }
        val json = EditorActionCodec.encode(many, emptyList())
        assertNotNull(json)
        val size = json!!.toByteArray(Charsets.UTF_8).size
        assertTrue(
            "encoded size $size should fit under ${EditorActionCodec.MAX_BYTES}",
            size <= EditorActionCodec.MAX_BYTES,
        )
        val decoded = EditorActionCodec.decode(json)
        assertTrue(
            "should have evicted some past entries (kept ${decoded.past.size})",
            decoded.past.size < many.size,
        )
        // FIFO: the most recent entries should survive.
        val survivor = decoded.past.last() as EditorAction.AddItems
        assertEquals("id-199", survivor.items[0].id)
    }

    @Test
    fun fiveHundredSmallAddItemsFitsUnderCap() {
        // Defensive: typical sessions stay well under the cap even with
        // 500 small actions.
        val many = (0 until 500).map {
            EditorAction.AddItems(listOf(stroke("s-$it", body = "tiny")))
        }
        val json = EditorActionCodec.encode(many, emptyList())
        assertNotNull(json)
        val decoded = EditorActionCodec.decode(json)
        // With small payloads we expect no eviction.
        assertEquals(500, decoded.past.size)
    }
}
