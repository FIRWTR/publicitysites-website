package com.unifiedmesh.core.model

/** Where the composer should send a message. */
enum class SendTarget {
    MESHTASTIC,
    MESHCORE,

    /**
     * Transmit independently over both radios.
     *
     * This is *not* bridging: each radio gets its own copy directly from the
     * phone. Neither radio relays for the other.
     */
    BOTH,
    ;

    fun protocols(): List<MeshProtocol> = when (this) {
        MESHTASTIC -> listOf(MeshProtocol.MESHTASTIC)
        MESHCORE -> listOf(MeshProtocol.MESHCORE)
        BOTH -> listOf(MeshProtocol.MESHTASTIC, MeshProtocol.MESHCORE)
    }
}

/**
 * A message handed to a single adapter for transmission.
 *
 * Exactly one of [destinationId] / [channelId] is non-null.
 */
data class OutgoingMessage(
    /** App-level id; becomes [UnifiedMessage.id] of the stored outbound record. */
    val id: String,
    val protocol: MeshProtocol,
    val destinationId: String? = null,
    val channelId: String? = null,
    val text: String,
    /** Epoch millis the operator composed it. */
    val timestamp: Long,
    /** Ask the network for a delivery acknowledgement where the protocol supports it. */
    val wantAck: Boolean = true,
    /** Set when this transmission was produced by the bridge. */
    val bridgeMetadata: BridgeMetadata? = null,
) {
    init {
        require((destinationId == null) != (channelId == null)) {
            "OutgoingMessage must target exactly one of destinationId or channelId"
        }
    }
}

/** Bridge provenance attached to a relayed transmission. */
data class BridgeMetadata(
    val bridgeId: String,
    val originProtocol: MeshProtocol,
    val originNodeId: String,
    val originSenderName: String?,
    val hopCount: Int,
)

/** Result of handing one [OutgoingMessage] to one radio. */
sealed interface SendResult {
    /** The radio accepted the packet. [radioMessageId] is the protocol's own id when it returns one. */
    data class Accepted(val radioMessageId: String? = null) : SendResult

    data class Failed(val reason: String, val retryable: Boolean) : SendResult
}

/**
 * Per-protocol outcome of a composer send, so the UI can show
 * `Meshtastic ✓ Sent` / `MeshCore ✕ Failed` independently.
 */
data class SendAttempt(
    val protocol: MeshProtocol,
    val messageId: String,
    val result: SendResult,
)
