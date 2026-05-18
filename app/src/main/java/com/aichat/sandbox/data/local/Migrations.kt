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

/**
 * Migration from version 6 to 7:
 * Sub-phase 6.4 — real layer system. Adds:
 *   - `note_layers` table (per-note ordered layer list with visibility / lock / opacity)
 *   - `note_items.layerId` column (FK, nullable; null = default layer)
 *
 * For each existing note we materialise an "Ink" layer and (if any
 * `tool='highlighter'` items exist) a "Highlights" layer beneath it. Each
 * existing item is reparented to the matching layer. The legacy
 * negative-z-base hack used to draw highlighters under ink continues to
 * work — the new layer ordering simply makes the convention explicit.
 *
 * The migration runs inside the implicit Room transaction; on a 100-note
 * fixture it completes well under 1s because per-note inserts are tiny.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `note_layers` (
                `id` TEXT NOT NULL,
                `noteId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `opacityPercent` INTEGER NOT NULL,
                `visible` INTEGER NOT NULL,
                `locked` INTEGER NOT NULL,
                `ordinal` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_layers_noteId` ON `note_layers` (`noteId`)")
        db.execSQL("ALTER TABLE note_items ADD COLUMN layerId TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_items_layerId` ON `note_items` (`layerId`)")

        // Materialise default layers per existing note. Two-pass:
        //   1. for every note, insert one "Ink" layer (ordinal 0); if the note
        //      has any highlighter items, also insert one "Highlights" layer
        //      (ordinal -1, so it renders beneath).
        //   2. assign each item to the layer matching its tool.
        val noteCursor = db.query("SELECT id FROM notes")
        val noteIds = ArrayList<String>(noteCursor.count)
        while (noteCursor.moveToNext()) noteIds.add(noteCursor.getString(0))
        noteCursor.close()
        for (noteId in noteIds) {
            val highlighterCountCursor = db.query(
                "SELECT COUNT(*) FROM note_items WHERE noteId = ? AND tool = 'highlighter'",
                arrayOf(noteId),
            )
            highlighterCountCursor.moveToFirst()
            val hasHighlighters = highlighterCountCursor.getInt(0) > 0
            highlighterCountCursor.close()

            val inkLayerId = java.util.UUID.randomUUID().toString()
            db.execSQL(
                "INSERT INTO note_layers(id, noteId, name, opacityPercent, visible, locked, ordinal) " +
                    "VALUES(?, ?, 'Ink', 100, 1, 0, 0)",
                arrayOf(inkLayerId, noteId),
            )
            db.execSQL(
                "UPDATE note_items SET layerId = ? WHERE noteId = ? AND (tool IS NULL OR tool != 'highlighter')",
                arrayOf(inkLayerId, noteId),
            )
            if (hasHighlighters) {
                val hlLayerId = java.util.UUID.randomUUID().toString()
                db.execSQL(
                    "INSERT INTO note_layers(id, noteId, name, opacityPercent, visible, locked, ordinal) " +
                        "VALUES(?, ?, 'Highlights', 100, 1, 0, -1)",
                    arrayOf(hlLayerId, noteId),
                )
                db.execSQL(
                    "UPDATE note_items SET layerId = ? WHERE noteId = ? AND tool = 'highlighter'",
                    arrayOf(hlLayerId, noteId),
                )
            }
        }
    }
}

/**
 * Migration from version 7 to 8:
 * Sub-phase 6.5 — brush presets. Adds the `brush_presets` table and seeds
 * 18 app-scope rows (6 default colours × 3 ink tools). User-scope rows
 * arrive at runtime via "Save as preset…".
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `brush_presets` (
                `id` TEXT NOT NULL,
                `ownerScope` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `tool` TEXT NOT NULL,
                `colorArgb` INTEGER NOT NULL,
                `baseWidthPx` REAL NOT NULL,
                `opacity` REAL NOT NULL,
                `taperStart` REAL NOT NULL,
                `taperEnd` REAL NOT NULL,
                `jitter` REAL NOT NULL,
                `pressureCurveId` TEXT NOT NULL,
                `textureId` TEXT NOT NULL,
                `ordinal` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_brush_presets_tool` ON `brush_presets` (`tool`)")

        // Seeded presets. Colours mirror ToolPaletteState.DEFAULT_COLOR_SWATCHES.
        // Names lean utilitarian — "Pen / Black", "Pencil / Charcoal" etc. —
        // because the chip row shows the colour swatch anyway.
        val tools = listOf(
            Triple("pen", 4f, "smooth"),
            Triple("highlighter", 18f, "smooth"),
            Triple("pencil", 3f, "charcoal"),
        )
        val colours = listOf(
            0xFF000000.toInt() to "Black",
            0xFF2D2D2D.toInt() to "Graphite",
            0xFFD62828.toInt() to "Red",
            0xFF2463EB.toInt() to "Blue",
            0xFF109F5C.toInt() to "Green",
            0xFFFF9F1C.toInt() to "Orange",
        )
        var ordinal = 0
        for ((tool, width, texture) in tools) {
            for ((argb, name) in colours) {
                db.execSQL(
                    "INSERT INTO brush_presets(id, ownerScope, name, tool, colorArgb, baseWidthPx, " +
                        "opacity, taperStart, taperEnd, jitter, pressureCurveId, textureId, ordinal) " +
                        "VALUES(?, 'app', ?, ?, ?, ?, 1.0, 0.0, 0.0, 0.0, 'LINEAR', ?, ?)",
                    arrayOf<Any>(
                        java.util.UUID.randomUUID().toString(),
                        "$name $tool".replaceFirstChar { it.uppercase() },
                        tool,
                        argb,
                        width,
                        texture,
                        ordinal++,
                    ),
                )
            }
        }
    }
}

/**
 * Migration from version 8 to 9:
 * Sub-phase 6.7 — image insert. No new tables; `NoteItem.kind` simply gains
 * the `"image"` discriminator. Existing rows are unaffected; new image
 * payloads use [com.aichat.sandbox.ui.components.notes.ImageItemCodec].
 *
 * The version bump exists primarily so a future build that introduces an
 * incompatible image format can branch on the schema version cleanly.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // No-op DDL: image items use the existing note_items columns
        // (kind = "image", payload encodes path / dims / crop / rotation).
    }
}
