package com.aichat.sandbox

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.aichat.sandbox.data.local.PreferencesManager
import com.aichat.sandbox.ui.navigation.AppNavigation
import com.aichat.sandbox.ui.navigation.NotesDeepLink
import com.aichat.sandbox.ui.theme.DoodlePadTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var preferencesManager: PreferencesManager

    // Compose-visible deep-link state. Set on cold-launch (`onCreate`) and
    // hot-launch (`onNewIntent`); cleared by AppNavigation after it consumes
    // the request so re-composition or rotation doesn't re-navigate.
    private val pendingDeepLink = mutableStateOf<NotesDeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingDeepLink.value = parseNotesDeepLink(intent)
        setContent {
            val darkMode by preferencesManager.darkMode.collectAsState(initial = true)
            DoodlePadTheme(darkTheme = darkMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(
                        pendingDeepLink = pendingDeepLink.value,
                        onDeepLinkHandled = { pendingDeepLink.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // `singleTask` reuses this activity for repeat deep links; refresh the
        // backing intent and re-publish to Compose.
        setIntent(intent)
        parseNotesDeepLink(intent)?.let { pendingDeepLink.value = it }
    }

    private fun parseNotesDeepLink(intent: Intent?): NotesDeepLink? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> parseViewDeepLink(intent)
            ACTION_CREATE_NOTE -> parseCreateNoteIntent(intent)
            else -> null
        }
    }

    private fun parseViewDeepLink(intent: Intent): NotesDeepLink? {
        val data: Uri = intent.data ?: return null
        if (data.scheme != "doodlepad" || data.host != "notes") return null
        if (data.pathSegments.firstOrNull() != "new") return null
        val source = data.getQueryParameter("source")
        val stylus = data.getQueryParameter("stylus")?.equals("true", ignoreCase = true) ?: false
        Log.d(TAG, "deep-link source=$source stylus=$stylus uri=$data")
        return NotesDeepLink.NewNote(source = source, stylus = stylus)
    }

    private fun parseCreateNoteIntent(intent: Intent): NotesDeepLink {
        val useStylus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            intent.getBooleanExtra(EXTRA_USE_STYLUS_MODE, true)
        } else {
            true
        }
        Log.d(TAG, "deep-link source=create_note_intent stylus=$useStylus")
        // Consume so a configuration change doesn't re-fire the alias path.
        intent.action = null
        return NotesDeepLink.NewNote(source = "create_note_intent", stylus = useStylus)
    }

    companion object {
        private const val TAG = "NotesEntry"
        private const val ACTION_CREATE_NOTE = "android.intent.action.CREATE_NOTE"
        private const val EXTRA_USE_STYLUS_MODE = "android.intent.extra.USE_STYLUS_MODE"
    }
}
