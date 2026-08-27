package com.unifiedmesh.protocol.meshtastic

import com.google.protobuf.ByteString
import com.unifiedmesh.core.model.ConversationKey
import com.unifiedmesh.core.model.DeliveryState
import com.unifiedmesh.core.model.MeshNode
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.MessageClass
import com.unifiedmesh.core.model.MessageDirection
import com.unifiedmesh.core.model.NodePosition
import com.unifiedmesh.core.model.OutgoingMessage
import com.unifiedmesh.core.model.UnifiedMessage
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.Portnums
import kotlin.random.Random

/**
 * Converts between Meshtastic protobufs and the app's protocol-independent
 * model.
 *
 * Pure and stateless; the conversation with the radio lives in
 * [MeshtasticSession] and [MeshtasticAdapter].
 */
object MeshtasticCodec {

    /**
     * Classifies a received packet by port number.
     *
     * This is the gate that keeps non-chat traffic out of the inbox and
     * therefore out of the bridge. Anything that is not
     * [Portnums.PortNum.TEXT_MESSAGE_APP] is recorded and routed elsewhere.
     */
    fun classify(packet: MeshProtos.MeshPacket): MessageClass {
        if (!packet.hasDecoded()) {
            // An encrypted packet is one this radio holds no key for. It is not
            // ours to read, let alone relay.
            return MessageClass.OTHER
        }
        return when (packet.decoded.portnum) {
            Portnums.PortNum.TEXT_MESSAGE_APP -> MessageClass.TEXT
            Portnums.PortNum.POSITION_APP -> MessageClass.POSITION
            Portnums.PortNum.NODEINFO_APP -> MessageClass.NODE_INFO
            Portnums.PortNum.TELEMETRY_APP -> MessageClass.TELEMETRY
            Portnums.PortNum.ROUTING_APP -> MessageClass.ROUTING
            Portnums.PortNum.ADMIN_APP -> MessageClass.ADMIN
            else -> MessageClass.OTHER
        }
    }

    /**
     * Converts a received text packet into a [UnifiedMessage].
     *
     * Returns null when the packet is not plain text — callers should have
     * checked [classify] first, but this makes it impossible to convert, say, a
     * telemetry packet into an inbox entry by mistake.
     *
     * @param selfNodeNum this radio's own node number, used to tell a direct
     *   message addressed to us from one we are merely overhearing.
     * @param receivedAtMillis local receipt time, used when the packet carries no
     *   `rx_time` (the firmware omits it when its clock is unset).
     */
    fun toUnifiedMessage(
        packet: MeshProtos.MeshPacket,
        selfNodeNum: Long,
        nameLookup: (Long) -> String? = { null },
        receivedAtMillis: Long,
    ): UnifiedMessage? {
        if (classify(packet) != MessageClass.TEXT) return null

        val text = packet.decoded.payload.toStringUtf8()
        if (text.isEmpty()) return null

        val from = packet.from.toLong() and 0xFFFFFFFFL
        val to = packet.to.toLong() and 0xFFFFFFFFL
        val senderId = MeshtasticProtocol.formatNodeId(from)
        val isBroadcast = to == MeshtasticProtocol.BROADCAST_ADDRESS

        val channelId = if (isBroadcast) packet.channel.toString() else null
        val destinationId = if (isBroadcast) null else MeshtasticProtocol.formatNodeId(to)

        val conversation = if (isBroadcast) {
            ConversationKey.channel(MeshProtocol.MESHTASTIC, packet.channel.toString())
        } else {
            // A direct message threads under the *other* party, whichever end we are.
            val peer = if (from == selfNodeNum) to else from
            ConversationKey.direct(MeshProtocol.MESHTASTIC, MeshtasticProtocol.formatNodeId(peer))
        }

        // rx_time is seconds since epoch and is optional: the firmware only sets
        // it once it has a clock. Falling back to local receipt time keeps the
        // inbox ordered rather than dumping everything at 1970.
        val timestamp = if (packet.hasRxTime() && packet.rxTime != 0) {
            (packet.rxTime.toLong() and 0xFFFFFFFFL) * 1000
        } else {
            receivedAtMillis
        }

        return UnifiedMessage(
            id = messageId(packet),
            protocol = MeshProtocol.MESHTASTIC,
            conversationId = conversation.asId(),
            senderId = senderId,
            senderName = nameLookup(from),
            destinationId = destinationId,
            channelId = channelId,
            text = text,
            timestamp = timestamp,
            direction = if (from == selfNodeNum) MessageDirection.OUTGOING else MessageDirection.INCOMING,
            deliveryState = if (from == selfNodeNum) DeliveryState.SENT else DeliveryState.RECEIVED,
            snr = packet.rxSnr.takeIf { it != 0f },
            rssi = if (packet.hasRxRssi() && packet.rxRssi != 0) packet.rxRssi else null,
        )
    }

    /**
     * Builds a `ToRadio` carrying [message] as a text packet.
     *
     * @param packetId caller-supplied packet id. It must be non-zero and is what
     *   correlates a later `ROUTING_APP` acknowledgement back to this message,
     *   so the adapter keeps it rather than letting the firmware assign one.
     */
    fun toRadioTextPacket(
        message: OutgoingMessage,
        packetId: Int,
        hopLimit: Int = DEFAULT_HOP_LIMIT,
    ): MeshProtos.ToRadio {
        val destination = when {
            message.channelId != null -> MeshtasticProtocol.BROADCAST_ADDRESS
            else -> MeshtasticProtocol.parseNodeId(requireNotNull(message.destinationId))
                ?: throw IllegalArgumentException("Not a Meshtastic node id: ${message.destinationId}")
        }
        val channelIndex = message.channelId?.toIntOrNull() ?: 0

        val data = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
            .setPayload(ByteString.copyFromUtf8(message.text))
            .build()

        val packet = MeshProtos.MeshPacket.newBuilder()
            .setTo(destination.toInt())
            .setChannel(channelIndex)
            .setId(packetId)
            .setDecoded(data)
            // Broadcast traffic must not request an ACK: on a busy channel every
            // receiver would answer and the airtime cost is unbounded.
            .setWantAck(message.wantAck && message.channelId == null)
            .setHopLimit(hopLimit)
            .build()

        return MeshProtos.ToRadio.newBuilder().setPacket(packet).build()
    }

