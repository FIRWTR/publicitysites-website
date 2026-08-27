package com.unifiedmesh.feature.nodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.unifiedmesh.core.database.MessageRepository
import com.unifiedmesh.core.model.MeshNode
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.feature.common.ProtocolTag
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class NodesViewModel @Inject constructor(
    repository: MessageRepository,
) : ViewModel() {

    /**
     * Nodes grouped by network.
     *
     * Grouped, never merged. Two entries with the same display name on the two
     * networks are two different people until an operator says otherwise —
     * silently combining them would send a message to the wrong person.
     */
    val nodesByProtocol: StateFlow<Map<MeshProtocol, List<MeshNode>>> =
        repository.observeNodes()
            .map { nodes -> nodes.groupBy { it.protocol } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}

/** The combined people/nodes list. */
@Composable
fun NodesScreen(
    modifier: Modifier = Modifier,
    viewModel: NodesViewModel = hiltViewModel(),
) {
    val grouped by viewModel.nodesByProtocol.collectAsStateWithLifecycle()

    if (grouped.values.all { it.isEmpty() }) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "Nodes appear here once a radio is connected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        MeshProtocol.entries.forEach { protocol ->
            val nodes = grouped[protocol].orEmpty()
            if (nodes.isEmpty()) return@forEach

            item(key = "header-${protocol.name}") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProtocolTag(protocol)
                    Text(
                        text = protocol.displayName.uppercase(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${nodes.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
            }

            items(nodes, key = { "${it.protocol.name}:${it.id}" }) { node ->
                NodeRow(node)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun NodeRow(node: MeshNode) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(node.displayName, style = MaterialTheme.typography.bodyLarge)
                if (node.isSelf) {
                    Text(
                        text = "this radio",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                // The protocol-scoped id is always shown: it is the only thing
                // that actually distinguishes two nodes with the same name.
                text = node.id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            node.batteryLevel?.let {
                Text("$it%", style = MaterialTheme.typography.labelMedium)
            }
            node.snr?.let {
                Text(
                    text = "${"%.1f".format(it)} dB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            node.hopsAway?.let {
                Text(
                    text = if (it == 0) "direct" else "$it hop${if (it == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
