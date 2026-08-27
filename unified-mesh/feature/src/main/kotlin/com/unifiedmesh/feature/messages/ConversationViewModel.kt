package com.unifiedmesh.feature.messages

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unifiedmesh.core.database.MessageRepository
import com.unifiedmesh.core.model.Conversation
import com.unifiedmesh.core.model.ConversationKey
import com.unifiedmesh.core.model.ConversationKind
import com.unifiedmesh.core.model.MeshChannel
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.RadioConnectionState
import com.unifiedmesh.core.model.SendAttempt
import com.unifiedmesh.core.model.SendResult
import com.unifiedmesh.core.model.SendTarget
import com.unifiedmesh.core.model.UnifiedMessage
import com.unifiedmesh.core.radio.RadioCoordinator
import com.unifiedmesh.core.radio.SendDestination
import com.unifiedmesh.feature.common.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationUiState(
    val conversationId: String = "",
    val key: ConversationKey? = null,
    val title: String = "",
    val messages: List<UnifiedMessage> = emptyList(),
    val connectionStates: Map<MeshProtocol, RadioConnectionState> = emptyMap(),
    val sendTarget: SendTarget = SendTarget.MESHTASTIC,
    /** Result of the most recent send, shown per protocol under the composer. */
    val lastAttempts: List<SendAttempt> = emptyList(),
    val sending: Boolean = false,
) {
    /**
     * Whether "Both" makes sense here.
     *
     * Only for channel threads. A direct thread is addressed to one identity on
     * one network; there is no corresponding identity on the other, and the app
     * never guesses one.
     */
    val canSendToBoth: Boolean get() = key?.kind == ConversationKind.CHANNEL

    fun isConnected(protocol: MeshProtocol): Boolean = connectionStates[protocol]?.isConnected == true
}

@HiltViewModel
class ConversationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MessageRepository,
    private val coordinator: RadioCoordinator,
) : ViewModel() {

    /**
     * Navigation has already percent-decoded the path argument, so decoding again
     * here would corrupt any id containing a literal `%` or `+`.
     */
    private val conversationId: String =
        savedStateHandle.get<String>(Routes.ARG_CONVERSATION_ID).orEmpty()

    private val key = ConversationKey.parse(conversationId)

    private val sendTarget = MutableStateFlow(
        // Default to the thread's own network. Sending to the other one by
        // default would put a reply on a network the sender is not on.
        key?.protocol?.let {
            when (it) {
                MeshProtocol.MESHTASTIC -> SendTarget.MESHTASTIC
                MeshProtocol.MESHCORE -> SendTarget.MESHCORE
            }
        } ?: SendTarget.MESHTASTIC,
    )

    private val lastAttempts = MutableStateFlow<List<SendAttempt>>(emptyList())
    private val sending = MutableStateFlow(false)

    val uiState: StateFlow<ConversationUiState> = combine(
        repository.observeMessages(conversationId),
        repository.observeConversations(),
        coordinator.connectionStates,
        sendTarget,
        combine(lastAttempts, sending) { attempts, isSending -> attempts to isSending },
    ) { messages, conversations, states, target, (attempts, isSending) ->
        ConversationUiState(
            conversationId = conversationId,
            key = key,
            title = conversations.firstOrNull { it.id == conversationId }?.title
                ?: key?.peerOrChannelId.orEmpty(),
            messages = messages,
            connectionStates = states,
            sendTarget = target,
            lastAttempts = attempts,
            sending = isSending,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ConversationUiState(conversationId = conversationId, key = key),
    )

    /** Channels available on the other network, for a Both send. */
    val otherProtocolChannels: StateFlow<List<MeshChannel>> = run {
        val other = key?.protocol?.other
        when (other) {
            null -> MutableStateFlow(emptyList<MeshChannel>()).asStateFlow()
            else -> coordinator.session(other).channels
        }
    }

    init {
        viewModelScope.launch { repository.markRead(conversationId) }
    }

    fun setSendTarget(target: SendTarget) {
        sendTarget.value = target
        // A new choice invalidates the previous result; leaving a stale
        // "MeshCore ✕ Failed" under a freshly chosen target would misreport.
        lastAttempts.value = emptyList()
    }

    /**
     * Sends [text] to the current target.
     *
     * For [SendTarget.BOTH] the destination on the other network is the channel
     * with the same index, when one exists. That is a convention, not an
     * assumption about the networks being related — the operator can see both
     * results and correct the mapping in Settings.
     */
    fun send(text: String) {
        val trimmed = text.trim()
        val currentKey = key ?: return
        if (trimmed.isEmpty() || sending.value) return

        viewModelScope.launch {
            sending.value = true
            try {
                val destinations = buildDestinations(currentKey)
                val attempts = coordinator.send(
                    target = sendTarget.value,
                    text = trimmed,
                    destinations = destinations,
                    // A channel message must not ask every listener to acknowledge.
                    wantAck = currentKey.kind == ConversationKind.DIRECT,
                )
                lastAttempts.value = attempts
            } catch (e: Exception) {
                lastAttempts.value = listOf(
                    SendAttempt(
                        protocol = currentKey.protocol,
                        messageId = "",
                        result = SendResult.Failed(e.message ?: "Send failed", retryable = true),
                    ),
                )
            } finally {
                sending.value = false
            }
        }
    }

    private fun buildDestinations(currentKey: ConversationKey): Map<MeshProtocol, SendDestination> {
        val own = when (currentKey.kind) {
            ConversationKind.DIRECT -> SendDestination(nodeId = currentKey.peerOrChannelId)
            ConversationKind.CHANNEL -> SendDestination(channelId = currentKey.peerOrChannelId)
        }
        val destinations = mutableMapOf(currentKey.protocol to own)

        if (currentKey.kind == ConversationKind.CHANNEL) {
            val other = currentKey.protocol.other
            val match = coordinator.session(other).channels.value
                .firstOrNull { it.id == currentKey.peerOrChannelId }
            // Only offer the other network a destination when it actually has a
            // channel at that index; otherwise a "Both" send would fail there for
            // a reason the operator cannot see.
            if (match != null) destinations[other] = SendDestination(channelId = match.id)
        }
        return destinations
    }
}

/** Convenience for reading a conversation's protocol-scoped peer. */
val Conversation.peerId: String get() = key.peerOrChannelId
