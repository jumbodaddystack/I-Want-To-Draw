package com.aichat.sandbox.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aichat.sandbox.ui.screens.chat.ChatScreen
import com.aichat.sandbox.ui.screens.chatlist.ChatListScreen
import com.aichat.sandbox.ui.screens.icons.IconsListScreen
import com.aichat.sandbox.ui.screens.notes.ENTRY_SOURCE_ICON
import com.aichat.sandbox.ui.screens.notes.NOTE_ID_NEW
import com.aichat.sandbox.ui.screens.notes.NoteEditorScreen
import com.aichat.sandbox.ui.screens.notes.NoteSearchScreen
import com.aichat.sandbox.ui.screens.notes.NotesListScreen
import com.aichat.sandbox.ui.screens.settings.SettingsScreen
import com.aichat.sandbox.ui.screens.vector.ROUTE_VECTOR_TUNEUP
import com.aichat.sandbox.ui.screens.vector.VectorTuneupScreen

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object ChatList : Screen("chat_list", "Chat", Icons.Filled.Chat)
    data object Notes : Screen("notes", "Notes", Icons.Filled.EditNote)
    data object IconsTab : Screen("icons", "Icons", Icons.Filled.Draw)
    data object VectorTuneup : Screen(ROUTE_VECTOR_TUNEUP, "Vector", Icons.Filled.Tune)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
}

val bottomNavItems = listOf(
    Screen.ChatList,
    Screen.Notes,
    Screen.IconsTab,
    Screen.VectorTuneup,
    Screen.Settings
)

/**
 * Deep-link request handed to [AppNavigation] from `MainActivity`. Phase 3.1
 * introduces a single `aichat://notes/new` URI for every quick-capture entry
 * point (home-screen shortcut today, Quick Settings tile in 3.2,
 * `ACTION_CREATE_NOTE` in 3.3, sketch composer in 3.4).
 */
sealed interface NotesDeepLink {
    data class NewNote(val source: String?, val stylus: Boolean) : NotesDeepLink
}

