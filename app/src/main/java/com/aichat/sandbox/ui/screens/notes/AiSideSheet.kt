package com.aichat.sandbox.ui.screens.notes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Right-edge slide-in sheet that hosts the AI ask/reply loop for the note
 * editor (sub-phase 2.6 of `docs/STYLUS_NOTES_PHASE_2.md`).
 *
 * Renders on top of the editor — the scrim absorbs canvas taps while the
 * sheet is open. Width is capped at ~70% on phones and at a fixed maximum
 * on tablets so the canvas stays partially visible. Streaming replies are
 * appended to the active turn's bubble; the lazy list auto-scrolls to the
 * latest turn as it grows.
 *
 * Reply actions (Copy / Insert / Send to chat), canned prompt chips, and the
 * in-sheet model picker land in sub-phases 2.7 and 2.8. This sub-phase ships
 * the wiring: open / type / stream / cancel / close.
 */
@Composable
fun AiSideSheet(
    state: AiSideSheetState,
    onInputChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val sheetWidth = (screenWidthDp.value * SHEET_WIDTH_FRACTION).dp
        .coerceAtLeast(MIN_SHEET_WIDTH)
        .coerceAtMost(MAX_SHEET_WIDTH)

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = state.isOpen,
            enter = fadeIn(animationSpec = tween(SCRIM_ANIM_MS)),
            exit = fadeOut(animationSpec = tween(SCRIM_ANIM_MS)),
            modifier = Modifier.fillMaxSize(),
        ) {
            // Scrim — taps anywhere outside the sheet dismiss it.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose,
                    ),
            )
        }

        AnimatedVisibility(
            visible = state.isOpen,
            enter = slideInHorizontally(
                animationSpec = tween(SHEET_ANIM_MS),
                initialOffsetX = { it },
            ),
            exit = slideOutHorizontally(
                animationSpec = tween(SHEET_ANIM_MS),
                targetOffsetX = { it },
            ),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(sheetWidth),
        ) {
            Surface(
                tonalElevation = 4.dp,
                shadowElevation = 8.dp,
                shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth()
                    // Swallow taps inside the sheet so they don't reach the
                    // scrim's `clickable` and close us.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                SheetContent(
                    state = state,
                    onInputChanged = onInputChanged,
                    onSubmit = onSubmit,
                    onCancel = onCancel,
                    onClose = onClose,
                )
            }
        }
    }
}

@Composable
private fun SheetContent(
    state: AiSideSheetState,
    onInputChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SheetHeader(
            modelId = state.activeModelId,
            onClose = onClose,
        )
        HorizontalDivider()
        ConversationList(
            turns = state.turns,
            modifier = Modifier.weight(1f),
        )
        HorizontalDivider()
        SheetFooter(
            inputText = state.inputText,
            isStreaming = state.isStreaming,
            onInputChanged = onInputChanged,
            onSubmit = onSubmit,
            onCancel = onCancel,
        )
    }
}

@Composable
private fun SheetHeader(
    modelId: String,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Ask about this note",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (modelId.isNotEmpty()) {
                Text(
                    text = modelId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "Close AI panel")
        }
    }
}

@Composable
private fun ConversationList(
    turns: List<AskTurn>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val latestTurnId = turns.lastOrNull()?.id
    val latestReplyLength = turns.lastOrNull()?.replyBuffer?.length ?: 0

    // Re-scroll to the bottom whenever a new turn lands OR the active reply
    // grows. Cheap because LazyColumn only re-measures the tail.
    LaunchedEffect(latestTurnId, latestReplyLength) {
        val lastIndex = turns.lastIndex
        if (lastIndex >= 0) listState.animateScrollToItem(lastIndex)
    }

    if (turns.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Ask anything about this note.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(turns, key = { it.id }) { turn ->
            TurnBubble(turn = turn)
        }
    }
}

@Composable
private fun TurnBubble(turn: AskTurn) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PromptBubble(prompt = turn.prompt, selectionSummary = turn.selectionSummary)
        Spacer(modifier = Modifier.height(4.dp))
        ReplyBubble(turn = turn)
    }
}

@Composable
private fun PromptBubble(prompt: String, selectionSummary: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (selectionSummary != null) {
                    Text(
                        text = selectionSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    text = prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ReplyBubble(turn: AskTurn) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                when (turn.state) {
                    is TurnState.Streaming -> {
                        if (turn.replyBuffer.isEmpty()) {
                            TypingIndicator()
                        } else {
                            Text(
                                text = turn.replyBuffer,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    is TurnState.Done -> {
                        Text(
                            text = turn.replyBuffer.ifEmpty { "(empty response)" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is TurnState.Error -> {
                        if (turn.replyBuffer.isNotEmpty()) {
                            Text(
                                text = turn.replyBuffer,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Text(
                            text = turn.state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(14.dp)
                .semantics { contentDescription = "Streaming response" },
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Thinking…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SheetFooter(
    inputText: String,
    isStreaming: Boolean,
    onInputChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChanged,
            placeholder = { Text("Ask anything…") },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(20.dp),
            maxLines = 5,
            enabled = !isStreaming,
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (isStreaming) {
            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .size(48.dp),
            ) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = "Stop response",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        } else {
            val enabled = inputText.isNotBlank()
            IconButton(
                onClick = onSubmit,
                enabled = enabled,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .size(48.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send prompt",
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val SHEET_WIDTH_FRACTION: Float = 0.7f
private val MIN_SHEET_WIDTH = 280.dp
private val MAX_SHEET_WIDTH = 480.dp
private const val SHEET_ANIM_MS: Int = 220
private const val SCRIM_ANIM_MS: Int = 180
