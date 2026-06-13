package com.aichat.sandbox.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aichat.sandbox.data.model.BrushPreset
import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.model.MessageFts
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
import com.aichat.sandbox.data.model.VectorSymbolEntity
import com.aichat.sandbox.data.model.VectorTuneupProjectEntity
import com.aichat.sandbox.data.model.VectorTuneupVersionEntity

// Note: `notes_ocr_fts` (Phase 9.3) is intentionally NOT a registered
// entity. It's a virtual FTS4 table managed entirely by raw SQL — see
// `createNotesSearchIndex` in Migrations.kt (run from `MIGRATION_17_18` on
// upgrade and from AppModule's database `onCreate` callback on fresh
// installs) — and queried through `NoteSearchDao`. Decoupling from Room's
// `@Fts4(contentEntity = ...)` mechanism avoids trigger-name collisions
// with the explicit sync triggers we install.
@Database(
    entities = [
        Chat::class,
        Message::class,
        MessageFts::class,
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
        VectorTuneupProjectEntity::class,
        VectorTuneupVersionEntity::class,
        VectorSymbolEntity::class,
    ],
    version = 22,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
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
    abstract fun vectorTuneupDao(): VectorTuneupDao
    abstract fun vectorSymbolDao(): VectorSymbolDao
}
