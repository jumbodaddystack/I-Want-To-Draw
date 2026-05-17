package com.aichat.sandbox

import android.content.Intent
import android.net.Uri
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
import com.aichat.sandbox.ui.theme.AIChatSandboxTheme
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
            AIChatSandboxTheme(darkTheme = darkMode) {
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
        if (intent.action != Intent.ACTION_VIEW) return null
        val data: Uri = intent.data ?: return null
        if (data.scheme != "aichat" || data.host != "notes") return null
        if (data.pathSegments.firstOrNull() != "new") return null
        val source = data.getQueryParameter("source")
        val stylus = data.getQueryParameter("stylus")?.equals("true", ignoreCase = true) ?: false
        Log.d(TAG, "deep-link source=$source stylus=$stylus uri=$data")
        return NotesDeepLink.NewNote(source = source, stylus = stylus)
    }

    companion object {
        private const val TAG = "NotesEntry"
    }
}
