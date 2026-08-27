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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unifiedmesh.core.model.BridgeRule
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
                BridgeRuleRow(rule)
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
}

@Composable
private fun BridgeRuleRow(rule: BridgeRule) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            text = rule.label.ifBlank {
                "${rule.fromProtocol.displayName} ${rule.fromChannelId ?: "any channel"} " +
                    "→ ${rule.toProtocol.displayName} ${rule.toChannelId}"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = if (rule.enabled) "Enabled" else "Disabled",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
