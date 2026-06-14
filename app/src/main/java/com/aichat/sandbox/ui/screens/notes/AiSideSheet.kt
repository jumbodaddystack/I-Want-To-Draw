package com.aichat.sandbox.ui.screens.notes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aichat.sandbox.ui.components.ModelSelector

/**
 * Right-edge slide-in sheet that hosts the AI ask/reply loop for the note
 * editor (sub-phase 2.6 of `docs/STYLUS_NOTES_PHASE_2.md`).
 *
 * P0.2 (audit A2) — a non-blocking docked rail: no scrim, and the editor
 * reserves [aiSheetWidthFor] of layout for it so the canvas sits *beside* the
 * sheet (fully visible and live: draw / pan / point) rather than underneath a
 * tap-absorbing scrim. Streaming replies are appended to the active turn's
 * bubble; the lazy list auto-scrolls to the latest turn as it grows.
 *
 * Canned prompt chips and the scope chip landed in sub-phase 2.7. Sub-phase
 * 2.8 adds the per-reply action row (Copy / Insert / Send to chat) for every
 * Done turn and a `ModelSelector` in the header so the model can be swapped
 * mid-conversation (subsequent turns only — existing replies are immutable).
 */
@Composable
fun AiSideSheet(
    state: AiSideSheetState,
    onInputChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onCannedPrompt: (CannedPrompt) -> Unit,
    onIconQuickAction: (IconQuickAction) -> Unit,
    onFooterModeChanged: (AiFooterMode) -> Unit,
    onClearScope: () -> Unit,
    onInsertConvertResult: (turnId: String) -> Unit,
    onInsertReply: (turnId: String) -> Unit,
    onSendReplyToChat: (turnId: String) -> Unit,
    onModelSelected: (String) -> Unit,
    availableModels: List<String>,
    customModels: List<String>,
    modifier: Modifier = Modifier,
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val sheetWidth = aiSheetWidthFor(screenWidthDp)

    // P0.2 (audit A2) — the sheet is a non-blocking docked rail: the editor
    // reserves `sheetWidth` of layout for it (end padding on the canvas
    // column) so the canvas is never *underneath* the sheet. There is
    // therefore no scrim — the canvas stays fully visible and live (draw /
    // pan / point) while the AI panel is open, which is exactly what you want
    // while reviewing the on-canvas edit diff. Dismissal is the header's
    // close button (the old "tap the scrim" gesture had no visible target).
    Box(modifier = modifier.fillMaxSize()) {
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
                    // Swallow taps inside the sheet so they don't fall through
                    // to anything layered behind the rail.
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
                    onCannedPrompt = onCannedPrompt,
                    onIconQuickAction = onIconQuickAction,
                    onFooterModeChanged = onFooterModeChanged,
                    onClearScope = onClearScope,
                    onInsertConvertResult = onInsertConvertResult,
                    onInsertReply = onInsertReply,
                    onSendReplyToChat = onSendReplyToChat,
                    onModelSelected = onModelSelected,
                    availableModels = availableModels,
                    customModels = customModels,
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
    onCannedPrompt: (CannedPrompt) -> Unit,
    onIconQuickAction: (IconQuickAction) -> Unit,
    onFooterModeChanged: (AiFooterMode) -> Unit,
    onClearScope: () -> Unit,
    onInsertConvertResult: (turnId: String) -> Unit,
    onInsertReply: (turnId: String) -> Unit,
    onSendReplyToChat: (turnId: String) -> Unit,
    onModelSelected: (String) -> Unit,
    availableModels: List<String>,
    customModels: List<String>,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SheetHeader(
            title = if (state.isIcon) "AI for this icon" else "Ask about this note",
            modelId = state.activeModelId,
            availableModels = availableModels,
            customModels = customModels,
            onModelSelected = onModelSelected,
            onClose = onClose,
        )
        HorizontalDivider()
        ConversationList(
            turns = state.turns,
            onInsertConvertResult = onInsertConvertResult,
            onInsertReply = onInsertReply,
            onSendReplyToChat = onSendReplyToChat,
            modifier = Modifier.weight(1f),
        )
        HorizontalDivider()
        ScopeAndCannedPromptRow(
            scopeLabel = state.scopeLabel,
            hasSelection = state.pendingSelection != null,
            isIcon = state.isIcon,
            convertEnabled = state.pendingSelection != null && !state.isStreaming,
            isStreaming = state.isStreaming,
            onCannedPrompt = onCannedPrompt,
            onIconQuickAction = onIconQuickAction,
            onClearScope = onClearScope,
        )
        SheetFooter(
            inputText = state.inputText,
            footerMode = state.footerMode,
            isStreaming = state.isStreaming,
            onInputChanged = onInputChanged,
            onFooterModeChanged = onFooterModeChanged,
            onSubmit = onSubmit,
            onCancel = onCancel,
        )
    }
}

