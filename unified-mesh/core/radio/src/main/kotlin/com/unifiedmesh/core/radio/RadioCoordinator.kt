package com.unifiedmesh.core.radio

import com.unifiedmesh.core.bridge.BridgeDecision
import com.unifiedmesh.core.bridge.BridgeEngine
import com.unifiedmesh.core.model.BridgeConfig
import com.unifiedmesh.core.model.Clock
import com.unifiedmesh.core.model.ConversationKey
import com.unifiedmesh.core.model.DeliveryState
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.MessageDirection
import com.unifiedmesh.core.model.OutgoingMessage
import com.unifiedmesh.core.model.SendAttempt
import com.unifiedmesh.core.model.SendResult
import com.unifiedmesh.core.model.SendTarget
import com.unifiedmesh.core.model.UnifiedMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * Where the app persists everything the radios produce.
 *
 * An interface so `:core:radio` stays free of Room, and so tests can watch what
 * would have been stored.
 */
interface MessageStore {
    suspend fun saveIncoming(message: UnifiedMessage)

    suspend fun saveOutgoing(message: UnifiedMessage)

    suspend fun updateDeliveryState(messageId: String, state: DeliveryState, detail: String? = null)
}

/** Notified when a message arrives, so the app can raise a notification. */
fun interface IncomingMessageListener {
    suspend fun onMessage(message: UnifiedMessage)
}

