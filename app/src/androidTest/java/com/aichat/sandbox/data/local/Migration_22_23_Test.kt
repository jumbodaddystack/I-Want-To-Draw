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
 * Migration 22 → 23 (Phase: pen-size zoom scaling): adds the
 * `note_items.fixedWidth` flag.
 *
 * Verifies:
 *  - existing stroke rows survive and default `fixedWidth` to 0 (false), and
 *  - the new column accepts an explicit 1 (true) on fresh inserts.
 */
@RunWith(AndroidJUnit4::class)
class Migration_22_23_Test {

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
                '$id', 'Note', 'graph', 1,
                0.0, 0.0, 100.0, 100.0,
                NULL, NULL, 1000, 2000,
                NULL, NULL, 0,
                NULL, NULL, NULL
            )
            """.trimIndent()
        )
    }

    private fun insertStroke(db: androidx.sqlite.db.SupportSQLiteDatabase, id: String, noteId: String) {
        db.execSQL(
            """
            INSERT INTO note_items (
                id, noteId, zIndex, kind, tool, colorArgb, baseWidthPx, payload, layerId, groupId
            ) VALUES (
                '$id', '$noteId', 0, 'stroke', 'pen', -16777216, 4.0, X'', NULL, NULL
            )
            """.trimIndent()
        )
    }

    @Test
    fun migrate22to23_existingStrokeDefaultsFixedWidthToZero() {
        helper.createDatabase(TEST_DB, 22).use { db ->
            insertNote(db, "note-1")
            insertStroke(db, "item-1", "note-1")
        }

        helper.runMigrationsAndValidate(TEST_DB, 23, true, MIGRATION_22_23).use { migrated ->
            migrated.query("SELECT fixedWidth FROM note_items WHERE id = 'item-1'").use { cursor ->
                assertTrue("stroke row should still exist", cursor.moveToFirst())
                assertEquals("legacy strokes default to non-fixed width", 0, cursor.getInt(0))
            }
            // New inserts can opt into fixed width.
            insertNote(migrated, "note-2")
            migrated.execSQL(
                "INSERT INTO note_items (id, noteId, zIndex, kind, tool, colorArgb, baseWidthPx, " +
                    "payload, layerId, groupId, fixedWidth) " +
                    "VALUES ('item-2', 'note-2', 0, 'stroke', 'pen', -16777216, 4.0, X'', NULL, NULL, 1)"
            )
            migrated.query("SELECT fixedWidth FROM note_items WHERE id = 'item-2'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    @Test
    fun openingV23WithMigration_succeeds() {
        helper.createDatabase(TEST_DB, 22).close()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_22_23)
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
        private const val TEST_DB = "migration-test-22-23"
    }
}
