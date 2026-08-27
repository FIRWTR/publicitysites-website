package com.unifiedmesh.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.unifiedmesh.core.model.ConversationKey
import com.unifiedmesh.core.model.DeliveryState
import com.unifiedmesh.core.model.MeshNode
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.MessageDirection
import com.unifiedmesh.core.model.NodePosition
import com.unifiedmesh.core.model.RadioDevice
import com.unifiedmesh.core.model.RadioTransport
import com.unifiedmesh.core.model.UnifiedMessage

/**
 * A stored message.
 *
 * Rows carry protocol-independent fields only. Nothing here holds a channel PSK,
 * a MeshCore channel secret, or any other key material — that stays inside the
 * protocol layer and is never written to disk by this app.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index("conversationId", "timestamp"),
        Index("timestamp"),
        Index("protocol"),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val protocol: MeshProtocol,
    val conversationId: String,
    val senderId: String,
    val senderName: String?,
    val destinationId: String?,
    val channelId: String?,
    val text: String,
    val timestamp: Long,
    val direction: MessageDirection,
    val deliveryState: DeliveryState,
    val deliveryDetail: String? = null,
    val bridged: Boolean = false,
    val originalProtocol: MeshProtocol? = null,
    val bridgeId: String? = null,
    val hopCount: Int = 0,
    val snr: Float? = null,
    val rssi: Int? = null,
    val read: Boolean = false,
) {
    fun toModel() = UnifiedMessage(
        id = id,
        protocol = protocol,
        conversationId = conversationId,
        senderId = senderId,
        senderName = senderName,
        destinationId = destinationId,
        channelId = channelId,
        text = text,
        timestamp = timestamp,
        direction = direction,
        deliveryState = deliveryState,
        bridged = bridged,
        originalProtocol = originalProtocol,
        bridgeId = bridgeId,
        hopCount = hopCount,
        snr = snr,
        rssi = rssi,
    )

    companion object {
        fun from(message: UnifiedMessage, read: Boolean = false) = MessageEntity(
            id = message.id,
            protocol = message.protocol,
            conversationId = message.conversationId,
            senderId = message.senderId,
            senderName = message.senderName,
            destinationId = message.destinationId,
            channelId = message.channelId,
            text = message.text,
            timestamp = message.timestamp,
            direction = message.direction,
            deliveryState = message.deliveryState,
            bridged = message.bridged,
            originalProtocol = message.originalProtocol,
            bridgeId = message.bridgeId,
            hopCount = message.hopCount,
            snr = message.snr,
            rssi = message.rssi,
            // Anything this phone sent has by definition been seen by its operator.
            read = read || message.direction == MessageDirection.OUTGOING,
        )
    }
}

/**
 * A conversation thread.
 *
 * Kept as its own table rather than derived from messages so the inbox list can
 * be read with one indexed query instead of a group-by over the whole message
 * history, which grows without bound in the field.
 */
@Entity(tableName = "conversations", indices = [Index("lastMessageAt")])
data class ConversationEntity(
    @PrimaryKey val id: String,
    val protocol: MeshProtocol,
    val kind: String,
    val peerOrChannelId: String,
    val title: String,
    val lastMessageText: String?,
    val lastMessageAt: Long,
    val unreadCount: Int,
) {
    companion object {
        fun forKey(key: ConversationKey, title: String, message: UnifiedMessage, unreadCount: Int) =
            ConversationEntity(
                id = key.asId(),
                protocol = key.protocol,
                kind = key.kind.name,
                peerOrChannelId = key.peerOrChannelId,
                title = title,
                lastMessageText = message.text,
                lastMessageAt = message.timestamp,
                unreadCount = unreadCount,
            )
    }
}

/**
 * A node or contact.
 *
 * The primary key is protocol plus protocol-scoped id: identities are never
 * merged across networks, even when the display names match.
 */
@Entity(tableName = "nodes", primaryKeys = ["protocol", "nodeId"])
data class NodeEntity(
    val protocol: MeshProtocol,
    val nodeId: String,
    val longName: String?,
    val shortName: String?,
    val lastHeard: Long?,
    val latitude: Double?,
    val longitude: Double?,
    val altitudeMeters: Int?,
    val positionTimestamp: Long?,
    val batteryLevel: Int?,
    val snr: Float?,
    val hopsAway: Int?,
    val isSelf: Boolean,
    /** When this app last saw the node, so stale entries can be aged out. */
    val updatedAt: Long,
) {
    fun toModel() = MeshNode(
        protocol = protocol,
        id = nodeId,
        longName = longName,
        shortName = shortName,
        lastHeard = lastHeard,
        position = if (latitude != null && longitude != null) {
            NodePosition(latitude, longitude, altitudeMeters, positionTimestamp).takeIf { it.isValid }
        } else {
            null
        },
        batteryLevel = batteryLevel,
        snr = snr,
        hopsAway = hopsAway,
        isSelf = isSelf,
    )

    companion object {
        fun from(node: MeshNode, updatedAt: Long) = NodeEntity(
            protocol = node.protocol,
            nodeId = node.id,
            longName = node.longName,
            shortName = node.shortName,
            lastHeard = node.lastHeard,
            latitude = node.position?.latitude,
            longitude = node.position?.longitude,
            altitudeMeters = node.position?.altitudeMeters,
            positionTimestamp = node.position?.timestamp,
            batteryLevel = node.batteryLevel,
            snr = node.snr,
            hopsAway = node.hopsAway,
            isSelf = node.isSelf,
            updatedAt = updatedAt,
        )
    }
}

/**
 * Which physical device is assigned to which protocol slot.
 *
 * One row per protocol, so re-assigning a slot replaces rather than accumulates.
 */
@Entity(tableName = "radio_assignments")
data class RadioAssignmentEntity(
    @PrimaryKey val protocol: MeshProtocol,
    val address: String,
    val name: String?,
    val transport: RadioTransport,
    /** Reconnect to this radio automatically when the service starts. */
    val autoConnect: Boolean = true,
    val assignedAt: Long,
) {
    fun toDevice() = RadioDevice(
        address = address,
        name = name,
        transport = transport,
        assignedProtocol = protocol,
    )
}

/**
 * The bridge's recently-seen cache, persisted.
 *
 * It survives process death deliberately: a phone killed mid-conversation and
 * restarted would otherwise have an empty cache and re-relay everything it heard
 * again.
 */
@Entity(tableName = "bridge_seen", indices = [Index("seenAtMillis"), Index("bridgeId")])
data class BridgeSeenEntity(
    @PrimaryKey val fingerprint: String,
    val bridgeId: String?,
    val protocol: MeshProtocol,
    val seenAtMillis: Long,
)

/** A configured channel-to-channel bridge mapping. */
@Entity(tableName = "bridge_rules")
data class BridgeRuleEntity(
    @PrimaryKey val id: String,
    val enabled: Boolean,
    val fromProtocol: MeshProtocol,
    val fromChannelId: String?,
    val toProtocol: MeshProtocol,
    val toChannelId: String,
    val label: String,
)

/**
 * A developer diagnostics entry.
 *
 * Message bodies and key material never reach this table; see
 * [com.unifiedmesh.core.model.DiagnosticEvent].
 */
@Entity(tableName = "diagnostics", indices = [Index("timestamp")])
data class DiagnosticEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val level: String,
    val category: String,
    val protocol: MeshProtocol?,
    val message: String,
    val detail: String?,
)