@Composable
fun AppNavigation(
    pendingDeepLink: NotesDeepLink? = null,
    onDeepLinkHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val showBottomBar = currentDestination?.route in bottomNavItems.map { it.route }

    // Consume any pending deep link exactly once. The MainActivity owns the
    // backing state and clears it via [onDeepLinkHandled] so a rotation or
    // recomposition can't double-navigate.
    LaunchedEffect(pendingDeepLink) {
        val link = pendingDeepLink ?: return@LaunchedEffect
        when (link) {
            is NotesDeepLink.NewNote -> {
                val source = link.source?.let { java.net.URLEncoder.encode(it, "UTF-8") }
                val query = buildString {
                    if (source != null) append("source=").append(source)
                    if (link.stylus) {
                        if (isNotEmpty()) append('&')
                        append("stylus=true")
                    }
                }
                val route = if (query.isEmpty()) "note/new" else "note/new?$query"
                navController.navigate(route) { launchSingleTop = true }
            }
        }
        onDeepLinkHandled()
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        ),
        bottomBar = {
            if (showBottomBar) {
                CompactBottomBar(
                    items = bottomNavItems,
                    isSelected = { screen ->
                        currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    },
                    onSelect = { screen ->
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        }
    ) { padding ->
        // The outer Scaffold owns all window insets: the status-bar inset is
        // carried in `padding` on top, and the compact bottom bar's measured
        // height (incl. its navigation-bar inset) is carried on the bottom.
        // Passing the full `padding` keeps every tab's content — and its FAB —
        // above the bar (previously the bottom was zeroed, hiding the create
        // buttons and the Settings "About" section behind the nav bar).
        NavHost(
            navController = navController,
            startDestination = Screen.ChatList.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.ChatList.route) {
                ChatListScreen(
                    onChatClick = { chatId ->
                        navController.navigate("chat/$chatId")
                    },
                    onNewChat = { chatId ->
                        navController.navigate("chat/$chatId")
                    }
                )
            }
            composable(
                // Optional `draftText` query arg lets callers (notably the AI
                // side sheet's "Send to chat" reply action — sub-phase 2.8)
                // prefill the composer without auto-sending. URL-encode on
                // the navigate side; `ChatScreen` strips the arg from the
                // back stack after first read so rotation doesn't re-prefill.
                route = "chat/{chatId}?draftText={draftText}",
                arguments = listOf(
                    navArgument("chatId") { type = NavType.StringType },
                    navArgument("draftText") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                )
            ) { backStackEntry ->
                val chatId = backStackEntry.arguments?.getString("chatId") ?: return@composable
                ChatScreen(
                    chatId = chatId,
                    onNavigateBack = { navController.popBackStack() },
                    onOpenSettings = {
                        navController.navigate(Screen.Settings.route) {
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(Screen.Notes.route) {
                NotesListScreen(
                    onNoteClick = { noteId ->
                        navController.navigate("note/$noteId")
                    },
                    onNewNote = {
                        navController.navigate("note/$NOTE_ID_NEW")
                    },
                    // Sub-phase 11.4 — starter templates ride the new-note
                    // route as an optional query arg.
                    onNewNoteFromTemplate = { templateId ->
                        navController.navigate("note/$NOTE_ID_NEW?template=$templateId")
                    },
                    onOpenNotebook = { noteId ->
                        navController.navigate("note/$noteId")
                    },
                    onOpenSearch = {
                        navController.navigate("notes_search")
                    },
                )
            }
            composable(Screen.IconsTab.route) {
                IconsListScreen(
                    onIconClick = { noteId ->
                        navController.navigate("note/$noteId")
                    },
                    onNewIcon = {
                        navController.navigate("note/$NOTE_ID_NEW?source=$ENTRY_SOURCE_ICON")
                    },
                )
            }
            // Quick-capture deep link (Phase 3.1). Lives as its own route so
            // `note/{noteId}` doesn't have to grow optional args that only
            // ever apply to fresh notes. Both `source` and `stylus` are
            // optional, so a bare `note/new` (used by the in-app FAB before
            // it grew query args, plus shortcuts.xml fallbacks) still matches.
            composable(
                route = "note/new?source={source}&stylus={stylus}&template={template}",
                arguments = listOf(
                    navArgument("source") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("stylus") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                    // Sub-phase 11.4 — optional starter-template id.
                    navArgument("template") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) {
                NoteEditorScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToChat = { chatId ->
                        // Sub-phase 4.3: PNG + draft text live in
                        // [PendingDraftStore], keyed by chatId. The chat
                        // screen reads + clears the entry once, so
                        // navigation is a plain `chat/{id}` push.
                        navController.navigate("chat/$chatId")
                    },
                )
            }
            composable(
                route = "note/{noteId}",
                arguments = listOf(navArgument("noteId") { type = NavType.StringType })
            ) {
                NoteEditorScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToChat = { chatId ->
                        navController.navigate("chat/$chatId")
                    },
                )
            }
            composable("notes_search") {
                NoteSearchScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenNote = { noteId ->
                        navController.navigate("note/$noteId")
                    },
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
            // Vector Art Tune-Up workspace (Phase 3) — a bottom-nav destination.
            // (It was briefly orphaned when the Icons tab took its slot; the
            // Notes + Notebooks merge freed a slot to bring it back.)
            composable(Screen.VectorTuneup.route) {
                VectorTuneupScreen(
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/**
 * Compact bottom navigation bar — replaces the stock Material [NavigationBar]
 * (80dp) with a shorter (~56dp) custom row to reclaim vertical space the user
 * flagged as "too tall". Keeps the same accent/selection colours and routing
 * behaviour; applies the system navigation-bar inset itself so the Scaffold
 * reports its full height into the content padding.
 */
@Composable
private fun CompactBottomBar(
    items: List<Screen>,
    isSelected: (Screen) -> Boolean,
    onSelect: (Screen) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .heightIn(min = 56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { screen ->
                val selected = isSelected(screen)
                val tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .selectable(
                            selected = selected,
                            role = Role.Tab,
                            onClick = { onSelect(screen) },
                        )
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        screen.icon,
                        contentDescription = screen.label,
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = screen.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
