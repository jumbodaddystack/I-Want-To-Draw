package com.aichat.sandbox.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration from version 1 to 2:
 * - Establishes safe migration infrastructure (replaces fallbackToDestructiveMigration)
 * - Creates FTS4 virtual table for full-text search on messages (1.5)
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Create FTS4 virtual table for full-text search on messages
        db.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS `messages_fts`
            USING FTS4(`content`, content=`messages`)
        """.trimIndent())
        // Populate FTS table with existing messages
        db.execSQL("""
            INSERT INTO messages_fts(messages_fts) VALUES('rebuild')
        """.trimIndent())
    }
}

/**
 * Migration from version 2 to 3:
 * Adds multi-modal support columns to the messages table.
 * - contentType: "text" or "multimodal" to indicate message type
 * - metadata: JSON blob storing image URIs and other attachment data
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN contentType TEXT NOT NULL DEFAULT 'text'")
        db.execSQL("ALTER TABLE messages ADD COLUMN metadata TEXT")
    }
}

/**
 * Migration from version 3 to 4:
 * Adds the stylus-notes data layer:
 * - `notes` table for note metadata (title, background style, bounds, OCR text, thumbnail).
 * - `note_items` table for strokes / text items, keyed by noteId with cascade delete.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `notes` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `backgroundStyle` TEXT NOT NULL,
                `schemaVersion` INTEGER NOT NULL,
                `minX` REAL NOT NULL,
                `minY` REAL NOT NULL,
                `maxX` REAL NOT NULL,
                `maxY` REAL NOT NULL,
                `thumbnailPath` TEXT,
                `ocrText` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `note_items` (
                `id` TEXT NOT NULL,
                `noteId` TEXT NOT NULL,
                `zIndex` INTEGER NOT NULL,
                `kind` TEXT NOT NULL,
                `tool` TEXT,
                `colorArgb` INTEGER NOT NULL,
                `baseWidthPx` REAL NOT NULL,
                `payload` BLOB NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_items_noteId` ON `note_items` (`noteId`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_note_items_noteId_zIndex` ON `note_items` (`noteId`, `zIndex`)"
        )
    }
}

/**
 * Migration from version 4 to 5:
 * Sub-phase 4.4 — pin a note to a chat as transparent per-turn context. Adds
 * the nullable `pinnedNoteId` column on `chats`; existing rows preserve all
 * data and default to `null` (no pin). Renders are not persisted alongside —
 * see `PinnedNoteCache` for the in-memory caching layer used at send time.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chats ADD COLUMN pinnedNoteId TEXT")
    }
}

/**
 * Migration from version 5 to 6:
 * Sub-phase 5.2 — persisted undo/redo. Adds `undoLogJson` to `notes`. The
 * column is nullable so existing rows are left undisturbed and the editor
 * loads them with an empty stack (the same behaviour as a freshly opened note).
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE notes ADD COLUMN undoLogJson TEXT")
    }
}
