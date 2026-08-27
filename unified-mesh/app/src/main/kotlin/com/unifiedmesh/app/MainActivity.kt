package com.unifiedmesh.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.unifiedmesh.app.service.RadioService
import com.unifiedmesh.core.bluetooth.BluetoothPermissions
import com.unifiedmesh.core.database.SettingsRepository
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

    @Inject lateinit var permissions: BluetoothPermissions

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
        //
        // Gated on Bluetooth permission: a connectedDevice foreground service
        // started without BLUETOOTH_CONNECT is refused by the platform on
        // Android 14 and later, which would crash the app on first launch
        // before the operator has been asked for anything.
        startRadioServiceIfAllowed()

        pendingConversationId = intent?.getStringExtra(EXTRA_CONVERSATION_ID)

        setContent {
            UnifiedMeshTheme {
                // The navigation itself happens inside UnifiedMeshApp, where the
                // graph is known to be set. Clearing the id once consumed stops a
                // later return to the app re-opening a thread already left.
                UnifiedMeshApp(
                    onExportDiagnostics = ::shareDiagnostics,
                    deepLinkConversationId = pendingConversationId,
                    onDeepLinkHandled = { pendingConversationId = null },
                )
            }
        }
    }

    /**
     * Starts the radio service whenever the activity is in the foreground and the
     * operator has granted Bluetooth access.
     *
     * `repeatOnLifecycle(STARTED)` does two jobs here. It re-runs the check every
     * time the activity comes back to the foreground, which is how the service
     * starts after the permission dialog is answered rather than staying down for
     * the rest of the session. And it keeps the start out of the background, where
     * Android 12+ refuses a foreground service outright — the setting read
     * suspends, so a bare launch could land after the activity had already gone.
     */
    private fun startRadioServiceIfAllowed() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val wanted = settings.general.first().backgroundOperationEnabled
                if (wanted && permissions.allGranted(this@MainActivity)) {
                    // Starting an already-running service is a no-op beyond
                    // re-delivering the connect action, which is idempotent.
                    RadioService.start(this@MainActivity)
                }
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
