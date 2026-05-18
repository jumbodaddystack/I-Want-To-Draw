package com.aichat.sandbox.ui.components.notes

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/**
 * Sub-phase 9.4 — record button + timer.
 *
 * Asks for RECORD_AUDIO at first tap (not at app launch — same contract
 * as image insert). Tap toggles between idle ↔ recording.
 */
@Composable
fun AudioRecordingBar(
    isRecording: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pendingStart by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingStart) onStart()
        pendingStart = false
    }

    var elapsed by remember(isRecording) { mutableStateOf(0L) }
    LaunchedEffect(isRecording) {
        if (!isRecording) {
            elapsed = 0L
            return@LaunchedEffect
        }
        val anchor = android.os.SystemClock.elapsedRealtime()
        while (true) {
            elapsed = android.os.SystemClock.elapsedRealtime() - anchor
            delay(200L)
        }
    }

    Surface(
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(24.dp)),
        color = if (isRecording) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (isRecording) {
                    onStop()
                } else {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        onStart()
                    } else {
                        pendingStart = true
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }) {
                if (isRecording) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "Stop recording",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Mic,
                        contentDescription = "Record audio",
                    )
                }
            }
            if (isRecording) {
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(Color.Red, RoundedCornerShape(50)),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatDuration(elapsed),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/**
 * Hh:mm:ss style time formatter. Drops the hours segment when the
 * recording is under an hour to keep the timer compact.
 */
internal fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val hh = totalSec / 3600L
    val mm = (totalSec % 3600L) / 60L
    val ss = totalSec % 60L
    return if (hh > 0L) String.format("%d:%02d:%02d", hh, mm, ss)
    else String.format("%02d:%02d", mm, ss)
}

