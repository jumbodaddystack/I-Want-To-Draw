package com.aichat.sandbox.data.notes

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sub-phase 9.4 — audio player.
 *
 * Wraps [MediaPlayer] and exposes [positionMs] as a `StateFlow` ticking at
 * 30 Hz so the stroke replayer (`StrokeReplayer`) can scrub strokes in
 * lockstep with the audio. Holding a single instance app-wide keeps the
 * "only one clip plays at a time" invariant trivial; loading a fresh file
 * resets state cleanly.
 */
@Singleton
class AudioPlayer @Inject constructor() {

    private var player: MediaPlayer? = null
    private var currentClipPath: String? = null
    private var tickJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _activeClip = MutableStateFlow<String?>(null)
    /** Currently-loaded clip path (the one [positionMs] applies to), or null. */
    val activeClip: StateFlow<String?> = _activeClip.asStateFlow()

    /** Load and start playing [filePath]. Replaces any in-flight playback. */
    fun play(filePath: String) {
        if (currentClipPath == filePath && player != null) {
            // Resume (or restart from start if completed).
            val mp = player ?: return
            try { mp.start() } catch (t: Throwable) {
                Log.w(TAG, "AudioPlayer: resume failed", t)
                return
            }
            _state.value = PlaybackState.PLAYING
            startTicker()
            return
        }
        release()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(filePath)
            mp.setOnPreparedListener {
                it.start()
                _state.value = PlaybackState.PLAYING
                startTicker()
            }
            mp.setOnCompletionListener {
                _state.value = PlaybackState.COMPLETED
                _positionMs.value = it.duration.toLong().coerceAtLeast(0L)
                stopTicker()
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "AudioPlayer: error what=$what extra=$extra")
                release()
                true
            }
            mp.prepareAsync()
        } catch (t: Throwable) {
            Log.w(TAG, "AudioPlayer: setDataSource failed", t)
            try { mp.release() } catch (_: Throwable) { /* ignore */ }
            return
        }
        player = mp
        currentClipPath = filePath
        _activeClip.value = filePath
        _positionMs.value = 0L
    }

    fun pause() {
        val mp = player ?: return
        if (mp.isPlaying) {
            mp.pause()
            _state.value = PlaybackState.PAUSED
            stopTicker()
        }
    }

    fun resume() {
        val mp = player ?: return
        try { mp.start() } catch (t: Throwable) {
            Log.w(TAG, "AudioPlayer: resume failed", t); return
        }
        _state.value = PlaybackState.PLAYING
        startTicker()
    }

    /** Seek to [positionMs] in the current clip. Updates [positionMs] immediately. */
    fun seekTo(positionMs: Long) {
        val mp = player ?: return
        val clamped = positionMs.coerceAtLeast(0L)
        try { mp.seekTo(clamped.toInt()) } catch (t: Throwable) {
            Log.w(TAG, "AudioPlayer: seek failed", t); return
        }
        _positionMs.value = clamped
    }

    fun release() {
        stopTicker()
        try { player?.release() } catch (_: Throwable) { /* ignore */ }
        player = null
        currentClipPath = null
        _activeClip.value = null
        _state.value = PlaybackState.IDLE
        _positionMs.value = 0L
    }

    private fun startTicker() {
        stopTicker()
        tickJob = scope.launch {
            // Read the player position at ~30 Hz. Stroke replay is rendered
            // from this same value so the two stay locked.
            while (isActive) {
                val mp = player ?: break
                val pos = try { mp.currentPosition.toLong() } catch (_: Throwable) { break }
                _positionMs.value = pos.coerceAtLeast(0L)
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private fun stopTicker() {
        tickJob?.cancel()
        tickJob = null
    }

    enum class PlaybackState { IDLE, PLAYING, PAUSED, COMPLETED }

    companion object {
        private const val TAG = "AudioPlayer"
        private const val TICK_INTERVAL_MS: Long = 33L
    }
}
