package com.aichat.sandbox.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aichat.sandbox.data.model.BrushPreset
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteAudio
import com.aichat.sandbox.data.model.NoteFrame
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import com.aichat.sandbox.data.model.NoteTag
import com.aichat.sandbox.data.model.Notebook
import com.aichat.sandbox.data.model.Stamp
import com.aichat.sandbox.data.model.StampTag
import com.aichat.sandbox.data.model.UserTemplate

// Note: `notes_ocr_fts` (Phase 9.3) is intentionally NOT a registered
// entity. It's a virtual FTS4 table managed entirely by raw SQL — see
// `createNotesSearchIndex` in Migrations.kt (run from `MIGRATION_17_18` on
// upgrade and from AppModule's database `onCreate` callback on fresh
// installs) — and queried through `NoteSearchDao`. Decoupling from Room's
// `@Fts4(contentEntity = ...)` mechanism avoids trigger-name collisions
// with the explicit sync triggers we install.
@Database(
    entities = [
        Note::class,
        NoteItem::class,
        NoteLayer::class,
        NoteTag::class,
        BrushPreset::class,
        NoteFrame::class,
        Stamp::class,
        StampTag::class,
        UserTemplate::class,
        Notebook::class,
        NoteAudio::class,
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao
    abstract fun noteTagDao(): NoteTagDao
    abstract fun brushPresetDao(): BrushPresetDao
    abstract fun noteFrameDao(): NoteFrameDao
    abstract fun stampDao(): StampDao
    abstract fun stampTagDao(): StampTagDao
    abstract fun userTemplateDao(): UserTemplateDao
    abstract fun notebookDao(): NotebookDao
    abstract fun noteSearchDao(): NoteSearchDao
    abstract fun noteAudioDao(): NoteAudioDao
}
