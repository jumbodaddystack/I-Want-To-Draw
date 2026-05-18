package com.aichat.sandbox.data.repository

import com.aichat.sandbox.data.local.NoteAudioDao
import com.aichat.sandbox.data.model.NoteAudio
import com.aichat.sandbox.data.notes.AudioRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sub-phase 9.4 — persistence for the audio table.
 *
 * Also owns the launch-time orphan sweep: any `.m4a` in the audio
 * directory that isn't referenced by a `note_audio` row is purged so a
 * crash mid-recording can't accrete dead files on disk.
 */
@Singleton
class NoteAudioRepository @Inject constructor(
    private val dao: NoteAudioDao,
    private val recorder: AudioRecorder,
) {

    fun observeAudio(noteId: String): Flow<List<NoteAudio>> = dao.observeAudio(noteId)

    suspend fun getAudio(noteId: String): List<NoteAudio> = withContext(Dispatchers.IO) {
        dao.getAudio(noteId)
    }

    suspend fun insertAudio(audio: NoteAudio) = withContext(Dispatchers.IO) {
        dao.upsert(audio)
    }

    suspend fun deleteAudio(audioId: String) = withContext(Dispatchers.IO) {
        val row = dao.getAudioById(audioId) ?: return@withContext
        File(row.filePath).delete()
        dao.delete(audioId)
    }

    /**
     * One-shot purge of `.m4a` files without a matching row. Safe to run
     * on app start; the I/O is bounded by the audio directory size.
     */
    suspend fun sweepOrphans() = withContext(Dispatchers.IO) {
        val known = dao.getAll().map { it.filePath }.toHashSet()
        recorder.sweepOrphans(known)
    }
}
