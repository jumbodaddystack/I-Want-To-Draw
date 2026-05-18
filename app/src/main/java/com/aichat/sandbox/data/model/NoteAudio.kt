package com.aichat.sandbox.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Sub-phase 9.4 — recorded audio track that the editor replays in sync with
 * timestamped strokes.
 *
 * One note may own many audio rows. [recordingStartedAt] is captured from
 * `SystemClock.elapsedRealtime()` at record start; every stroke sample drawn
 * during the recording window stores `t = sampleTime - recordingStartedAt`
 * (stroke payload v2), letting the replayer filter and clip strokes against
 * the player's `currentPositionMs`.
 *
 * Strokes drawn outside any recording use stroke payload v1 (no `t`) and
 * render statically; the replayer ignores them.
 */
@Entity(
    tableName = "note_audio",
    foreignKeys = [
        ForeignKey(
            entity = Note::class,
            parentColumns = ["id"],
            childColumns = ["noteId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("noteId")],
)
data class NoteAudio(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val noteId: String,
    val filePath: String,
    val durationMs: Long,
    val recordedAt: Long = System.currentTimeMillis(),
    /**
     * Monotonic anchor (from `SystemClock.elapsedRealtime()` at record start).
     * Stroke samples timestamp themselves as `sampleTime - recordingStartedAt`
     * so a process kill mid-recording still leaves a self-consistent window.
     */
    val recordingStartedAt: Long,
)
