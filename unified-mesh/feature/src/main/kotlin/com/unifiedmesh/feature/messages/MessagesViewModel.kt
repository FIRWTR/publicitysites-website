package com.unifiedmesh.feature.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unifiedmesh.core.database.MessageRepository
import com.unifiedmesh.core.model.Conversation
import com.unifiedmesh.core.model.ConversationKind
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.RadioConnectionState
import com.unifiedmesh.core.radio.RadioCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Inbox filters. */
enum class InboxFilter(val label: String) {
    ALL("All"),
    MESHTASTIC("Meshtastic"),
    MESHCORE("MeshCore"),
    DIRECT("Direct"),
    CHANNELS("Channels"),
    ;

    fun matches(conversation: Conversation): Boolean = when (this) {
        ALL -> true
        MESHTASTIC -> conversation.protocol == MeshProtocol.MESHTASTIC
        MESHCORE -> conversation.protocol == MeshProtocol.MESHCORE
        DIRECT -> conversation.key.kind == ConversationKind.DIRECT
        CHANNELS -> conversation.key.kind == ConversationKind.CHANNEL
    }
}

data class InboxUiState(
    val conversations: List<Conversation> = emptyList(),
    val filter: InboxFilter = InboxFilter.ALL,
    val connectionStates: Map<MeshProtocol, RadioConnectionState> = emptyMap(),
) {
    /** True when there is nothing to show *because* no radio has ever connected. */
    val noRadiosEverConnected: Boolean
        get() = conversations.isEmpty() && connectionStates.values.none { it.isConnected }
}

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val repository: MessageRepository,
    coordinator: RadioCoordinator,
) : ViewModel() {

    private val filter = MutableStateFlow(InboxFilter.ALL)

    val uiState: StateFlow<InboxUiState> = combine(
        repository.observeConversations(),
        filter,
        coordinator.connectionStates,
    ) { conversations, activeFilter, states ->
        InboxUiState(
            // Filtering here rather than with a per-filter query keeps one
            // subscription open instead of tearing down and rebuilding the
            // database flow every time the operator taps a chip.
            conversations = conversations.filter(activeFilter::matches),
            filter = activeFilter,
            connectionStates = states,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = InboxUiState(),
    )

    fun setFilter(value: InboxFilter) {
        filter.value = value
    }

    private companion object {
        /**
         * Keeps the database subscription alive briefly across configuration
         * changes so a rotation does not re-query the whole inbox.
         */
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
