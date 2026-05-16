package com.aichat.sandbox.data.repository

import com.aichat.sandbox.data.local.NoteDao
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoteRepository @Inject constructor(
    private val noteDao: NoteDao,
) {
    fun observeNotes(): Flow<List<Note>> = noteDao.observeNotes()

    suspend fun getNote(noteId: String): Note? = withContext(Dispatchers.IO) {
        noteDao.getNote(noteId)
    }

    suspend fun getItems(noteId: String): List<NoteItem> = withContext(Dispatchers.IO) {
        noteDao.getItems(noteId)
    }

    suspend fun saveNote(note: Note, items: List<NoteItem>) = withContext(Dispatchers.IO) {
        noteDao.saveNote(note, items)
    }

    suspend fun deleteNote(noteId: String) = withContext(Dispatchers.IO) {
        noteDao.deleteNote(noteId)
    }
}