@Composable
private fun SheetHeader(
    title: String,
    modelId: String,
    availableModels: List<String>,
    customModels: List<String>,
    onModelSelected: (String) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close AI panel")
            }
        }
        // Inline model picker (sub-phase 2.8). Switching mid-conversation
        // only affects subsequent turns — replies already on screen keep
        // showing whatever model produced them.
        ModelSelector(
            label = "Model",
            selectedModel = modelId,
            models = availableModels,
            onModelSelected = onModelSelected,
            customModels = customModels,
        )
    }
}

@Composable
private fun ConversationList(
    turns: List<AskTurn>,
    onInsertConvertResult: (turnId: String) -> Unit,
    onInsertReply: (turnId: String) -> Unit,
    onSendReplyToChat: (turnId: String) -> Unit,
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
            TurnBubble(
                turn = turn,
                onInsertConvertResult = onInsertConvertResult,
                onInsertReply = onInsertReply,
                onSendReplyToChat = onSendReplyToChat,
            )
        }
    }
}

@Composable
private fun TurnBubble(
    turn: AskTurn,
    onInsertConvertResult: (turnId: String) -> Unit,
    onInsertReply: (turnId: String) -> Unit,
    onSendReplyToChat: (turnId: String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PromptBubble(prompt = turn.prompt, selectionSummary = turn.selectionSummary)
        Spacer(modifier = Modifier.height(4.dp))
        ReplyBubble(turn = turn)
        // Reply-action row — visible once the turn is Done and produced
        // text. Convert-to-text replies keep the slimmer single-action row
        // because Copy / Send-to-chat don't add much value for raw OCR
        // output (Phase 4 may revisit). The general AI replies get the
        // full Copy / Insert / Send-to-chat row that sub-phase 2.8
        // promises.
        if (turn.state is TurnState.Done && turn.replyBuffer.isNotEmpty()) {
            if (turn.isConvertResult) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    TextButton(onClick = { onInsertConvertResult(turn.id) }) {
                        Icon(
                            imageVector = Icons.Filled.TextFields,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Insert as text box")
                    }
                }
            } else {
                ReplyActionRow(
                    text = turn.replyBuffer,
                    onInsert = { onInsertReply(turn.id) },
                    onSendToChat = { onSendReplyToChat(turn.id) },
                )
            }
        }
    }
}

/**
 * Per-reply action buttons for AI replies (sub-phase 2.8). Copy lives in
 * the composable rather than the VM so it has a `LocalClipboardManager`
 * handle without bouncing through an `ApplicationContext` injection.
 *
 * Buttons stay text-only (no surface chip) to keep the bubble visually
 * close to a chat message — the actions are the affordance, not a separate
 * surface fighting the reply for attention.
 */
@Composable
private fun ReplyActionRow(
    text: String,
    onInsert: () -> Unit,
    onSendToChat: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Copy")
        }
        TextButton(onClick = onInsert) {
            Icon(
                imageVector = Icons.Filled.TextFields,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Insert")
        }
        TextButton(onClick = onSendToChat) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Send to chat")
        }
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

/**
 * Scope chip + canned-prompt row (sub-phase 2.7). Sits above the input
 * field. The scope chip ("Whole note" or "3 strokes selected") tap-clears
 * the frozen selection so the user can pivot mid-conversation. Canned
 * prompts fire on a single tap; Convert-to-text is only enabled when there
 * is a selection in scope.
 */
