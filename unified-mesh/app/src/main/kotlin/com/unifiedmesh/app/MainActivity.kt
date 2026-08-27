package com.unifiedmesh.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.unifiedmesh.app.service.RadioService
import com.unifiedmesh.core.database.SettingsRepository
import com.unifiedmesh.feature.common.UnifiedMeshApp
import com.unifiedmesh.feature.common.UnifiedMeshTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository

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

        setContent {
            UnifiedMeshTheme {
                UnifiedMeshApp(onExportDiagnostics = ::shareDiagnostics)
            }
        }
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
