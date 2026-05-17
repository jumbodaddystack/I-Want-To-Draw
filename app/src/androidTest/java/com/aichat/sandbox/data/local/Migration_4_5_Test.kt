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
 * Migration 4 → 5 covers the chat-side pinned-note column
 * (`chats.pinnedNoteId`, sub-phase 4.4). The test seeds a chat at schema v4
 * with all existing columns populated, runs the migration, then verifies:
 *  - every existing column round-trips unchanged, and
 *  - the new `pinnedNoteId` column exists and defaults to `null` for
 *    pre-existing rows.
 *
 * Uses the schema dumps under `app/schemas/` so the migration is exercised
 * against the actual Room-generated DDL, not a hand-written stand-in.
 */
@RunWith(AndroidJUnit4::class)
class Migration_4_5_Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate4to5_addsPinnedNoteIdColumn_andPreservesExistingRows() {
        // Seed at v4 with a representative chat row.
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                """
                INSERT INTO chats (
                    id, title, model, systemMessage, temperature, topP, maxTokens,
                    presencePenalty, frequencyPenalty, totalTokens, totalCost,
                    createdAt, updatedAt
                ) VALUES (
                    'chat-1', 'Existing chat', 'gpt-4o', 'be brief',
                    0.7, 0.9, 1024, 0.0, 0.0, 100, 0.05,
                    1000, 2000
                )
                """.trimIndent()
            )
        }

        // Apply the migration.
        helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5).use { migrated ->
            migrated.query("SELECT * FROM chats WHERE id = 'chat-1'").use { cursor ->
                assertTrue("chat row should still exist", cursor.moveToFirst())

                val idIdx = cursor.getColumnIndexOrThrow("id")
                val titleIdx = cursor.getColumnIndexOrThrow("title")
                val modelIdx = cursor.getColumnIndexOrThrow("model")
                val systemIdx = cursor.getColumnIndexOrThrow("systemMessage")
                val createdAtIdx = cursor.getColumnIndexOrThrow("createdAt")
                val updatedAtIdx = cursor.getColumnIndexOrThrow("updatedAt")
                val pinnedIdx = cursor.getColumnIndexOrThrow("pinnedNoteId")

                assertEquals("chat-1", cursor.getString(idIdx))
                assertEquals("Existing chat", cursor.getString(titleIdx))
                assertEquals("gpt-4o", cursor.getString(modelIdx))
                assertEquals("be brief", cursor.getString(systemIdx))
                assertEquals(1000L, cursor.getLong(createdAtIdx))
                assertEquals(2000L, cursor.getLong(updatedAtIdx))
                assertTrue(
                    "pinnedNoteId should default to NULL for pre-existing rows",
                    cursor.isNull(pinnedIdx)
                )
            }
        }
    }

    @Test
    fun migrate4to5_allowsSettingPinnedNoteId() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                """
                INSERT INTO chats (
                    id, title, model, systemMessage, temperature, topP, maxTokens,
                    presencePenalty, frequencyPenalty, totalTokens, totalCost,
                    createdAt, updatedAt
                ) VALUES (
                    'chat-2', 'Another chat', 'gpt-4o', '',
                    0.7, 0.9, 1024, 0.0, 0.0, 0, 0.0,
                    1000, 2000
                )
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5).use { migrated ->
            migrated.execSQL("UPDATE chats SET pinnedNoteId = 'note-xyz' WHERE id = 'chat-2'")
            migrated.query("SELECT pinnedNoteId FROM chats WHERE id = 'chat-2'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("note-xyz", cursor.getString(0))
            }
        }
    }

    @Test
    fun openingV5WithMigration_succeeds() {
        // Belt-and-braces: open the post-migration DB via Room itself so the
        // generated DDL matches the schema dump and there's no identity-hash
        // mismatch lurking.
        helper.createDatabase(TEST_DB, 4).close()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(MIGRATION_4_5)
            .openHelperFactory(FrameworkSQLiteOpenHelperFactory())
            .build()
        try {
            // Triggering a query forces Room to open + validate.
            db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master").close()
        } finally {
            db.close()
            // Cleanup: helper.createDatabase reuses the same on-disk file.
            context.deleteDatabase(TEST_DB)
        }
    }

    companion object {
        private const val TEST_DB = "migration-test-4-5"
    }
}
