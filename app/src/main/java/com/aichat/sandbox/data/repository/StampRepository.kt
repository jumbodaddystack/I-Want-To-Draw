package com.aichat.sandbox.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.aichat.sandbox.data.local.StampDao
import com.aichat.sandbox.data.model.Stamp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sub-phase 8.3 — repository for the saved object library ("stamps").
 *
 * A stamp packages a selection's [com.aichat.sandbox.data.notes.VectorCanvasJson]
 * payload alongside a 256 px thumbnail PNG and a name. Stamps live under
 * `filesDir/stamps/` so they survive cache wipes; image dependencies are
 * deep-copied into `filesDir/stamp-images/<stampId>/` on save so deleting
 * the source note can't orphan a stamp's bitmaps.
 */
@Singleton
class StampRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: StampDao,
) {

    fun observeAll(): Flow<List<Stamp>> = dao.observeAll()

    suspend fun getStamp(stampId: String): Stamp? = withContext(Dispatchers.IO) {
        dao.getStamp(stampId)
    }

    /**
     * Persist a new stamp. Writes the thumbnail PNG atomically (`.tmp` →
     * rename) under [stampsDir]; the DAO row picks up the absolute path
     * so the drawer can `BitmapFactory.decodeFile` it directly without a
     * URI lookup.
     */
    suspend fun saveStamp(
        name: String,
        thumbnail: Bitmap,
        payloadJson: String,
    ): Stamp = withContext(Dispatchers.IO) {
        val stamp = Stamp(
            name = name.ifBlank { "Stamp" },
            thumbnailPath = "",
            payloadJson = payloadJson,
        )
        val dir = stampsDir().apply { if (!exists()) mkdirs() }
        val finalFile = File(dir, "${stamp.id}.png")
        val tmpFile = File(dir, "${stamp.id}.png.tmp")
        try {
            FileOutputStream(tmpFile).use { os ->
                thumbnail.compress(Bitmap.CompressFormat.PNG, 100, os)
            }
            if (finalFile.exists()) finalFile.delete()
            check(tmpFile.renameTo(finalFile)) {
                "StampRepository: rename ${tmpFile.name} → ${finalFile.name} failed"
            }
        } finally {
            if (tmpFile.exists()) tmpFile.delete()
        }
        val withPath = stamp.copy(thumbnailPath = finalFile.absolutePath)
        dao.upsert(withPath)
        withPath
    }

    suspend fun rename(stampId: String, name: String) = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext
        dao.rename(stampId, name.trim())
    }

    suspend fun delete(stampId: String) = withContext(Dispatchers.IO) {
        val stamp = dao.getStamp(stampId)
        dao.delete(stampId)
        if (stamp != null) {
            File(stamp.thumbnailPath).delete()
            stampImagesDir(stampId).deleteRecursively()
        }
    }

    suspend fun touchLastUsed(stampId: String) = withContext(Dispatchers.IO) {
        dao.touchLastUsed(stampId, System.currentTimeMillis())
    }

    /** Per-stamp image directory under [filesDir]. */
    fun stampImagesDir(stampId: String): File =
        File(File(context.filesDir, STAMP_IMAGES_SUBDIR), stampId).apply {
            if (!exists()) mkdirs()
        }

    private fun stampsDir(): File = File(context.filesDir, STAMPS_SUBDIR)

    companion object {
        const val STAMPS_SUBDIR: String = "stamps"
        const val STAMP_IMAGES_SUBDIR: String = "stamp-images"
    }
}
