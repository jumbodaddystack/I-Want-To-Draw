package com.aichat.sandbox.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aichat.sandbox.data.model.BrushPreset
import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.model.MessageFts
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteFrame
import com.aichat.sandbox.data.model.NoteItem
import com.aichat.sandbox.data.model.NoteLayer
import com.aichat.sandbox.data.model.Stamp

@Database(
    entities = [
        Chat::class,
        Message::class,
        MessageFts::class,
        Note::class,
        NoteItem::class,
        NoteLayer::class,
        BrushPreset::class,
        NoteFrame::class,
        Stamp::class,
    ],
    version = 11,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun noteDao(): NoteDao
    abstract fun brushPresetDao(): BrushPresetDao
    abstract fun noteFrameDao(): NoteFrameDao
    abstract fun stampDao(): StampDao
}
