package com.unifiedmesh.core.model

/** Which way a message travelled relative to this phone. */
enum class MessageDirection { INCOMING, OUTGOING }

/**
 * Delivery lifecycle of a message.
 *
 * Both protocols report delivery differently (Meshtastic routing ACKs vs
 * MeshCore's `RESP_CODE_SENT` + `PUSH_CODE_SEND_CONFIRMED`), so the adapters
 * normalise onto these states.
 */
enum class DeliveryState {
    /** Composed locally, not yet handed to a radio. */
    QUEUED,

    /** Written to the radio; the radio has not confirmed transmission yet. */
    SENDING,

    /** The radio confirmed it put the packet on the air. */
    SENT,

    /** The destination (or an intermediate router) acknowledged receipt. */
    DELIVERED,

    /** Transmission failed, or the ACK window expired. */
    FAILED,

    /** Not applicable — this is an inbound message. */
    RECEIVED,
}

/**
 * Protocol-independent message record.
 *
 * This is the *only* message type the UI layer sees. Protocol-specific detail
 * (Meshtastic `MeshPacket` fields, MeshCore public-key prefixes, …) is either
 * normalised into these fields by the adapters or dropped.
 *
 * @param id stable app-level identifier, unique across both networks.
 * @param protocol which radio this message was received on / will be sent on.
 * @param conversationId the thread this message belongs to; see [ConversationKey].
 * @param senderId protocol-scoped sender identity (Meshtastic node num as `!hex`,
 *   MeshCore 6-byte public key prefix as hex). Never merged across protocols.
 * @param senderName best-known display name at the time of receipt, or null.
 * @param destinationId protocol-scoped destination identity for direct messages,
 *   null for channel/broadcast traffic.
 * @param channelId protocol-scoped channel identity (Meshtastic channel index,
 *   MeshCore channel index), null for direct messages.
 * @param text the message body.
 * @param timestamp epoch milliseconds. For inbound traffic this is the radio's
 *   reported timestamp when it supplies one, otherwise local receipt time.
 * @param direction inbound or outbound.
 * @param deliveryState current delivery lifecycle state.
 * @param bridged true when this message was put on this network by the app's
 *   bridge rather than composed by the local operator.
 * @param originalProtocol for bridged messages, the network the text came from.
 * @param bridgeId for bridged messages, the bridge transaction id. Also stored
 *   on the *source* message so both halves of a bridged pair can be correlated.
 * @param hopCount bridge hop count (not radio hops); 0 for locally originated
 *   traffic. See [com.unifiedmesh.core.bridge.BridgeEngine].
 * @param snr radio signal-to-noise ratio in dB when the radio reported one.
 * @param rssi received signal strength in dBm when the radio reported one.
 */
data class UnifiedMessage(
    val id: String,
    val protocol: MeshProtocol,
    val conversationId: String,
    val senderId: String,
    val senderName: String? = null,
    val destinationId: String? = null,
    val channelId: String? = null,
    val text: String,
    val timestamp: Long,
    val direction: MessageDirection,
    val deliveryState: DeliveryState,
    val bridged: Boolean = false,
    val originalProtocol: MeshProtocol? = null,
    val bridgeId: String? = null,
    val hopCount: Int = 0,
    val snr: Float? = null,
    val rssi: Int? = null,
) {
    val isDirect: Boolean get() = channelId == null
}
