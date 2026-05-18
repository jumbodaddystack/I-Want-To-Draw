package com.aichat.sandbox.ui.components.notes

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.data.model.NoteAudio
import com.aichat.sandbox.data.notes.AudioPlayer

/**
 * Sub-phase 9.4 — audio clip strip + scrubber.
 *
 * Lists every recorded clip on the note. Tap a clip to load it into the
 * player; the scrubber appears once a clip is active and drives stroke
 * replay through `StrokeReplayer`.
 */
@Composable
fun AudioPlaybackBar(
    clips: List<NoteAudio>,
    activeClipPath: String?,
    playbackState: AudioPlayer.PlaybackState,
    positionMs: Long,
    onPlay: (NoteAudio) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeek: (Long) -> Unit,
    onDelete: (clipId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (clips.isEmpty()) return
    val activeClip = clips.firstOrNull { it.filePath == activeClipPath }
    Surface(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp)),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(clips, key = { it.id }) { clip ->
                    AudioClipChip(
                        clip = clip,
                        isActive = clip.filePath == activeClipPath,
                        playbackState = if (clip.filePath == activeClipPath) playbackState
                        else AudioPlayer.PlaybackState.IDLE,
                        onPlayPause = {
                            if (clip.filePath == activeClipPath &&
                                playbackState == AudioPlayer.PlaybackState.PLAYING) {
                                onPause()
                            } else if (clip.filePath == activeClipPath &&
                                playbackState == AudioPlayer.PlaybackState.PAUSED) {
                                onResume()
                            } else {
                                onPlay(clip)
                            }
                        },
                        onDelete = { onDelete(clip.id) },
                    )
                }
            }
            if (activeClip != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Scrubber(
                    durationMs = activeClip.durationMs,
                    positionMs = positionMs,
                    onSeek = onSeek,
                )
            }
        }
    }
}

@Composable
private fun AudioClipChip(
    clip: NoteAudio,
    isActive: Boolean,
    playbackState: AudioPlayer.PlaybackState,
    onPlayPause: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = if (isActive) 2.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPlayPause, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = if (playbackState == AudioPlayer.PlaybackState.PLAYING)
                        Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = "Play/pause",
                )
            }
            Text(
                text = formatDuration(clip.durationMs),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete recording",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun Scrubber(
    durationMs: Long,
    positionMs: Long,
    onSeek: (Long) -> Unit,
) {
    val duration = durationMs.coerceAtLeast(1L)
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableStateOf(0f) }
    val current = if (scrubbing) scrubValue else positionMs.toFloat()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatDuration(current.toLong()),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(48.dp),
        )
        Slider(
            value = current,
            onValueChange = {
                scrubbing = true
                scrubValue = it
            },
            onValueChangeFinished = {
                onSeek(scrubValue.toLong())
                scrubbing = false
            },
            valueRange = 0f..duration.toFloat(),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatDuration(duration),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(48.dp),
        )
    }
}
