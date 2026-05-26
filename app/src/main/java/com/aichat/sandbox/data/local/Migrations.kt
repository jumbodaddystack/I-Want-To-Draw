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

/**
 * Migration from version 9 to 10:
 * Sub-phase 8.1 — frame primitive. Adds the `note_frames` table holding
 * named rectangles in world space per note. Frames don't clip rendering;
 * they exist to define exportable regions and (in Phase 9) notebook
 * pages. Notes without frames are unaffected.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `note_frames` (
                `id` TEXT NOT NULL,
                `noteId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `minX` REAL NOT NULL,
                `minY` REAL NOT NULL,
                `maxX` REAL NOT NULL,
                `maxY` REAL NOT NULL,
                `ordinal` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_frames_noteId` ON `note_frames` (`noteId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_frames_noteId_ordinal` ON `note_frames` (`noteId`, `ordinal`)")
    }
}

/**
 * Migration from version 10 to 11:
 * Sub-phase 8.3 — object library. Adds the `stamps` table holding saved
 * selections as reusable JSON payloads. Stamps are app-scoped (not
 * per-note) so a user can drop the same stamp into any note.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `stamps` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `thumbnailPath` TEXT NOT NULL,
                `payloadJson` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `lastUsedAt` INTEGER,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stamps_lastUsedAt` ON `stamps` (`lastUsedAt`)")
    }
}

/**
 * Migration from version 11 to 12:
 * Bundles Phase 9.1 (notebooks table + `notes.notebookId`) and Phase 9.3
 * (FTS4 mirror of `notes.ocrText`). Both are additive — existing rows are
 * left intact and the new column / table merely surface optional data.
 *
 * A notebook is a header row + the FK on the underlying [Note]. Notes
 * with a non-null `notebookId` are notebook pages (one note per notebook
 * in the 9.1 contract; this leaves room to relax that in a future phase).
 *
 * The FTS table follows the same shape Room generates for `messages_fts`
 * (sub-phase 1.5). We populate it from existing `notes.ocrText` once at
 * migration time; from then on Room maintains it via the BEFORE
 * INSERT/UPDATE/DELETE triggers it installs from the schema annotation.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // --- Phase 9.1: notebooks table -----------------------------------
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `notebooks` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `pageStyle` TEXT NOT NULL,
                `pageWidth` REAL NOT NULL,
                `pageHeight` REAL NOT NULL,
                `defaultBrushPresetId` TEXT,
                `coverColorArgb` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent()
        )
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `notebookId` TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_notes_notebookId` ON `notes` (`notebookId`)")

        // --- Phase 9.3: notes_ocr_fts ------------------------------------
        // Standalone FTS4 index (no `content=` link to `notes`). The
        // triggers below keep it in sync; raw `UPDATE ocrText` paths in
        // `NoteDao.updateOcrText` therefore propagate correctly.
        db.execSQL(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS `notes_ocr_fts`
                USING FTS4(`ocrText` TEXT NOT NULL)
            """.trimIndent()
        )
        // Seed: copy existing OCR text into the index. Empty / null rows
        // are skipped so an FTS4 search never matches the empty string.
        db.execSQL(
            """
            INSERT INTO `notes_ocr_fts`(`docid`, `ocrText`)
                SELECT `rowid`, `ocrText` FROM `notes` WHERE `ocrText` IS NOT NULL
            """.trimIndent()
        )
        // Triggers keep the FTS index in sync when `notes.ocrText` is
        // mutated by raw UPDATE statements (Room's generated FTS triggers
        // only cover Insert/Delete, not partial-column updates like the
        // sub-phase 2.4 `updateOcrText` path).
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS notes_fts_ai AFTER INSERT ON notes BEGIN
                INSERT INTO notes_ocr_fts(docid, ocrText)
                    VALUES(new.rowid, COALESCE(new.ocrText, ''));
            END;
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS notes_fts_ad AFTER DELETE ON notes BEGIN
                DELETE FROM notes_ocr_fts WHERE docid = old.rowid;
            END;
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TRIGGER IF NOT EXISTS notes_fts_au AFTER UPDATE OF ocrText ON notes BEGIN
                DELETE FROM notes_ocr_fts WHERE docid = old.rowid;
                INSERT INTO notes_ocr_fts(docid, ocrText)
                    VALUES(new.rowid, COALESCE(new.ocrText, ''));
            END;
            """.trimIndent()
        )
    }
}

/**
 * Migration from version 12 to 13:
 * Sub-phase 9.4 — audio-synced ink. Adds the `note_audio` table.
 *
 * Stroke payload v2 (`x, y, p, tilt, t`) is gated per-note via
 * `Note.schemaVersion` rather than a bulk rewrite: existing v1 strokes
 * keep their four-floats-per-sample layout forever and the decoder
 * synthesizes `t` at read time. Only strokes drawn during an audio
 * recording bump to v2.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `note_audio` (
                `id` TEXT NOT NULL,
                `noteId` TEXT NOT NULL,
                `filePath` TEXT NOT NULL,
                `durationMs` INTEGER NOT NULL,
                `recordedAt` INTEGER NOT NULL,
                `recordingStartedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`noteId`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_audio_noteId` ON `note_audio` (`noteId`)")
    }
}

/**
 * Migration from version 13 to 14:
 * Icon mode — adds the `isIcon` flag to `notes`. Existing rows default to
 * `0` (false) so they keep behaving as ordinary notes.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `notes` ADD COLUMN `isIcon` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Migration from version 14 to 15:
 * Phase 6 — persistent Vector Art Tune-Up projects. Adds two additive tables:
 *   - `vector_tuneup_projects`: one row per saved project, holding the exact
 *     imported `sourceXml` and a pointer to the active version.
 *   - `vector_tuneup_versions`: the version tree (original + every generated
 *     candidate) with `parentId` lineage, cascade-deleted with their project.
 *
 * `activeVersionId` deliberately has no foreign key (it would create a circular
 * project↔version dependency at creation time); app logic maintains it. Index
 * names match what Room generates for the declared `@Index`es so the schema
 * identity validates on open.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `vector_tuneup_projects` (
                `id` TEXT NOT NULL,
                `title` TEXT NOT NULL,
                `sourceXml` TEXT NOT NULL,
                `activeVersionId` TEXT,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_vector_tuneup_projects_updatedAt` " +
                "ON `vector_tuneup_projects` (`updatedAt`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `vector_tuneup_versions` (
                `id` TEXT NOT NULL,
                `projectId` TEXT NOT NULL,
                `parentId` TEXT,
                `label` TEXT NOT NULL,
                `instruction` TEXT NOT NULL,
                `mode` TEXT NOT NULL,
                `xml` TEXT NOT NULL,
                `metricsJson` TEXT NOT NULL,
                `warningsJson` TEXT NOT NULL,
                `reportSummary` TEXT,
                `editPlanJson` TEXT,
                `sceneJson` TEXT,
                `previewPngPath` TEXT,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`),
                FOREIGN KEY(`projectId`) REFERENCES `vector_tuneup_projects`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_vector_tuneup_versions_projectId` " +
                "ON `vector_tuneup_versions` (`projectId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_vector_tuneup_versions_parentId` " +
                "ON `vector_tuneup_versions` (`parentId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_vector_tuneup_versions_createdAt` " +
                "ON `vector_tuneup_versions` (`createdAt`)",
        )
    }
}
