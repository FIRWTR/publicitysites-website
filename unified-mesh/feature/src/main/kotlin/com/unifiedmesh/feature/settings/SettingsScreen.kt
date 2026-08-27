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
import com.unifiedmesh.core.model.BridgeRule
import com.unifiedmesh.core.model.MeshChannel
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.RadioDeviceInfo
import com.unifiedmesh.core.radio.RadioCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class SettingsUiState(
    val general: GeneralSettings = GeneralSettings(),
    val bridge: BridgeConfig = BridgeConfig(),
    val deviceInfo: Map<MeshProtocol, RadioDeviceInfo?> = emptyMap(),
    /** Channels each radio has reported, for building bridge mappings. */
    val channels: Map<MeshProtocol, List<MeshChannel>> = emptyMap(),
) {
    fun channelsFor(protocol: MeshProtocol): List<MeshChannel> = channels[protocol].orEmpty()

    /**
     * True when a mapping can be built at all.
     *
     * A rule names a destination channel, and the app will not invent one — so
     * both radios have to have reported their channels first.
     */
    val canCreateRules: Boolean
        get() = MeshProtocol.entries.all { channelsFor(it).isNotEmpty() }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    coordinator: RadioCoordinator,
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> = combine(
        settings.general,
        settings.bridgeConfig,
        combine(coordinator.meshtastic.deviceInfo, coordinator.meshCore.deviceInfo) { a, b -> a to b },
        combine(coordinator.meshtastic.channels, coordinator.meshCore.channels) { a, b -> a to b },
    ) { general, bridge, (mtInfo, mcInfo), (mtChannels, mcChannels) ->
        SettingsUiState(
            general = general,
            bridge = bridge,
            deviceInfo = mapOf(
                MeshProtocol.MESHTASTIC to mtInfo,
                MeshProtocol.MESHCORE to mcInfo,
            ),
            channels = mapOf(
                MeshProtocol.MESHTASTIC to mtChannels,
                MeshProtocol.MESHCORE to mcChannels,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setNotifications(enabled: Boolean) = launch { settings.setNotificationsEnabled(enabled) }

    fun setBackground(enabled: Boolean) = launch { settings.setBackgroundOperationEnabled(enabled) }

    fun setKeepConnected(enabled: Boolean) = launch { settings.setKeepRadiosConnected(enabled) }

    fun setDemoMode(enabled: Boolean) = launch { settings.setDemoMode(enabled) }

    fun setBridgeMaster(enabled: Boolean) = launch { settings.setBridgeMaster(enabled) }

    fun setBridgeDirection(from: MeshProtocol, enabled: Boolean) =
        launch { settings.setBridgeDirection(from, enabled) }

    fun setBridgeAnnotate(enabled: Boolean) = launch { settings.setBridgeAnnotate(enabled) }

    fun setBridgeMaxHops(hops: Int) = launch { settings.setBridgeMaxHops(hops) }

    fun setDuplicateWindowMinutes(minutes: Int) =
        launch { settings.setBridgeDuplicateWindow(minutes * 60_000L) }

    // --- Bridge mappings ----------------------------------------------------

    /**
     * Creates or replaces a channel mapping.
     *
     * A new rule is created disabled. Turning the bridge on is a separate,
     * deliberate act from deciding which channels it would connect.
     */
    fun saveBridgeRule(
        id: String?,
        fromProtocol: MeshProtocol,
        fromChannel: MeshChannel?,
        toChannel: MeshChannel,
        enabled: Boolean,
    ) = launch {
        val from = fromChannel?.displayName ?: "any channel"
        settings.upsertBridgeRule(
            BridgeRule(
                id = id ?: UUID.randomUUID().toString(),
                enabled = enabled,
                fromProtocol = fromProtocol,
                fromChannelId = fromChannel?.id,
                toProtocol = fromProtocol.other,
                toChannelId = toChannel.id,
                label = "${fromProtocol.displayName} $from -> " +
                    "${fromProtocol.other.displayName} ${toChannel.displayName}",
            ),
        )
    }

    fun setRuleEnabled(rule: BridgeRule, enabled: Boolean) =
        launch { settings.upsertBridgeRule(rule.copy(enabled = enabled)) }

    fun deleteBridgeRule(id: String) = launch { settings.deleteBridgeRule(id) }

    /**
     * Creates the obvious pair of mappings: each network's primary channel to the
     * other's.
     *
     * Created disabled, like any other rule. This exists because pairing the two
     * primary channels is what nearly everyone wants first, and building it by
     * hand twice is friction with no decision in it.
     */
    fun addDefaultMappings() = launch {
        val state = uiState.value
        MeshProtocol.entries.forEach { from ->
            val source = state.channelsFor(from).firstOrNull { it.isPrimary } ?: return@forEach
            val destination = state.channelsFor(from.other).firstOrNull { it.isPrimary } ?: return@forEach
            // Skip if an equivalent mapping already exists, so tapping twice does
            // not produce duplicates that would each relay the same message.
            val existing = state.bridge.rules.any {
                it.fromProtocol == from && it.fromChannelId == source.id && it.toChannelId == destination.id
            }
            if (existing) return@forEach
            settings.upsertBridgeRule(
                BridgeRule(
                    id = UUID.randomUUID().toString(),
                    enabled = false,
                    fromProtocol = from,
                    fromChannelId = source.id,
                    toProtocol = from.other,
                    toChannelId = destination.id,
                    label = "${from.displayName} ${source.displayName} -> " +
                        "${from.other.displayName} ${destination.displayName}",
                ),
            )
        }
    }

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

        item { SectionHeader("DEMO MODE") }
        item {
            SwitchRow(
                title = "Use simulated radios",
                subtitle = "Run the app against two fake radios instead of Bluetooth, so you can " +
                    "try everything without hardware. Disconnect and reconnect both radios after " +
                    "changing this.",
                checked = state.general.demoMode,
                onCheckedChange = viewModel::setDemoMode,
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
