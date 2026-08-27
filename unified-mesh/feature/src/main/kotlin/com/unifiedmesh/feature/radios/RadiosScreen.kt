package com.unifiedmesh.feature.radios

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unifiedmesh.core.bluetooth.ScannedDevice
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.RadioConnectionState
import com.unifiedmesh.feature.common.ConnectionColors
import com.unifiedmesh.feature.common.describe

/**
 * Radio management: one card per protocol slot, plus scanning.
 *
 * The two slots are always both shown, even when empty, so it is obvious that
 * the app expects two radios and which one is missing.
 */
@Composable
fun RadiosScreen(
    modifier: Modifier = Modifier,
    viewModel: RadiosViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var scanTarget by remember { mutableStateOf<MeshProtocol?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshPermissions() }

    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("RADIOS", style = MaterialTheme.typography.labelLarge)

        if (state.demoMode) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Demo mode is on", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "These are simulated radios, not Bluetooth devices. " +
                            "Turn demo mode off in Settings to use real hardware.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.missingPermissions.isNotEmpty()) {
            PermissionCard(
                permissions = state.missingPermissions,
                onGrant = {
                    permissionLauncher.launch(state.missingPermissions.map { it.manifestPermission }.toTypedArray())
                },
            )
        }

        state.slots.forEach { slot ->
            RadioSlotCard(
                slot = slot,
                onConnect = { viewModel.connect(slot.protocol) },
                onDisconnect = { viewModel.disconnect(slot.protocol) },
                onReconnect = { viewModel.reconnect(slot.protocol) },
                onChangeDevice = {
                    scanTarget = slot.protocol
                    viewModel.startScan()
                },
                onForget = { viewModel.clearAssignment(slot.protocol) },
                onRefresh = { viewModel.refreshDeviceInfo(slot.protocol) },
            )
        }
    }

    scanTarget?.let { protocol ->
        ScanDialog(
            protocol = protocol,
            results = state.scanResults,
            scanning = state.scanning,
            error = state.scanError,
            onPick = { device ->
                viewModel.assign(protocol, device)
                scanTarget = null
            },
            onDismiss = {
                viewModel.stopScan()
                scanTarget = null
            },
        )
    }
}

@Composable
private fun RadioSlotCard(
    slot: RadioSlotUi,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onChangeDevice: () -> Unit,
    onForget: () -> Unit,
    onRefresh: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(ConnectionColors.of(slot.state)),
                )
                Text(
                    text = slot.protocol.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (!slot.hasDevice) {
                Text(
                    text = "No device assigned",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onChangeDevice, modifier = Modifier.padding(top = 6.dp)) {
                    Text("Choose a ${slot.protocol.displayName} radio")
                }
                return@Column
            }

            DetailRow("Device", slot.deviceInfo?.deviceName ?: slot.assignedDevice?.displayName ?: "—")
            DetailRow("Status", slot.state.describe())
            slot.deviceInfo?.hardwareModel?.let { DetailRow("Model", it) }
            slot.deviceInfo?.firmwareVersion?.let { DetailRow("Firmware", it) }

            // Battery is reported differently by the two firmwares: Meshtastic
            // gives a percentage, MeshCore gives millivolts. Neither is converted
            // into the other, because a voltage-to-percentage curve depends on
            // the cell chemistry and would be a guess.
            slot.deviceInfo?.batteryLevel?.let { DetailRow("Battery", "$it%") }
            slot.deviceInfo?.batteryMilliVolts?.let {
                DetailRow("Battery", "${"%.2f".format(it / 1000f)} V")
            }
            slot.deviceInfo?.linkRssi?.let { DetailRow("Signal", "$it dBm") }
            slot.deviceInfo?.lastPacketSnr?.let { DetailRow("Last SNR", "${"%.1f".format(it)} dB") }
            slot.deviceInfo?.nodeName?.let { DetailRow("Node", it) }

            if (slot.state is RadioConnectionState.Error) {
                Text(
                    text = slot.state.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = ConnectionColors.Error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Row(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (slot.state.isConnected) {
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                    FilledTonalButton(onClick = onRefresh) { Text("Refresh") }
                } else {
                    Button(onClick = onConnect) { Text("Connect") }
                    OutlinedButton(onClick = onReconnect) { Text("Reconnect") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onChangeDevice) { Text("Change device") }
                TextButton(onClick = onForget) { Text("Forget") }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PermissionCard(
    permissions: List<com.unifiedmesh.core.bluetooth.BluetoothPermission>,
    onGrant: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Permissions needed", style = MaterialTheme.typography.titleSmall)
            // Each permission is explained on its own terms; a single generic
            // "we need Bluetooth" prompt tells the operator nothing.
            permissions.forEach { permission ->
                Column {
                    Text(permission.title, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        permission.rationale,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = onGrant) { Text("Grant") }
        }
    }
}

/**
 * The scan dialog.
 *
 * Its title names the slot being filled, and every row repeats it on the action
 * button — "Assign as Meshtastic" — because assigning a radio to the wrong stack
 * is the single most damaging mistake available on this screen.
 */
@Composable
private fun ScanDialog(
    protocol: MeshProtocol,
    results: List<ScannedDevice>,
    scanning: Boolean,
    error: String?,
    onPick: (com.unifiedmesh.core.model.RadioDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose the ${protocol.displayName} radio") },
        text = {
            Column {
                Text(
                    text = "This device will be used as your ${protocol.displayName} radio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = ConnectionColors.Error,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                if (scanning && results.isEmpty()) {
                    Row(
                        Modifier.padding(vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Text("Scanning…")
                    }
                }
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(results, key = { it.device.address }) { scanned ->
                        ScanRow(scanned, protocol) { onPick(scanned.device) }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun ScanRow(
    scanned: ScannedDevice,
    target: MeshProtocol,
    onPick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(scanned.device.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = buildString {
                    append(scanned.hint)
                    scanned.device.rssi?.let { append("  ·  $it dBm") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Say so plainly when the advertisement suggests the other stack: the
            // operator may still be right, but they should know they are
            // overriding the hint.
            if (scanned.likelyProtocol != null && scanned.likelyProtocol != target) {
                Text(
                    text = "Looks like a ${scanned.likelyProtocol.displayName} device",
                    style = MaterialTheme.typography.bodySmall,
                    color = ConnectionColors.Reconnecting,
                )
            }
        }
        Button(onClick = onPick) { Text("Assign as ${target.shortLabel}") }
    }
}
