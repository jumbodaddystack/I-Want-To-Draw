package com.aichat.sandbox.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Save → reopen round-trip coverage for the note aggregate (note row + items
 * + layers). The original "notes lost on reopen" bug lived in the DAO's
 * transaction semantics — which codec-level JVM tests can't see — so these
 * exercise the real Room schema, including one test that closes a file-backed
 * database and reopens it cold, the closest an instrumented test gets to a
 * process restart.
 */
@RunWith(AndroidJUnit4::class)
class NoteRoundTripTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: NoteDao

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.noteDao()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(DISK_DB_NAME)
    }

    @Test
    fun saveNoteWithLayers_roundTripsEveryField() = runTest {
        val note = sampleNote()
        val layers = sampleLayers(note.id)
        val items = listOf(
            sampleItem(note.id, zIndex = 0, layerId = layers[0].id, payload = byteArrayOf(1, 2, 3)),
            sampleItem(note.id, zIndex = 1, layerId = layers[1].id, payload = byteArrayOf(4, 5)),
            // Null layerId is the legitimate "default layer" and must survive.
            sampleItem(note.id, zIndex = 2, layerId = null, payload = byteArrayOf(6)),
        )

        dao.saveNoteWithLayers(note, items, layers)

        val loadedNote = dao.getNote(note.id)
        assertNotNull(loadedNote)
        assertEquals(note, loadedNote)
        // NoteItem.equals is ByteArray-aware, so this covers payload bytes,
        // layerId, tool, color, width — the full stroke identity.
        assertEquals(items, dao.getItems(note.id))
        // getLayers orders by ordinal ASC; sampleLayers is built in that order.
        assertEquals(layers, dao.getLayers(note.id))
    }

    @Test
    fun saveNoteWithLayers_replacesItemsAndLayersAtomically() = runTest {
        val note = sampleNote()
        val staleLayers = sampleLayers(note.id)
        val staleItems = listOf(
            sampleItem(note.id, zIndex = 0, layerId = staleLayers[0].id),
            sampleItem(note.id, zIndex = 1, layerId = staleLayers[1].id),
        )
        dao.saveNoteWithLayers(note, staleItems, staleLayers)

        val freshLayer = sampleLayer(note.id, name = "Fresh", ordinal = 0)
        val freshItem = sampleItem(note.id, zIndex = 0, layerId = freshLayer.id)
        dao.saveNoteWithLayers(note, listOf(freshItem), listOf(freshLayer))

        // The contract is delete-then-upsert inside one transaction: nothing
        // from the first save may survive the second.
        assertEquals(listOf(freshItem), dao.getItems(note.id))
        assertEquals(listOf(freshLayer), dao.getLayers(note.id))
    }

    @Test
    fun saveNoteWithLayers_emptyListsClearChildrenButKeepNote() = runTest {
        val note = sampleNote()
        val layers = sampleLayers(note.id)
        dao.saveNoteWithLayers(note, listOf(sampleItem(note.id, zIndex = 0, layerId = layers[0].id)), layers)

        dao.saveNoteWithLayers(note, emptyList(), emptyList())

        assertNotNull(dao.getNote(note.id))
        assertTrue(dao.getItems(note.id).isEmpty())
        assertTrue(dao.getLayers(note.id).isEmpty())
    }

    @Test
    fun saveNoteWithLayers_orphanLayerReferenceSurvivesRoundTrip() = runTest {
        // note_items.layerId deliberately has no SQL FK to note_layers —
        // LayerLookup resolves orphans to the virtual default layer. This
        // pins down that an orphan reference neither fails the save nor gets
        // rewritten on the way through the DAO.
        val note = sampleNote()
        val orphan = sampleItem(note.id, zIndex = 0, layerId = "layer-that-does-not-exist")

        dao.saveNoteWithLayers(note, listOf(orphan), layers = emptyList())

        val loaded = dao.getItems(note.id).single()
        assertEquals("layer-that-does-not-exist", loaded.layerId)
        assertTrue(dao.getLayers(note.id).isEmpty())
    }

    @Test
    fun saveNote_withoutLayers_leavesExistingLayersUntouched() = runTest {
        // The legacy saveNote(note, items) overload must not clear layers a
        // prior saveNoteWithLayers persisted — only the layered save owns the
        // note_layers table.
        val note = sampleNote()
        val layers = sampleLayers(note.id)
        dao.saveNoteWithLayers(note, listOf(sampleItem(note.id, zIndex = 0, layerId = layers[0].id)), layers)

        dao.saveNote(note, listOf(sampleItem(note.id, zIndex = 0, layerId = layers[0].id)))

        assertEquals(layers, dao.getLayers(note.id))
    }

    @Test
    fun deleteNote_cascadesLayersAndItems() = runTest {
        val note = sampleNote()
        val layers = sampleLayers(note.id)
        dao.saveNoteWithLayers(note, listOf(sampleItem(note.id, zIndex = 0, layerId = layers[0].id)), layers)

        dao.deleteNote(note.id)

        assertNull(dao.getNote(note.id))
        assertTrue(dao.getItems(note.id).isEmpty())
        assertTrue(dao.getLayers(note.id).isEmpty())
    }

    @Test
    fun saveCloseReopen_roundTripsThroughDiskDatabase() = runTest {
        // File-backed database: save, close, reopen cold, read back. This is
        // the closest an instrumented test gets to "crash + relaunch" and it
        // exercises the real on-disk commit, not just the in-memory page cache.
        context.deleteDatabase(DISK_DB_NAME)
        val note = sampleNote(id = "disk-note")
        val layers = sampleLayers(note.id)
        val items = listOf(
            sampleItem(note.id, zIndex = 0, layerId = layers[0].id, payload = byteArrayOf(9, 8, 7)),
            sampleItem(note.id, zIndex = 1, layerId = null, payload = byteArrayOf(6, 5)),
        )

        val first = Room.databaseBuilder(context, AppDatabase::class.java, DISK_DB_NAME)
            .allowMainThreadQueries()
            .build()
        try {
            first.noteDao().saveNoteWithLayers(note, items, layers)
        } finally {
            first.close()
        }

        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, DISK_DB_NAME)
            .allowMainThreadQueries()
            .build()
        try {
            val freshDao = reopened.noteDao()
            assertEquals(note, freshDao.getNote(note.id))
            assertEquals(items, freshDao.getItems(note.id))
            assertEquals(layers, freshDao.getLayers(note.id))
        } finally {
            reopened.close()
        }
    }

    private fun sampleNote(id: String = "note-1") = Note(
        id = id,
        title = "Round-trip note",
        backgroundStyle = "dot",
        schemaVersion = 1,
        minX = -10f,
        minY = -20f,
        maxX = 100f,
        maxY = 200f,
        thumbnailPath = null,
        ocrText = "recognised text",
    )

    private fun sampleLayer(noteId: String, name: String, ordinal: Int) = NoteLayer(
        noteId = noteId,
        name = name,
        opacityPercent = 80,
        visible = true,
        locked = false,
        ordinal = ordinal,
    )

    private fun sampleLayers(noteId: String) = listOf(
        sampleLayer(noteId, name = "Background", ordinal = 0),
        sampleLayer(noteId, name = "Ink", ordinal = 1),
    )

    private fun sampleItem(
        noteId: String,
        zIndex: Int,
        layerId: String?,
        payload: ByteArray = byteArrayOf(1, 2, 3, 4),
    ) = NoteItem(
        noteId = noteId,
        zIndex = zIndex,
        kind = NoteItem.KIND_STROKE,
        tool = "pen",
        colorArgb = 0xFF112233.toInt(),
        baseWidthPx = 4f,
        payload = payload,
        layerId = layerId,
    )

    companion object {
        private const val DISK_DB_NAME = "note-round-trip-test.db"
    }
}