/** Both radio slots, plus the composer and the bridge that sit across them. */
class RadioCoordinator(
    val meshtastic: RadioSession,
    val meshCore: RadioSession,
    private val store: MessageStore,
    private val bridgeEngine: BridgeEngine,
    private val bridgeConfig: StateFlow<BridgeConfig>,
    private val clock: Clock = Clock.System,
    dispatcher: CoroutineContext = kotlinx.coroutines.Dispatchers.Default,
    private val diagnostics: (String) -> Unit = {},
    private val incomingListener: IncomingMessageListener? = null,
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var started = false

    /** The slot for [protocol]. */
    fun session(protocol: MeshProtocol): RadioSession = when (protocol) {
        MeshProtocol.MESHTASTIC -> meshtastic
        MeshProtocol.MESHCORE -> meshCore
    }

    /** Combined connection state for the header indicators. */
    val connectionStates: StateFlow<Map<MeshProtocol, com.unifiedmesh.core.model.RadioConnectionState>> =
        combine(meshtastic.state, meshCore.state) { mt, mc ->
            mapOf(MeshProtocol.MESHTASTIC to mt, MeshProtocol.MESHCORE to mc)
        }.stateIn(
            scope = scope,
            started = kotlinx.coroutines.flow.SharingStarted.Eagerly,
            initialValue = mapOf(
                MeshProtocol.MESHTASTIC to meshtastic.state.value,
                MeshProtocol.MESHCORE to meshCore.state.value,
            ),
        )

    /**
     * Begins consuming both radios.
     *
     * Each slot is collected in its own coroutine under a [SupervisorJob], so a
     * failure while handling one radio's traffic cannot cancel the other's
     * collector.
     */
    fun start() {
        if (started) return
        started = true
        listOf(meshtastic, meshCore).forEach { session ->
            scope.launch { collectIncoming(session) }
            scope.launch { collectDeliveries(session) }
        }
    }

    private suspend fun collectIncoming(session: RadioSession) {
        session.incomingMessages.collect { message ->
            try {
                store.saveIncoming(message)
                incomingListener?.onMessage(message)
                runBridge(message, session)
            } catch (e: Exception) {
                // Swallowing here is deliberate: an exception escaping this
                // collector would cancel it and leave one radio silently deaf
                // while the other kept working.
                diagnostics("failed to handle ${session.protocol.displayName} message: ${e.message}")
            }
        }
    }

    private suspend fun collectDeliveries(session: RadioSession) {
        session.deliveryUpdates.collect { update ->
            runCatching { store.updateDeliveryState(update.messageId, update.state, update.detail) }
                .onFailure { diagnostics("failed to record delivery update: ${it.message}") }
        }
    }

    private suspend fun runBridge(message: UnifiedMessage, source: RadioSession) {
        val config = bridgeConfig.value
        when (val decision = bridgeEngine.evaluate(message, config, selfNodeId = source.selfNodeId)) {
            is BridgeDecision.Skip -> {
                // Recorded at debug level: on a busy channel with the bridge off,
                // every message produces one of these.
                diagnostics("bridge skipped ${source.protocol.shortLabel} message: ${decision.reason}")
            }

            is BridgeDecision.Relay -> {
                val target = session(decision.outgoing.protocol)
                if (!target.state.value.isConnected) {
                    diagnostics(
                        "bridge could not relay to ${decision.outgoing.protocol.displayName}: not connected",
                    )
                    return
                }
                val stored = decision.outgoing.toStoredMessage(
                    senderId = source.selfNodeId ?: LOCAL_SENDER_ID,
                    now = clock.nowMillis(),
                )
                store.saveOutgoing(stored)
                val result = target.send(decision.outgoing)
                store.updateDeliveryState(
                    stored.id,
                    when (result) {
                        is SendResult.Accepted -> DeliveryState.SENT
                        is SendResult.Failed -> DeliveryState.FAILED
                    },
                    (result as? SendResult.Failed)?.reason,
                )
                diagnostics(
                    "bridged ${source.protocol.shortLabel} -> ${decision.outgoing.protocol.shortLabel} " +
                        "via rule ${decision.rule.id}, hop ${decision.hopCount}: $result",
                )
            }
        }
    }

    /**
     * Sends [text] to [target].
     *
     * When [target] is [SendTarget.BOTH] the two radios transmit **independently
     * and concurrently** — each gets its own copy straight from the phone.
     * Neither relays for the other, and a failure on one does not affect the
     * other's result, which is why the two sends are separate coroutines with
     * their own error handling rather than a sequential loop that could abort
     * halfway.
     */
    suspend fun send(
        target: SendTarget,
        text: String,
        destinations: Map<MeshProtocol, SendDestination>,
        wantAck: Boolean = true,
    ): List<SendAttempt> = coroutineScope {
        val now = clock.nowMillis()
        target.protocols()
            .mapNotNull { protocol -> destinations[protocol]?.let { protocol to it } }
            .map { (protocol, destination) ->
                async {
                    val message = OutgoingMessage(
                        id = "out-${UUID.randomUUID()}",
                        protocol = protocol,
                        destinationId = destination.nodeId,
                        channelId = destination.channelId,
                        text = text,
                        timestamp = now,
                        wantAck = wantAck,
                    )
                    val session = session(protocol)
                    val stored = message.toStoredMessage(
                        senderId = session.selfNodeId ?: LOCAL_SENDER_ID,
                        now = now,
                    )
                    store.saveOutgoing(stored)

                    val result = session.send(message)
                    store.updateDeliveryState(
                        stored.id,
                        when (result) {
                            is SendResult.Accepted -> DeliveryState.SENT
                            is SendResult.Failed -> DeliveryState.FAILED
                        },
                        (result as? SendResult.Failed)?.reason,
                    )
                    SendAttempt(protocol, stored.id, result)
                }
            }
            .awaitAll()
    }

    /** Releases the coordinator and both sessions. */
    fun shutdown() {
        meshtastic.shutdown()
        meshCore.shutdown()
        scope.cancel()
    }

    private companion object {
        /**
         * Sender id used before a radio has told us its own identity.
         *
         * A stored outbound message needs a sender, and using the empty string
         * would make it look like it came from an unknown peer.
         */
        const val LOCAL_SENDER_ID = "local"
    }
}

/** Where a composed message is going on one network. */
data class SendDestination(
    /** Direct-message peer id, or null for a channel message. */
    val nodeId: String? = null,
    /** Channel id, or null for a direct message. */
    val channelId: String? = null,
) {
    init {
        require((nodeId == null) != (channelId == null)) {
            "A send destination is either a node or a channel"
        }
    }
}

/** The stored record for a message this phone transmitted. */
internal fun OutgoingMessage.toStoredMessage(senderId: String, now: Long): UnifiedMessage {
    val key = channelId?.let { ConversationKey.channel(protocol, it) }
        ?: ConversationKey.direct(protocol, requireNotNull(destinationId))
    return UnifiedMessage(
        id = id,
        protocol = protocol,
        conversationId = key.asId(),
        senderId = senderId,
        senderName = null,
        destinationId = destinationId,
        channelId = channelId,
        text = text,
        timestamp = now,
        direction = MessageDirection.OUTGOING,
        deliveryState = DeliveryState.SENDING,
        bridged = bridgeMetadata != null,
        originalProtocol = bridgeMetadata?.originProtocol,
        bridgeId = bridgeMetadata?.bridgeId,
        hopCount = bridgeMetadata?.hopCount ?: 0,
    )
}
