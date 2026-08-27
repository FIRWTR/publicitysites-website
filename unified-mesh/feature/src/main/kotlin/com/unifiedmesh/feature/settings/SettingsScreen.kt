package com.unifiedmesh.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unifiedmesh.core.database.GeneralSettings
import com.unifiedmesh.core.database.SettingsRepository
import com.unifiedmesh.core.model.BridgeConfig
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.RadioDeviceInfo
import com.unifiedmesh.core.radio.RadioCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val general: GeneralSettings = GeneralSettings(),
    val bridge: BridgeConfig = BridgeConfig(),
    val deviceInfo: Map<MeshProtocol, RadioDeviceInfo?> = emptyMap(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    coordinator: RadioCoordinator,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.general,
        settings.bridgeConfig,
        coordinator.meshtastic.deviceInfo,
        coordinator.meshCore.deviceInfo,
    ) { general, bridge, mtInfo, mcInfo ->
        SettingsUiState(
            general = general,
            bridge = bridge,
            deviceInfo = mapOf(
                MeshProtocol.MESHTASTIC to mtInfo,
                MeshProtocol.MESHCORE to mcInfo,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setNotifications(enabled: Boolean) = launch { settings.setNotificationsEnabled(enabled) }

    fun setBackground(enabled: Boolean) = launch { settings.setBackgroundOperationEnabled(enabled) }

    fun setKeepConnected(enabled: Boolean) = launch { settings.setKeepRadiosConnected(enabled) }

    fun setBridgeMaster(enabled: Boolean) = launch { settings.setBridgeMaster(enabled) }

    fun setBridgeDirection(from: MeshProtocol, enabled: Boolean) =
        launch { settings.setBridgeDirection(from, enabled) }

    fun setBridgeAnnotate(enabled: Boolean) = launch { settings.setBridgeAnnotate(enabled) }

    fun setBridgeMaxHops(hops: Int) = launch { settings.setBridgeMaxHops(hops) }

    fun setDuplicateWindowMinutes(minutes: Int) =
        launch { settings.setBridgeDuplicateWindow(minutes * 60_000L) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

@Composable
fun SettingsScreen(
    onOpenBridge: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item { SectionHeader("GENERAL") }
        item {
            SwitchRow(
                title = "Notifications",
                subtitle = "Alert me when a message arrives on either network",
                checked = state.general.notificationsEnabled,
                onCheckedChange = viewModel::setNotifications,
            )
        }
        item {
            SwitchRow(
                title = "Background operation",
                subtitle = "Keep receiving while the screen is off. Shows an ongoing notification.",
                checked = state.general.backgroundOperationEnabled,
                onCheckedChange = viewModel::setBackground,
            )
        }
        item {
            SwitchRow(
                title = "Keep radios connected",
                subtitle = "Reconnect automatically when a radio drops",
                checked = state.general.keepRadiosConnected,
                onCheckedChange = viewModel::setKeepConnected,
            )
        }

        MeshProtocol.entries.forEach { protocol ->
            item { SectionHeader(protocol.displayName.uppercase()) }
            item {
                val info = state.deviceInfo[protocol]
                InfoRow("Device", info?.deviceName ?: "Not assigned")
                InfoRow("Model", info?.hardwareModel ?: "—")
                InfoRow("Firmware", info?.firmwareVersion ?: "—")
                InfoRow("Node", info?.nodeName ?: info?.nodeId ?: "—")
            }
        }

        item { SectionHeader("BRIDGE") }
        item {
            NavigationRow(
                title = "Bridge",
                subtitle = if (state.bridge.masterEnabled) {
                    "On · ${state.bridge.rules.count { it.enabled }} active mapping(s)"
                } else {
                    "Off"
                },
                onClick = onOpenBridge,
            )
        }

        item { SectionHeader("DEVELOPER") }
        item {
            NavigationRow(
                title = "Diagnostics",
                subtitle = "Connection events, frame metadata and bridge decisions",
                onClick = onOpenDiagnostics,
            )
        }
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun NavigationRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        subtitle?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}
