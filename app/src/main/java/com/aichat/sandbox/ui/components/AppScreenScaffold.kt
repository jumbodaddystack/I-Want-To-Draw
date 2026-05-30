package com.aichat.sandbox.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Standard top-level screen chrome — a slim header row + content host that
 * gives every screen the same compact title, back-button behaviour, action
 * slot, FAB, and snackbar host. Introduced during the UI overhaul to replace
 * the mix of bespoke raw-`Column` headers and one-off `Scaffold` blocks.
 *
 * The header is intentionally tighter than a stock Material `TopAppBar` (~52dp
 * vs 64dp) to claw back the "wasted space" at the top of each tab.
 *
 * Window insets are owned entirely by the outer `Scaffold` in `AppNavigation`
 * (it carries the status-bar inset on top and the bottom-bar inset below), so
 * this inner `Scaffold` consumes nothing (`contentWindowInsets = WindowInsets(0)`)
 * to avoid doubling the padding. The content lambda receives a
 * background-filled [Box] scope already inset by the header.
 */
@Composable
fun AppScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    content: @Composable (contentModifier: Modifier) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            SlimHeader(
                title = title,
                onNavigateBack = onNavigateBack,
                actions = actions,
            )
        },
        floatingActionButton = floatingActionButton,
        snackbarHost = {
            if (snackbarHostState != null) SnackbarHost(snackbarHostState)
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            content(Modifier)
        }
    }
}

/** Compact title row shared by the tab screens. */
@Composable
private fun SlimHeader(
    title: String,
    onNavigateBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .heightIn(min = 52.dp)
            .padding(start = if (onNavigateBack != null) 4.dp else 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onNavigateBack != null) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 8.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            content = actions,
        )
    }
}
