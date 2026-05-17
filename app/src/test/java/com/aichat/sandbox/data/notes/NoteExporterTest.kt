package com.aichat.sandbox.data.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM-only coverage of the pure helpers in [NoteExporter].
 *
 * The render → write → URI pipeline needs `android.graphics.Bitmap` and
 * `FileProvider`, both of which are exercised by an instrumented test (or
 * by manual sub-phase 4.1 verification on the S25 Ultra). What's testable
 * here is the cache-pruning policy and the filename sanitizer.
 */
class NoteExporterTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    @Test
    fun sanitizeBaseNameFallsBackOnBlank() {
        assertEquals("note", NoteExporter.sanitizeBaseName(""))
        assertEquals("note", NoteExporter.sanitizeBaseName("   "))
        // All-symbol titles collapse to underscores → trimmed → "note".
        assertEquals("note", NoteExporter.sanitizeBaseName("///"))
    }

    @Test
    fun sanitizeBaseNameReplacesUnsafeChars() {
        assertEquals("Project_meeting", NoteExporter.sanitizeBaseName("Project / meeting"))
        assertEquals("hello.world", NoteExporter.sanitizeBaseName("hello.world"))
        // Underscore + hyphen + dot all pass through unchanged.
        assertEquals("a-b_c.d", NoteExporter.sanitizeBaseName("a-b_c.d"))
    }

    @Test
    fun sanitizeBaseNameTruncatesLongTitles() {
        val raw = "a".repeat(200)
        val sanitized = NoteExporter.sanitizeBaseName(raw)
        // 40 chars is the configured cap; we don't care what the exact
        // number is, just that we never hand back a multi-hundred char
        // basename that could blow past NAME_MAX.
        assertTrue("Expected ≤ 40 chars, got ${sanitized.length}", sanitized.length <= 40)
    }

    @Test
    fun pruneOldKeepsMostRecentFilesAndDeletesTheRest() {
        val dir = tempFolder.newFolder("exports")
        // Create 5 files with strictly increasing lastModified so the
        // ordering is deterministic regardless of the host filesystem's
        // mtime granularity.
        val files = (1..5).map { i ->
            File(dir, "note-$i.png").apply {
                writeText("payload-$i")
                setLastModified(1_000L * i)
            }
        }

        NoteExporter.pruneOld(dir, keep = 2)

        assertFalse("oldest should be deleted", files[0].exists())
        assertFalse(files[1].exists())
        assertFalse(files[2].exists())
        assertTrue("two most recent should survive", files[3].exists())
        assertTrue(files[4].exists())
    }

    @Test
    fun pruneOldIsNoOpWhenUnderThreshold() {
        val dir = tempFolder.newFolder("exports")
        val files = (1..3).map { i ->
            File(dir, "note-$i.png").apply {
                writeText("payload-$i")
                setLastModified(1_000L * i)
            }
        }
        NoteExporter.pruneOld(dir, keep = 5)
        // All files should still be present.
        files.forEach { assertTrue(it.exists()) }
    }

    @Test
    fun pruneOldIgnoresNonPngFiles() {
        val dir = tempFolder.newFolder("exports")
        val png = File(dir, "note.png").apply {
            writeText("png-payload")
            setLastModified(1_000L)
        }
        val txt = File(dir, "notes.txt").apply {
            writeText("non-png file alongside our exports")
            setLastModified(500L)
        }
        NoteExporter.pruneOld(dir, keep = 0)
        // PNG matched the predicate so it gets deleted; the .txt file is
        // outside our concern and must be left alone (defends against
        // collateral damage if anything else ever writes here).
        assertFalse(png.exists())
        assertTrue(txt.exists())
    }

    @Test
    fun pruneOldHandlesMissingDirectoryGracefully() {
        val ghost = File(tempFolder.root, "no-such-dir")
        // Should not throw; the call is best-effort.
        NoteExporter.pruneOld(ghost, keep = 5)
    }

    @Test
    fun defaultPaperBoundsIsNonEmpty() {
        val b = NoteExporter.defaultPaperBounds()
        assertEquals(4, b.size)
        assertTrue("non-empty width", b[2] > b[0])
        assertTrue("non-empty height", b[3] > b[1])
    }
}
