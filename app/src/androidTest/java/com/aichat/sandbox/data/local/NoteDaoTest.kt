package com.aichat.sandbox.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: NoteDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.noteDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun saveNote_insertsNoteAndItems() = runTest {
        val note = sampleNote()
        val items = listOf(
            sampleItem(note.id, zIndex = 0),
            sampleItem(note.id, zIndex = 1),
            sampleItem(note.id, zIndex = 2),
        )

        dao.saveNote(note, items)

        val loaded = dao.getNote(note.id)
        assertNotNull(loaded)
        assertEquals(note.id, loaded?.id)

        val loadedItems = dao.getItems(note.id)
        assertEquals(3, loadedItems.size)
        assertEquals(listOf(0, 1, 2), loadedItems.map { it.zIndex })
    }

    @Test
    fun observeNotes_emitsOrderedByUpdatedAtDesc() = runTest {
        val older = sampleNote(id = "older").copy(updatedAt = 1_000L)
        val newer = sampleNote(id = "newer").copy(updatedAt = 2_000L)
        dao.saveNote(older, emptyList())
        dao.saveNote(newer, emptyList())

        val notes = dao.observeNotes().first()
        assertEquals(listOf("newer", "older"), notes.map { it.id })
    }

    @Test
    fun deleteNote_cascadesItems() = runTest {
        val note = sampleNote()
        val items = listOf(
            sampleItem(note.id, zIndex = 0),
            sampleItem(note.id, zIndex = 1),
            sampleItem(note.id, zIndex = 2),
        )
        dao.saveNote(note, items)

        dao.deleteNote(note.id)

        assertNull(dao.getNote(note.id))
        assertTrue(dao.getItems(note.id).isEmpty())
    }

    @Test
    fun saveNote_replacesItemSetAtomically() = runTest {
        val note = sampleNote()
        dao.saveNote(
            note,
            listOf(
                sampleItem(note.id, zIndex = 0),
                sampleItem(note.id, zIndex = 1),
            )
        )

        val replacement = listOf(sampleItem(note.id, zIndex = 0))
        dao.saveNote(note, replacement)

        val loaded = dao.getItems(note.id)
        assertEquals(1, loaded.size)
        assertEquals(replacement.single().id, loaded.single().id)
    }

    private fun sampleNote(id: String = "note-1") = Note(
        id = id,
        title = "Test note",
        backgroundStyle = "plain",
        schemaVersion = 1,
        minX = 0f,
        minY = 0f,
        maxX = 0f,
        maxY = 0f,
        thumbnailPath = null,
        ocrText = null,
    )

    private fun sampleItem(noteId: String, zIndex: Int) = NoteItem(
        noteId = noteId,
        zIndex = zIndex,
        kind = "stroke",
        tool = "pen",
        colorArgb = 0xFF000000.toInt(),
        baseWidthPx = 4f,
        payload = byteArrayOf(1, 2, 3, 4),
    )
}
