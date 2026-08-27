package com.unifiedmesh.feature.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unifiedmesh.core.model.Conversation
import com.unifiedmesh.feature.common.ProtocolTag

/**
 * The unified inbox.
 *
 * One list across both networks, newest first, with every row tagged `MT` or
 * `MC`. Threads are never merged across protocols: the same person reachable on
 * both networks is two conversations, because replying in the wrong one silently
 * fails to reach them.
 */
@Composable
fun MessagesScreen(
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MessagesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        FilterRow(
            selected = state.filter,
            onSelect = viewModel::setFilter,
        )
        HorizontalDivider()

        if (state.conversations.isEmpty()) {
            EmptyInbox(noRadiosEverConnected = state.noRadiosEverConnected)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.conversations, key = { it.id }) { conversation ->
                    ConversationRow(
                        conversation = conversation,
                        onClick = { onOpenConversation(conversation.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    selected: InboxFilter,
    onSelect: (InboxFilter) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(InboxFilter.entries) { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(filter.label) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ProtocolTag(conversation.protocol)
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            conversation.lastMessageText?.let { preview ->
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = formatRelativeTime(conversation.lastMessageAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (conversation.unreadCount > 0) {
                Badge(Modifier.padding(top = 4.dp)) {
                    Text(conversation.unreadCount.coerceAtMost(99).toString())
                }
            }
        }
    }
}

@Composable
private fun EmptyInbox(noRadiosEverConnected: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = if (noRadiosEverConnected) "No radios connected" else "No messages yet",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                // The empty state names the next action rather than just stating
                // the absence: a blank inbox on first run is otherwise a dead end.
                text = if (noRadiosEverConnected) {
                    "Open the Radios tab to assign your Meshtastic and MeshCore devices."
                } else {
                    "Messages from both networks will appear here."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Short relative timestamp; the conversation screen shows the exact time. */
internal fun formatRelativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    val delta = now - timestamp
    return when {
        delta < 60_000 -> "now"
        delta < 3_600_000 -> "${delta / 60_000}m"
        delta < 86_400_000 -> "${delta / 3_600_000}h"
        delta < 7 * 86_400_000L -> "${delta / 86_400_000}d"
        else -> {
            val formatter = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
            formatter.format(java.util.Date(timestamp))
        }
    }
}
