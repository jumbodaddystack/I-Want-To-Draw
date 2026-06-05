package com.aichat.sandbox.data.vector.notesbridge

import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.vector.VectorViewport
import com.aichat.sandbox.data.vector.allPaths
import com.aichat.sandbox.ui.components.notes.Shape
import com.aichat.sandbox.ui.components.notes.ShapeCodec
import com.aichat.sandbox.ui.components.notes.StrokeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteVectorBridgeTest {

    private val viewport = VectorViewport(100f, 100f, 100f, 100f)

    private fun stroke(id: String, z: Int): NoteItem {
        val arr = floatArrayOf(0f, 0f, 1f, 0f, 10f, 5f, 1f, 0f, 20f, 0f, 1f, 0f)
        return NoteItem(id, "n", z, NoteItem.KIND_STROKE, "pen", 0xFF000000.toInt(), 3f, StrokeCodec.encode(arr))
    }

    private fun shape(id: String, z: Int): NoteItem =
        NoteItem(id, "n", z, NoteItem.KIND_SHAPE, null, 0xFF000000.toInt(), 2f,
            ShapeCodec.encode(Shape.Rect(0f, 0f, 10f, 10f)))

    private fun text(id: String, z: Int): NoteItem =
        NoteItem(id, "n", z, NoteItem.KIND_TEXT, null, 0xFF000000.toInt(), 1f, ByteArray(4))

    private fun image(id: String, z: Int): NoteItem =
        NoteItem(id, "n", z, NoteItem.KIND_IMAGE, null, 0xFF000000.toInt(), 1f, ByteArray(4))

    @Test
    fun zOrderPreservedAsTreeOrder() {
        // Deliberately out of order; bridge must sort by zIndex.
        val items = listOf(
            shape("b", z = 2),
            stroke("a", z = 1),
            text("t", z = 3),
            image("i", z = 4),
        )
        val result = NoteVectorBridge.toDocument(items, viewport)
        val paths = result.document.allPaths()
        assertEquals(listOf("note_a", "note_b"), paths.map { it.id })
        assertEquals("note_a", result.itemToPathId["a"])
        assertEquals("note_b", result.itemToPathId["b"])
        assertTrue(result.skipped.containsAll(listOf("t", "i")))
        assertEquals(2, result.itemToPathId.size)
    }

    @Test
    fun emptyAndDegenerateStrokesSkippedNotCrash() {
        val emptyStroke = NoteItem("e", "n", 0, NoteItem.KIND_STROKE, "pen", 0xFF000000.toInt(), 3f, ByteArray(0))
        val onePoint = NoteItem("p", "n", 1, NoteItem.KIND_STROKE, "pen", 0xFF000000.toInt(), 3f,
            StrokeCodec.encode(floatArrayOf(5f, 5f, 1f, 0f)))
        val good = stroke("g", 2)
        val result = NoteVectorBridge.toDocument(listOf(emptyStroke, onePoint, good), viewport)
        assertEquals(listOf("note_g"), result.document.allPaths().map { it.id })
        assertTrue(result.skipped.containsAll(listOf("e", "p")))
    }

    @Test
    fun viewportCarriedThrough() {
        val result = NoteVectorBridge.toDocument(listOf(stroke("a", 0)), VectorViewport(48f, 48f, 48f, 48f))
        assertEquals(48f, result.document.viewport.viewportWidth, 0f)
    }
}
