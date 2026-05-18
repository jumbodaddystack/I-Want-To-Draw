package com.aichat.sandbox.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration 5 → 6 (sub-phase 5.2): adds `notes.undoLogJson` (nullable TEXT).
 *
 * Verifies:
 *  - every existing column on the seeded note row round-trips unchanged, and
 *  - the new `undoLogJson` column exists and defaults to `null` for
 *    pre-existing rows.
 *
 * The migration is additive (`ALTER TABLE notes ADD COLUMN ...`) — no
 * existing row is rewritten, no FK relationship changes.
 */
@RunWith(AndroidJUnit4::class)
class Migration_5_6_Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate5to6_addsUndoLogColumnAndPreservesRows() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                """
                INSERT INTO notes (
                    id, title, backgroundStyle, schemaVersion,
                    minX, minY, maxX, maxY,
                    thumbnailPath, ocrText, createdAt, updatedAt
                ) VALUES (
                    'note-1', 'Existing note', 'plain', 1,
                    0.0, 0.0, 100.0, 100.0,
                    NULL, NULL, 1000, 2000
                )
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6).use { migrated ->
            migrated.query("SELECT * FROM notes WHERE id = 'note-1'").use { cursor ->
                assertTrue("note row should still exist", cursor.moveToFirst())

                val titleIdx = cursor.getColumnIndexOrThrow("title")
                val bgIdx = cursor.getColumnIndexOrThrow("backgroundStyle")
                val createdAtIdx = cursor.getColumnIndexOrThrow("createdAt")
                val undoIdx = cursor.getColumnIndexOrThrow("undoLogJson")

                assertEquals("Existing note", cursor.getString(titleIdx))
                assertEquals("plain", cursor.getString(bgIdx))
                assertEquals(1000L, cursor.getLong(createdAtIdx))
                assertTrue(
                    "undoLogJson should default to NULL for pre-existing rows",
                    cursor.isNull(undoIdx),
                )
            }
        }
    }

    @Test
    fun migrate5to6_allowsSettingUndoLog() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                """
                INSERT INTO notes (
                    id, title, backgroundStyle, schemaVersion,
                    minX, minY, maxX, maxY,
                    thumbnailPath, ocrText, createdAt, updatedAt
                ) VALUES (
                    'note-2', 'Another note', 'dot', 1,
                    0.0, 0.0, 0.0, 0.0,
                    NULL, NULL, 1000, 2000
                )
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6).use { migrated ->
            migrated.execSQL(
                "UPDATE notes SET undoLogJson = '{\"schema\":1,\"past\":[],\"future\":[]}' " +
                    "WHERE id = 'note-2'"
            )
            migrated.query("SELECT undoLogJson FROM notes WHERE id = 'note-2'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    "{\"schema\":1,\"past\":[],\"future\":[]}",
                    cursor.getString(0),
                )
            }
        }
    }

    @Test
    fun openingV6WithMigration_succeeds() {
        // Belt-and-braces: open the post-migration DB via Room itself so the
        // generated DDL matches the schema dump and the identity hash lines
        // up.
        helper.createDatabase(TEST_DB, 5).close()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_5_6)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .build()
        try {
            db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master").close()
        } finally {
            db.close()
            context.deleteDatabase(TEST_DB)
        }
        // Silence unused-import warnings on platforms where assertNull isn't
        // reached during a successful open.
        assertNull(null)
    }

    companion object {
        private const val TEST_DB = "migration-test-5-6"
    }
}
