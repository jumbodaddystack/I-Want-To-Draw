package com.aichat.sandbox.data.vector

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads imported file content (XML / SVG / project-bundle JSON) from a content
 * URI for the Vector Art Tune-Up workspace (Phase 11).
 *
 * This is the UI/IO seam so the ViewModel and reducer stay free of Android URI
 * plumbing. Reads are bounded by [maxBytes] (the stream is abandoned as soon as
 * it crosses the limit) and never throw — a too-large file, an unreadable URI, or
 * non-text content all come back as a [Result.failure] with a stable
 * [ReadError] the caller maps to a friendly message.
 */
@Singleton
class VectorTuneupFileReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Why a read failed, so callers can show the right friendly message. */
    enum class ReadError {
        TOO_LARGE,
        UNREADABLE,
    }

    class FileReadException(val error: ReadError) : Exception(error.name)

    /**
     * Reads up to [maxBytes] of UTF-8 text from [uri]. Returns failure with a
     * [FileReadException] when the URI cannot be opened/read or exceeds the limit.
     */
    suspend fun readText(
        uri: Uri,
        maxBytes: Long = VectorInputLimits.MAX_IMPORT_BYTES,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(FileReadException(ReadError.UNREADABLE))
            stream.use { input ->
                val buffer = ByteArray(64 * 1024)
                val out = java.io.ByteArrayOutputStream()
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) {
                        return@withContext Result.failure(FileReadException(ReadError.TOO_LARGE))
                    }
                    out.write(buffer, 0, read)
                }
                Result.success(out.toString(Charsets.UTF_8.name()))
            }
        } catch (e: FileReadException) {
            Result.failure(e)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read import URI", t)
            Result.failure(FileReadException(ReadError.UNREADABLE))
        }
    }

    private companion object {
        const val TAG = "VectorFileReader"
    }
}
