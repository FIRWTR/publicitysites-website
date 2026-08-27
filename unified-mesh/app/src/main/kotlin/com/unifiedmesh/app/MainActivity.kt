package com.unifiedmesh.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.lifecycleScope
import com.unifiedmesh.app.service.RadioService
import com.unifiedmesh.core.database.SettingsRepository
import com.unifiedmesh.feature.common.Routes
import com.unifiedmesh.feature.common.UnifiedMeshApp
import com.unifiedmesh.feature.common.UnifiedMeshTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository

    /**
     * Conversation to open, set by tapping a message notification.
     *
     * Held as Compose state rather than read once in onCreate, because the
     * activity is singleTask: a second notification tap arrives at [onNewIntent]
     * on the existing instance, not as a fresh onCreate.
     */
    private var pendingConversationId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Opening the app is the operator asking for their radios, so this is
        // where the service starts — not in Application.onCreate, which runs for
        // any process spawn including a broadcast the operator never saw.
        lifecycleScope.launch {
            if (settings.general.first().backgroundOperationEnabled) {
                RadioService.start(this@MainActivity)
            }
        }

        pendingConversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID)

        setContent {
            UnifiedMeshTheme {
                val navController = rememberNavController()

                // Navigate once per delivered id, then clear it, so returning to
                // the app later does not re-open a thread the operator has left.
                LaunchedEffect(pendingConversationId) {
                    pendingConversationId?.let { conversationId ->
                        navController.navigate(Routes.conversation(conversationId))
                        pendingConversationId = null
                    }
                }

                UnifiedMeshApp(
                    onExportDiagnostics = ::shareDiagnostics,
                    navController = navController,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingConversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)
    }

    /**
     * Shares the sanitised diagnostics log.
     *
     * Uses a plain text share rather than writing a file: the export is already
     * redacted, and a share sheet lets the operator choose where it goes instead
     * of leaving a copy on disk.
     */
    private fun shareDiagnostics(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Unified Mesh diagnostics")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share diagnostics"))
    }

    companion object {
        /** Set on the notification intent so the app can open the right thread. */
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}
