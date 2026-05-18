package com.aichat.sandbox.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aichat.sandbox.data.model.Chat
import com.aichat.sandbox.data.model.Message
import com.aichat.sandbox.data.model.MessageFts
import com.aichat.sandbox.data.model.Note
import com.aichat.sandbox.data.model.NoteItem

@Database(
    entities = [Chat::class, Message::class, MessageFts::class, Note::class, NoteItem::class],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun noteDao(): NoteDao
}
