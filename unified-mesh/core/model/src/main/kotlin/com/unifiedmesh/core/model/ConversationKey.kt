package com.unifiedmesh.core.model

/** Whether a conversation is a 1:1 thread or a channel/group. */
enum class ConversationKind { DIRECT, CHANNEL }

/**
 * Identifies a conversation thread.
 *
 * Conversations are always scoped to a single protocol. A Meshtastic "LongFast"
 * channel and a MeshCore "Public" channel are two different threads even when
 * a bridge rule copies text between them — the operator needs to see which
 * network each message actually travelled over.
 */
data class ConversationKey(
    val protocol: MeshProtocol,
    val kind: ConversationKind,
    /** Peer node id for [ConversationKind.DIRECT], channel id for [ConversationKind.CHANNEL]. */
    val peerOrChannelId: String,
) {
    /** Stable string form, used as the Room primary key. */
    fun asId(): String = "${protocol.name}:${kind.name}:$peerOrChannelId"

    companion object {
        fun direct(protocol: MeshProtocol, peerId: String) =
            ConversationKey(protocol, ConversationKind.DIRECT, peerId)

        fun channel(protocol: MeshProtocol, channelId: String) =
            ConversationKey(protocol, ConversationKind.CHANNEL, channelId)

        /** Parses the [asId] form. Returns null for malformed input. */
        fun parse(id: String): ConversationKey? {
            val parts = id.split(':', limit = 3)
            if (parts.size != 3) return null
            val protocol = MeshProtocol.entries.firstOrNull { it.name == parts[0] } ?: return null
            val kind = ConversationKind.entries.firstOrNull { it.name == parts[1] } ?: return null
            return ConversationKey(protocol, kind, parts[2])
        }
    }
}

/** A conversation thread with the denormalised fields the inbox list needs. */
data class Conversation(
    val key: ConversationKey,
    val title: String,
    val lastMessageText: String?,
    val lastMessageAt: Long,
    val unreadCount: Int,
) {
    val id: String get() = key.asId()
    val protocol: MeshProtocol get() = key.protocol
}