    /** `ToRadio` requesting the configuration stream. */
    fun wantConfig(nonce: Int): MeshProtos.ToRadio =
        MeshProtos.ToRadio.newBuilder().setWantConfigId(nonce).build()

    /** `ToRadio` keeping the radio's phone-API session alive. */
    fun heartbeat(): MeshProtos.ToRadio =
        MeshProtos.ToRadio.newBuilder().setHeartbeat(MeshProtos.Heartbeat.getDefaultInstance()).build()

    /** `ToRadio` telling the radio the client is going away. */
    fun disconnect(): MeshProtos.ToRadio =
        MeshProtos.ToRadio.newBuilder().setDisconnect(true).build()

    /** Converts a `NodeInfo` from the radio's node database. */
    fun toMeshNode(nodeInfo: MeshProtos.NodeInfo, selfNodeNum: Long): MeshNode {
        val num = nodeInfo.num.toLong() and 0xFFFFFFFFL
        val user = nodeInfo.user
        return MeshNode(
            protocol = MeshProtocol.MESHTASTIC,
            // Prefer the id the node reports for itself; fall back to its number.
            id = user.id.takeIf { it.isNotBlank() } ?: MeshtasticProtocol.formatNodeId(num),
            longName = user.longName.takeIf { it.isNotBlank() },
            shortName = user.shortName.takeIf { it.isNotBlank() },
            lastHeard = (nodeInfo.lastHeard.toLong() and 0xFFFFFFFFL).takeIf { it > 0 }?.times(1000),
            position = nodeInfo.takeIf { it.hasPosition() }?.position?.toNodePosition(),
            batteryLevel = nodeInfo.takeIf { it.hasDeviceMetrics() }
                ?.deviceMetrics
                ?.takeIf { it.hasBatteryLevel() }
                ?.batteryLevel
                // The firmware reports 101 for "plugged in, no battery"; showing
                // that as a charge percentage would be wrong.
                ?.takeIf { it in 0..100 },
            snr = nodeInfo.snr.takeIf { it != 0f },
            hopsAway = nodeInfo.takeIf { it.hasHopsAway() }?.hopsAway,
            isSelf = num == selfNodeNum,
        )
    }

    /**
     * Reads a `ROUTING_APP` packet as a delivery outcome.
     *
     * The firmware answers a `want_ack` packet with a Routing packet whose
     * `request_id` is the original packet id: `NONE` means delivered, anything
     * else is a failure with a reason.
     */
    fun toDeliveryOutcome(packet: MeshProtos.MeshPacket): DeliveryOutcome? {
        if (!packet.hasDecoded() || packet.decoded.portnum != Portnums.PortNum.ROUTING_APP) return null
        val requestId = packet.decoded.requestId
        if (requestId == 0) return null
        val routing = runCatching { MeshProtos.Routing.parseFrom(packet.decoded.payload) }.getOrNull()
            ?: return null
        val reason = routing.errorReason
        return DeliveryOutcome(
            packetId = requestId,
            state = if (reason == MeshProtos.Routing.Error.NONE) DeliveryState.DELIVERED else DeliveryState.FAILED,
            detail = if (reason == MeshProtos.Routing.Error.NONE) null else reason.name,
        )
    }

    /** Generates a non-zero packet id. Zero means "assign one for me" to the firmware. */
    fun newPacketId(random: Random = Random.Default): Int {
        var id = random.nextInt()
        while (id == 0) id = random.nextInt()
        return id
    }

    /**
     * Stable app-level id for a received packet.
     *
     * Meshtastic packet ids are unique per originating node, so node plus packet
     * id identifies a message across flood retransmissions — which is exactly the
     * de-duplication the inbox needs.
     */
    private fun messageId(packet: MeshProtos.MeshPacket): String {
        val from = packet.from.toLong() and 0xFFFFFFFFL
        val id = packet.id.toLong() and 0xFFFFFFFFL
        return "mt-%08x-%08x".format(from, id)
    }

    /**
     * Default hop limit for outgoing traffic.
     *
     * Three matches the firmware's own default and keeps a message inside a
     * reasonable neighbourhood rather than flooding the whole mesh.
     */
    const val DEFAULT_HOP_LIMIT = 3
}

/** The outcome of a previously sent packet, recovered from a Routing packet. */
data class DeliveryOutcome(
    val packetId: Int,
    val state: DeliveryState,
    val detail: String?,
)

/**
 * Converts a `Position`.
 *
 * Coordinates are 1e-7 degree fixed point and both fields are optional, so a
 * position with no fix maps to null rather than to 0,0.
 */
internal fun MeshProtos.Position.toNodePosition(): NodePosition? {
    if (!hasLatitudeI() || !hasLongitudeI()) return null
    val position = NodePosition(
        latitude = latitudeI * 1e-7,
        longitude = longitudeI * 1e-7,
        altitudeMeters = if (hasAltitude()) altitude else null,
        timestamp = (time.toLong() and 0xFFFFFFFFL).takeIf { it > 0 }?.times(1000),
    )
    return position.takeIf { it.isValid }
}
