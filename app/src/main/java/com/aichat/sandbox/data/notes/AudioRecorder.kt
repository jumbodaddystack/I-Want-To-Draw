package com.aichat.sandbox.data.notes

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sub-phase 9.4 — audio recorder.
 *
 * Wraps [MediaRecorder] for AAC-in-MP4 capture at 64 kbps. Files land in
 * `<filesDir>/note-audio/<uuid>.m4a` so the FileProvider authority can hand
 * them out later. The recorder owns no UI state — callers drive
 * start / stop and read [recordingStartedAt] back to anchor stroke
 * timestamps.
 *
 * The instance is not thread-safe. The editor uses it from the UI thread.
 */
@Singleton
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private var recorder: MediaRecorder? = null
    private var activeFile: File? = null

    /**
     * Monotonic anchor captured at [start]. Stroke samples timestamp
     * themselves as `SystemClock.elapsedRealtime() - recordingStartedAt`
     * — see [com.aichat.sandbox.ui.components.notes.StrokeCodec] v2.
     */
    var recordingStartedAt: Long = 0L
        private set

    val isRecording: Boolean
        get() = recorder != null

    /**
     * Begin recording into a fresh file under [audioDir]. Returns the
     * absolute path the caller should persist in the `note_audio` row.
     *
     * Throws if a recording is already in progress — callers are expected
     * to gate the record button on [isRecording] themselves.
     */
    fun start(): RecordingHandle {
        check(recorder == null) { "AudioRecorder.start: recorder already active" }
        val dir = audioDir().apply { if (!exists()) mkdirs() }
        val file = File(dir, "${java.util.UUID.randomUUID()}.m4a")
        @Suppress("DEPRECATION")
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(64_000)
            mr.setAudioSamplingRate(44_100)
            mr.setOutputFile(file.absolutePath)
            mr.prepare()
            mr.start()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecorder: start failed", t)
            try { mr.release() } catch (_: Throwable) { /* ignore */ }
            file.delete()
            throw t
        }
        recorder = mr
        activeFile = file
        recordingStartedAt = SystemClock.elapsedRealtime()
        return RecordingHandle(filePath = file.absolutePath, startedAt = recordingStartedAt)
    }

    /**
     * Stop recording. Returns the duration in ms (best-effort:
     * `elapsedRealtime - recordingStartedAt`) and the final on-disk path.
     * Returns `null` when no recording is active (defensive — callers
     * should already gate).
     */
    fun stop(): CompletedRecording? {
        val mr = recorder ?: return null
        val file = activeFile
        val durationMs = (SystemClock.elapsedRealtime() - recordingStartedAt).coerceAtLeast(0L)
        try {
            mr.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecorder: stop threw; treating as abort", t)
            file?.delete()
            cleanup()
            return null
        }
        cleanup()
        return CompletedRecording(
            filePath = file?.absolutePath ?: return null,
            durationMs = durationMs,
            recordingStartedAt = recordingStartedAt,
        )
    }

    /** Abort the current recording (releases the recorder, deletes the partial file). */
    fun abort() {
        val mr = recorder ?: return
        val file = activeFile
        try { mr.stop() } catch (_: Throwable) { /* mid-prepare or no frames */ }
        try { mr.release() } catch (_: Throwable) { /* ignore */ }
        file?.delete()
        cleanup()
    }

    private fun cleanup() {
        try { recorder?.release() } catch (_: Throwable) { /* ignore */ }
        recorder = null
        activeFile = null
    }

    /**
     * Sweep stale `.m4a` partials. Called once at app start so a crash
     * during a recording doesn't leave orphans on disk. Knowns are
     * filenames we've persisted in `note_audio`; anything else gets purged.
     */
    fun sweepOrphans(knownPaths: Set<String>) {
        val dir = audioDir()
        if (!dir.exists()) return
        dir.listFiles()?.forEach { file ->
            if (file.absolutePath !in knownPaths) file.delete()
        }
    }

    fun audioDir(): File = File(context.filesDir, AUDIO_SUBDIR)

    data class RecordingHandle(val filePath: String, val startedAt: Long)

    data class CompletedRecording(
        val filePath: String,
        val durationMs: Long,
        val recordingStartedAt: Long,
    )

    companion object {
        const val AUDIO_SUBDIR: String = "note-audio"
        private const val TAG = "AudioRecorder"
    }
}
