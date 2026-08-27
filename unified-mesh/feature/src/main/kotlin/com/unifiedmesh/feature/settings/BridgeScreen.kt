package com.unifiedmesh.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unifiedmesh.core.model.BridgeRule
import com.unifiedmesh.core.model.MeshChannel
import com.unifiedmesh.core.model.MeshProtocol

/**
 * Bridge settings.
 *
 * The screen is arranged to make the safety story visible: the master switch,
 * then the two directions, then the individual channel mappings, then the
 * duplicate and hop controls. Nothing crosses networks unless every level above
 * it is on.
 */
@Composable
fun BridgeScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val bridge = state.bridge
    var editing by remember { mutableStateOf<BridgeRule?>(null) }
    var creating by remember { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        item {
            Card(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    text = "The bridge re-transmits text heard on one network onto the other. " +
                        "The radios stay independent — the phone does the relaying, and it never " +
                        "relays telemetry, positions, acknowledgements or device configuration.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        item { SectionHeader("MASTER BRIDGE") }
        item {
            SwitchRow(
                title = "Enable bridge",
                subtitle = "Off by default. Nothing crosses networks while this is off.",
                checked = bridge.masterEnabled,
                onCheckedChange = viewModel::setBridgeMaster,
            )
        }

        item { SectionHeader("DIRECTIONS") }
        item {
            SwitchRow(
                title = "Meshtastic → MeshCore",
                subtitle = "Relay text heard on Meshtastic onto MeshCore",
                checked = bridge.meshtasticToMeshCore,
                enabled = bridge.masterEnabled,
                onCheckedChange = { viewModel.setBridgeDirection(MeshProtocol.MESHTASTIC, it) },
            )
        }
        item {
            SwitchRow(
                title = "MeshCore → Meshtastic",
                subtitle = "Relay text heard on MeshCore onto Meshtastic",
                checked = bridge.meshCoreToMeshtastic,
                enabled = bridge.masterEnabled,
                onCheckedChange = { viewModel.setBridgeDirection(MeshProtocol.MESHCORE, it) },
            )
        }

        item { SectionHeader("CHANNEL MAPPINGS") }
        if (bridge.rules.isEmpty()) {
            item {
                Text(
                    text = "No mappings configured. Without at least one mapping nothing is relayed, " +
                        "even with the switches above turned on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        } else {
            items(bridge.rules, key = { it.id }) { rule ->
                BridgeRuleRow(
                    rule = rule,
                    onToggle = { viewModel.setRuleEnabled(rule, it) },
                    onEdit = { editing = rule },
                    onDelete = { viewModel.deleteBridgeRule(rule.id) },
                )
                HorizontalDivider()
            }
        }
        item {
            if (!state.canCreateRules) {
                // Without both channel lists there is nothing to pick from, and
                // guessing a destination channel is exactly what this app does not do.
                Text(
                    text = "Connect both radios first — a mapping needs a real channel on each " +
                        "network to point at.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = { creating = true }) { Text("Add mapping") }
                    if (bridge.rules.isEmpty()) {
                        OutlinedButton(onClick = viewModel::addDefaultMappings) {
                            Text("Use defaults")
                        }
                    }
                }
            }
        }

        item { SectionHeader("LOOP PREVENTION") }
        item {
            InfoRow("Bridge hop limit", bridge.maxHops.toString())
        }
        item {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(1, 2, 3).forEach { hops ->
                    FilterChip(
                        selected = bridge.maxHops == hops,
                        onClick = { viewModel.setBridgeMaxHops(hops) },
                        label = { Text("$hops") },
                        enabled = bridge.masterEnabled,
                    )
                }
            }
        }
        item {
            Text(
                text = "How many times a message may cross a bridge — this one or anyone else's. " +
                    "1 is the safe default: a message that has already been relayed once is dropped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        item {
            InfoRow("Duplicate window", "${bridge.duplicateWindowMillis / 60_000} min")
        }
        item {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(5, 10, 30, 60).forEach { minutes ->
                    FilterChip(
                        selected = bridge.duplicateWindowMillis == minutes * 60_000L,
                        onClick = { viewModel.setDuplicateWindowMinutes(minutes) },
                        label = { Text("${minutes}m") },
                        enabled = bridge.masterEnabled,
                    )
                }
            }
        }
        item {
            Text(
                text = "How long the app remembers a message so the same text coming back around " +
                    "is recognised and dropped.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        item { SectionHeader("APPEARANCE") }
        item {
            SwitchRow(
                title = "Mark relayed messages",
                subtitle = "Send as \"[MT: Bear] …\". Turning this off saves airtime but removes " +
                    "the hop counter other bridges rely on to stop loops.",
                checked = bridge.annotateRelayedText,
                enabled = bridge.masterEnabled,
                onCheckedChange = viewModel::setBridgeAnnotate,
            )
        }
    }

    if (creating || editing != null) {
        BridgeRuleDialog(
            existing = editing,
            channelsFor = state::channelsFor,
            onSave = { fromProtocol, fromChannel, toChannel, enabled ->
                viewModel.saveBridgeRule(editing?.id, fromProtocol, fromChannel, toChannel, enabled)
                creating = false
                editing = null
            },
            onDismiss = {
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun BridgeRuleRow(
    rule: BridgeRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = rule.label.ifBlank {
                        "${rule.fromProtocol.displayName} ${rule.fromChannelId ?: "any channel"} " +
                            "-> ${rule.toProtocol.displayName} ${rule.toChannelId}"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = if (rule.enabled) "Enabled" else "Disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = rule.enabled, onCheckedChange = onToggle)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onEdit) { Text("Edit") }
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

/**
 * Create or edit one mapping.
 *
 * The source may be "any channel"; the destination may not. A rule with no
 * destination would have nowhere to relay to, so the picker only offers channels
 * the destination radio has actually reported.
 */
@Composable
private fun BridgeRuleDialog(
    existing: BridgeRule?,
    channelsFor: (MeshProtocol) -> List<MeshChannel>,
    onSave: (fromProtocol: MeshProtocol, fromChannel: MeshChannel?, toChannel: MeshChannel, enabled: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var fromProtocol by remember { mutableStateOf(existing?.fromProtocol ?: MeshProtocol.MESHTASTIC) }
    val sourceChannels = channelsFor(fromProtocol)
    val destinationChannels = channelsFor(fromProtocol.other)

    var fromChannel by remember(fromProtocol) {
        mutableStateOf(sourceChannels.firstOrNull { it.id == existing?.fromChannelId })
    }
    var toChannel by remember(fromProtocol) {
        mutableStateOf(
            destinationChannels.firstOrNull { it.id == existing?.toChannelId }
                ?: destinationChannels.firstOrNull { it.isPrimary }
                ?: destinationChannels.firstOrNull(),
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New mapping" else "Edit mapping") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Relay from", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MeshProtocol.entries.forEach { protocol ->
                        FilterChip(
                            selected = fromProtocol == protocol,
                            onClick = { fromProtocol = protocol },
                            label = { Text(protocol.displayName) },
                        )
                    }
                }

                Text("Source channel", style = MaterialTheme.typography.labelMedium)
                Column {
                    FilterChip(
                        selected = fromChannel == null,
                        onClick = { fromChannel = null },
                        label = { Text("Any channel") },
                    )
                    sourceChannels.forEach { channel ->
                        FilterChip(
                            selected = fromChannel?.id == channel.id,
                            onClick = { fromChannel = channel },
                            label = { Text(channel.displayName) },
                        )
                    }
                }

                Text(
                    text = "Relay to ${fromProtocol.other.displayName}",
                    style = MaterialTheme.typography.labelMedium,
                )
                Column {
                    destinationChannels.forEach { channel ->
                        FilterChip(
                            selected = toChannel?.id == channel.id,
                            onClick = { toChannel = channel },
                            label = { Text(channel.displayName) },
                        )
                    }
                }

                Text(
                    text = "New mappings start disabled. Turn the mapping on, then the direction, " +
                        "then the master switch.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { toChannel?.let { onSave(fromProtocol, fromChannel, it, existing?.enabled ?: false) } },
                enabled = toChannel != null,
            ) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
