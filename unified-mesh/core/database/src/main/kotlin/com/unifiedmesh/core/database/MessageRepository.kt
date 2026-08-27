package com.unifiedmesh.core.database

import com.unifiedmesh.core.database.dao.ConversationDao
import com.unifiedmesh.core.database.dao.MessageDao
import com.unifiedmesh.core.database.dao.NodeDao
import com.unifiedmesh.core.database.entity.ConversationEntity
import com.unifiedmesh.core.database.entity.MessageEntity
import com.unifiedmesh.core.database.entity.NodeEntity
import com.unifiedmesh.core.model.Clock
import com.unifiedmesh.core.model.Conversation
import com.unifiedmesh.core.model.ConversationKey
import com.unifiedmesh.core.model.ConversationKind
import com.unifiedmesh.core.model.DeliveryState
import com.unifiedmesh.core.model.MeshChannel
import com.unifiedmesh.core.model.MeshNode
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.MessageDirection
import com.unifiedmesh.core.model.UnifiedMessage
import com.unifiedmesh.core.radio.MessageStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed [MessageStore], plus the reads the UI needs.
 *
 * Conversation titles are resolved here rather than in the adapters, because the
 * best available name for a peer changes as the node database fills in and the
 * inbox should follow.
 */
@Singleton
class MessageRepository @Inject constructor(
    private val messages: MessageDao,
    private val conversations: ConversationDao,
    private val nodes: NodeDao,
    private val clock: Clock,
) : MessageStore {

    override suspend fun saveIncoming(message: UnifiedMessage) {
        // A flood-routed mesh delivers the same message more than once and ids are
        // content-derived, so a conflict here means "already have it".
        val inserted = messages.insertIgnoringDuplicates(MessageEntity.from(message))
        if (inserted == -1L) return
        touchConversation(message)
        messages.trimTo(MAX_STORED_MESSAGES)
    }

    override suspend fun saveOutgoing(message: UnifiedMessage) {
        messages.upsert(MessageEntity.from(message, read = true))
        touchConversation(message)
    }

    override suspend fun updateDeliveryState(messageId: String, state: DeliveryState, detail: String?) {
        messages.updateDeliveryState(messageId, state, detail)
    }

    private suspend fun touchConversation(message: UnifiedMessage) {
        val key = ConversationKey.parse(message.conversationId) ?: return
        val existing = conversations.byId(key.asId())
        // Only advance the preview when this really is the newest message: a
        // backlog drained out of order must not rewrite the inbox row.
        val isNewest = existing == null || message.timestamp >= existing.lastMessageAt
        conversations.upsert(
            ConversationEntity(
                id = key.asId(),
                protocol = key.protocol,
                kind = key.kind.name,
                peerOrChannelId = key.peerOrChannelId,
                title = resolveTitle(key, message, existing),
                lastMessageText = if (isNewest) message.text else existing?.lastMessageText,
                lastMessageAt = maxOf(message.timestamp, existing?.lastMessageAt ?: 0),
                unreadCount = messages.unreadCount(key.asId()),
            ),
        )
    }

    /**
     * Best available title for a thread.
     *
     * A channel keeps whatever name the radio reported; a direct thread prefers
     * the peer's name from the node database, then the name carried on the
     * message, and falls back to the raw id so a thread is never nameless.
     */
    private suspend fun resolveTitle(
        key: ConversationKey,
        message: UnifiedMessage,
        existing: ConversationEntity?,
    ): String = when (key.kind) {
        ConversationKind.CHANNEL ->
            existing?.title?.takeIf { it.isNotBlank() && it != key.peerOrChannelId }
                ?: "Channel ${key.peerOrChannelId}"

        ConversationKind.DIRECT -> {
            val peerId = key.peerOrChannelId
            nodes.byId(key.protocol, peerId)?.toModel()?.displayName
                ?: message.senderName?.takeIf { message.direction == MessageDirection.INCOMING }
                ?: existing?.title
                ?: peerId
        }
    }

    /** Renames a conversation to match a channel the radio reported. */
    suspend fun applyChannelNames(protocol: MeshProtocol, channels: List<MeshChannel>) {
        channels.forEach { channel ->
            val key = ConversationKey.channel(protocol, channel.id)
            val existing = conversations.byId(key.asId()) ?: return@forEach
            if (existing.title == channel.displayName) return@forEach
            conversations.upsert(existing.copy(title = channel.displayName))
        }
    }

    /** Stores the node list a radio reported. */
    suspend fun saveNodes(nodeList: List<MeshNode>) {
        if (nodeList.isEmpty()) return
        val now = clock.nowMillis()
        nodes.upsertAll(nodeList.map { NodeEntity.from(it, now) })
    }

    fun observeConversations(): Flow<List<Conversation>> =
        conversations.observeAll().map { rows -> rows.mapNotNull { it.toModel() } }

    fun observeConversations(protocol: MeshProtocol): Flow<List<Conversation>> =
        conversations.observeForProtocol(protocol).map { rows -> rows.mapNotNull { it.toModel() } }

    fun observeMessages(conversationId: String): Flow<List<UnifiedMessage>> =
        messages.observeConversation(conversationId).map { rows -> rows.map { it.toModel() } }

    fun observeRecentMessages(limit: Int = 500): Flow<List<UnifiedMessage>> =
        messages.observeRecent(limit).map { rows -> rows.map { it.toModel() } }

    fun observeTotalUnread(): Flow<Int> = messages.observeTotalUnread()

    fun observeNodes(): Flow<List<MeshNode>> =
        nodes.observeAll().map { rows -> rows.map { it.toModel() } }

    fun observePositionedNodes(): Flow<List<MeshNode>> =
        nodes.observePositioned().map { rows -> rows.map { it.toModel() } }

    suspend fun markRead(conversationId: String) {
        messages.markConversationRead(conversationId)
        conversations.clearUnread(conversationId)
    }

    private fun ConversationEntity.toModel(): Conversation? {
        val key = ConversationKey.parse(id) ?: return null
        return Conversation(
            key = key,
            title = title,
            lastMessageText = lastMessageText,
            lastMessageAt = lastMessageAt,
            unreadCount = unreadCount,
        )
    }

    private companion object {
        /**
         * Message history cap.
         *
         * Generous enough to cover a long deployment, bounded so the database
         * cannot grow without limit on a phone that is never cleared.
         */
        const val MAX_STORED_MESSAGES = 20_000
    }
}