@Composable
private fun ScopeAndCannedPromptRow(
    scopeLabel: String,
    hasSelection: Boolean,
    isIcon: Boolean,
    convertEnabled: Boolean,
    isStreaming: Boolean,
    onCannedPrompt: (CannedPrompt) -> Unit,
    onIconQuickAction: (IconQuickAction) -> Unit,
    onClearScope: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        FilterChip(
            selected = hasSelection,
            onClick = { if (hasSelection) onClearScope() },
            label = { Text(scopeLabel, style = MaterialTheme.typography.labelMedium) },
            enabled = hasSelection,
            colors = FilterChipDefaults.filterChipColors(),
        )
        Spacer(modifier = Modifier.height(6.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (isIcon) {
                // Icons get design-oriented edit actions instead of the
                // note-centric ask prompts. Convert-to-text is dropped — it's
                // meaningless for an icon. Each action routes through the
                // model-backed EDIT pipeline (Recolor opens the colour picker).
                items(IconQuickAction.entries, key = { it.name }) { action ->
                    AssistChip(
                        onClick = { onIconQuickAction(action) },
                        enabled = !isStreaming,
                        label = { Text(action.label) },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            } else {
                items(CannedPrompt.ASK_PROMPTS, key = { it.name }) { prompt ->
                    AssistChip(
                        onClick = { onCannedPrompt(prompt) },
                        enabled = !isStreaming,
                        label = { Text(prompt.label) },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
                // Convert-to-text last so the order matches the lasso menu and
                // it visually trails the ask prompts as the "different kind" one.
                items(listOf(CannedPrompt.CONVERT_TO_TEXT), key = { it.name }) { prompt ->
                    AssistChip(
                        onClick = { onCannedPrompt(prompt) },
                        enabled = convertEnabled,
                        label = { Text(prompt.label) },
                        colors = AssistChipDefaults.assistChipColors(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetFooter(
    inputText: String,
    footerMode: AiFooterMode,
    isStreaming: Boolean,
    onInputChanged: (String) -> Unit,
    onFooterModeChanged: (AiFooterMode) -> Unit,
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
) {
  Column(
      modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 8.dp),
  ) {
    // Ask | Edit toggle. ASK returns a prose reply; EDIT stages a preview the
    // user accepts or rejects. Two FilterChips keep the affordance compact and
    // avoid depending on the SegmentedButton API version.
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = footerMode == AiFooterMode.ASK,
            onClick = { onFooterModeChanged(AiFooterMode.ASK) },
            label = { Text("Ask") },
            enabled = !isStreaming,
            colors = FilterChipDefaults.filterChipColors(),
        )
        FilterChip(
            selected = footerMode == AiFooterMode.EDIT,
            onClick = { onFooterModeChanged(AiFooterMode.EDIT) },
            label = { Text("Edit") },
            enabled = !isStreaming,
            colors = FilterChipDefaults.filterChipColors(),
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = inputText,
            onValueChange = onInputChanged,
            placeholder = {
                Text(
                    if (footerMode == AiFooterMode.EDIT) "Describe an edit…"
                    else "Ask anything…"
                )
            },
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
}

/**
 * Width the docked AI rail occupies for a given [screenWidthDp]. Shared by
 * the sheet itself and by the editor, which reserves the same width as end
 * padding on the canvas column so the two never overlap (P0.2 / audit A2).
 * Narrower than the pre-dock 70% overlay so a phone keeps a live canvas strip
 * beside the rail; still capped so a tablet rail doesn't sprawl.
 */
fun aiSheetWidthFor(screenWidthDp: Dp): Dp =
    (screenWidthDp.value * SHEET_WIDTH_FRACTION).dp
        .coerceAtLeast(MIN_SHEET_WIDTH)
        .coerceAtMost(MAX_SHEET_WIDTH)

private const val SHEET_WIDTH_FRACTION: Float = 0.5f
private val MIN_SHEET_WIDTH = 280.dp
private val MAX_SHEET_WIDTH = 460.dp
private const val SHEET_ANIM_MS: Int = 220
