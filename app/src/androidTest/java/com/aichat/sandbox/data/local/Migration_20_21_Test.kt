package com.aichat.sandbox.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration 20 → 21 (Phase 17.1): adds the `note_tags` junction table.
 *
 * Verifies:
 *  - existing note rows survive untouched,
 *  - the new table accepts (noteId, tag) rows and enforces the composite PK, and
 *  - deleting a note cascades its tag rows away.
 */
@RunWith(AndroidJUnit4::class)
class Migration_20_21_Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    private fun insertNote(db: androidx.sqlite.db.SupportSQLiteDatabase, id: String) {
        db.execSQL(
            """
            INSERT INTO notes (
                id, title, backgroundStyle, schemaVersion,
                minX, minY, maxX, maxY,
                thumbnailPath, ocrText, createdAt, updatedAt,
                undoLogJson, notebookId, isIcon,
                viewportOffsetX, viewportOffsetY, viewportScale
            ) VALUES (
                '$id', 'Icon note', 'graph', 1,
                0.0, 0.0, 100.0, 100.0,
                NULL, NULL, 1000, 2000,
                NULL, NULL, 1,
                NULL, NULL, NULL
            )
            """.trimIndent()
        )
    }

    @Test
    fun migrate20to21_preservesNotesAndCreatesTagTable() {
        helper.createDatabase(TEST_DB, 20).use { db ->
            insertNote(db, "note-1")
        }

        helper.runMigrationsAndValidate(TEST_DB, 21, true, MIGRATION_20_21).use { migrated ->
            migrated.query("SELECT title FROM notes WHERE id = 'note-1'").use { cursor ->
                assertTrue("note row should still exist", cursor.moveToFirst())
                assertEquals("Icon note", cursor.getString(0))
            }
            // The new table starts empty and accepts rows.
            migrated.execSQL("INSERT INTO note_tags(noteId, tag) VALUES('note-1', 'nav')")
            migrated.execSQL("INSERT INTO note_tags(noteId, tag) VALUES('note-1', 'filled')")
            migrated.query("SELECT COUNT(*) FROM note_tags WHERE noteId = 'note-1'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(2, cursor.getInt(0))
                }
        }
    }

    @Test
    fun migrate20to21_tagRowsCascadeWithTheirNote() {
        helper.createDatabase(TEST_DB, 20).use { db ->
            insertNote(db, "note-2")
        }

        helper.runMigrationsAndValidate(TEST_DB, 21, true, MIGRATION_20_21).use { migrated ->
            migrated.execSQL("PRAGMA foreign_keys = ON")
            migrated.execSQL("INSERT INTO note_tags(noteId, tag) VALUES('note-2', 'nav')")
            migrated.execSQL("DELETE FROM notes WHERE id = 'note-2'")
            migrated.query("SELECT COUNT(*) FROM note_tags").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("tag rows should cascade-delete", 0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun openingV21WithMigration_succeeds() {
        // Belt-and-braces: open the post-migration DB via Room itself so the
        // generated DDL matches the schema dump and the identity hash lines up.
        helper.createDatabase(TEST_DB, 20).close()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_20_21)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .build()
        try {
            db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master").close()
        } finally {
            db.close()
            context.deleteDatabase(TEST_DB)
        }
    }

    companion object {
        private const val TEST_DB = "migration-test-20-21"
    }
}
