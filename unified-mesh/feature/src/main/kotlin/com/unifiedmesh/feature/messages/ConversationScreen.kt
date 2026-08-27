package com.unifiedmesh.feature.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unifiedmesh.core.model.DeliveryState
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.MessageDirection
import com.unifiedmesh.core.model.SendAttempt
import com.unifiedmesh.core.model.SendResult
import com.unifiedmesh.core.model.SendTarget
import com.unifiedmesh.core.model.UnifiedMessage
import com.unifiedmesh.feature.common.ConnectionColors
import com.unifiedmesh.feature.common.ProtocolTag

/**
 * One conversation, with the Send Via composer beneath it.
 */
@Composable
fun ConversationScreen(
    modifier: Modifier = Modifier,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Follow the conversation as it grows, the way a chat app should.
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(state.messages, key = { it.id }) { message ->
                MessageBubble(message)
            }
        }

        HorizontalDivider()
        Composer(
            state = state,
            onTargetChange = viewModel::setSendTarget,
            onSend = viewModel::send,
        )
    }
}

@Composable
private fun MessageBubble(message: UnifiedMessage) {
    val outgoing = message.direction == MessageDirection.OUTGOING
    val background = when {
        outgoing -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(background)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ProtocolTag(message.protocol)
                Text(
                    text = message.senderName ?: message.senderId,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (message.bridged) {
                    // A relayed message is marked so the operator knows it did not
                    // arrive on this network under its own power.
                    Text(
                        text = "bridged",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 2.dp),
            )

            Row(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatRelativeTime(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (outgoing) {
                    Text(
                        text = message.deliveryState.label(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (message.deliveryState == DeliveryState.FAILED) {
                            ConnectionColors.Error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                // Signal quality is what tells an operator whether a link is
                // marginal, so it is shown inline rather than hidden in details.
                message.snr?.let {
                    Text(
                        text = "SNR ${"%.1f".format(it)} dB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun DeliveryState.label(): String = when (this) {
    DeliveryState.QUEUED -> "Queued"
    DeliveryState.SENDING -> "Sending…"
    DeliveryState.SENT -> "Sent"
    DeliveryState.DELIVERED -> "Delivered"
    DeliveryState.FAILED -> "Failed"
    DeliveryState.RECEIVED -> ""
}

@Composable
private fun Composer(
    state: ConversationUiState,
    onTargetChange: (SendTarget) -> Unit,
    onSend: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    Surface(tonalElevation = 2.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SendViaSelector(state, onTargetChange)

            if (state.lastAttempts.isNotEmpty()) {
                SendResults(state.lastAttempts)
            }

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    maxLines = 5,
                )
                IconButton(
                    onClick = {
                        onSend(draft)
                        draft = ""
                    },
                    enabled = draft.isNotBlank() && !state.sending,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

/**
 * SEND VIA — Meshtastic / MeshCore / Both.
 *
 * A target whose radio is disconnected is disabled rather than hidden, so the
 * operator can see that the option exists and why it is unavailable.
 */
@Composable
private fun SendViaSelector(
    state: ConversationUiState,
    onTargetChange: (SendTarget) -> Unit,
) {
    val options = buildList {
        add(SendTarget.MESHTASTIC to "Meshtastic")
        add(SendTarget.MESHCORE to "MeshCore")
        if (state.canSendToBoth) add(SendTarget.BOTH to "Both")
    }

    Column {
        Text(
            text = "SEND VIA",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (target, label) ->
                val enabled = target.protocols().all(state::isConnected)
                SegmentedButton(
                    selected = state.sendTarget == target,
                    onClick = { onTargetChange(target) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) {
                    Text(label)
                }
            }
        }
    }
}

/**
 * Per-radio send results.
 *
 * Both lines are always shown for a Both send, because "Meshtastic sent,
 * MeshCore failed" is a materially different situation from "both sent" and the
 * operator has to be able to tell them apart at a glance.
 */
@Composable
private fun SendResults(attempts: List<SendAttempt>) {
    Column {
        attempts.forEach { attempt ->
            val ok = attempt.result is SendResult.Accepted
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = attempt.protocol.displayName,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = if (ok) "✓ Sent" else "✕ Failed",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (ok) ConnectionColors.Connected else ConnectionColors.Error,
                )
                (attempt.result as? SendResult.Failed)?.let {
                    Text(
                        text = it.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Shown when a conversation cannot be resolved from its id. */
@Composable
fun MissingConversation(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "That conversation is no longer available.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}

/** Used by previews and the map legend. */
internal val MeshProtocol.accent: androidx.compose.ui.graphics.Color
    get() = when (this) {
        MeshProtocol.MESHTASTIC -> androidx.compose.ui.graphics.Color(0xFF2F6FED)
        MeshProtocol.MESHCORE -> androidx.compose.ui.graphics.Color(0xFF7B49C9)
    }
