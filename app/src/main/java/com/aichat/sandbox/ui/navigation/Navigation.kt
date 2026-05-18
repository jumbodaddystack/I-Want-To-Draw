package com.aichat.sandbox.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import com.aichat.sandbox.ui.screens.images.ImagesScreen
import com.aichat.sandbox.ui.screens.notebooks.NotebooksListScreen
import com.aichat.sandbox.ui.screens.notes.NOTE_ID_NEW
import com.aichat.sandbox.ui.screens.notes.NoteEditorScreen
import com.aichat.sandbox.ui.screens.notes.NoteSearchScreen
import com.aichat.sandbox.ui.screens.notes.NotesListScreen
import com.aichat.sandbox.ui.screens.settings.SettingsScreen
import com.aichat.sandbox.ui.screens.templates.TemplatesScreen

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object ChatList : Screen("chat_list", "Chat", Icons.Filled.Chat)
    data object Notes : Screen("notes", "Notes", Icons.Filled.EditNote)
    data object Notebooks : Screen("notebooks", "Notebooks", Icons.Filled.AutoStories)
    data object Templates : Screen("templates", "Templates", Icons.Filled.ListAlt)
    data object Images : Screen("images", "Images", Icons.Filled.Image)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
}

val bottomNavItems = listOf(
    Screen.ChatList,
    Screen.Notes,
    Screen.Notebooks,
    Screen.Templates,
    Screen.Images,
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
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    bottomNavItems.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.label) },
                            label = { Text(screen.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
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
                    onNavigateBack = { navController.popBackStack() }
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
                    onOpenSearch = {
                        navController.navigate("notes_search")
                    },
                )
            }
            // Quick-capture deep link (Phase 3.1). Lives as its own route so
            // `note/{noteId}` doesn't have to grow optional args that only
            // ever apply to fresh notes. Both `source` and `stylus` are
            // optional, so a bare `note/new` (used by the in-app FAB before
            // it grew query args, plus shortcuts.xml fallbacks) still matches.
            composable(
                route = "note/new?source={source}&stylus={stylus}",
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
            composable(Screen.Notebooks.route) {
                NotebooksListScreen(
                    onOpenNotebook = { noteId ->
                        navController.navigate("note/$noteId")
                    },
                    onOpenSearch = {
                        navController.navigate("notes_search")
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
            composable(Screen.Templates.route) {
                TemplatesScreen()
            }
            composable(Screen.Images.route) {
                ImagesScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }
}
