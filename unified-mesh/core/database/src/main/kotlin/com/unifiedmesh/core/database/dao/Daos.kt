package com.unifiedmesh.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.unifiedmesh.core.database.entity.BridgeRuleEntity
import com.unifiedmesh.core.database.entity.BridgeSeenEntity
import com.unifiedmesh.core.database.entity.ConversationEntity
import com.unifiedmesh.core.database.entity.DiagnosticEntity
import com.unifiedmesh.core.database.entity.MessageEntity
import com.unifiedmesh.core.database.entity.NodeEntity
import com.unifiedmesh.core.database.entity.RadioAssignmentEntity
import com.unifiedmesh.core.model.DeliveryState
import com.unifiedmesh.core.model.MeshProtocol
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /**
     * IGNORE rather than REPLACE: a flood-routed mesh delivers the same message
     * several times, and the ids are content-derived, so the second copy is a
     * duplicate to drop rather than an update to apply.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringDuplicates(message: MessageEntity): Long

    @Upsert
    suspend fun upsert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeConversation(conversationId: String): Flow<List<MessageEntity>>

    /** The unified inbox: newest first, across both networks. */
    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE protocol = :protocol ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecentForProtocol(protocol: MeshProtocol, limit: Int = 500): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun byId(id: String): MessageEntity?

    @Query("UPDATE messages SET deliveryState = :state, deliveryDetail = :detail WHERE id = :id")
    suspend fun updateDeliveryState(id: String, state: DeliveryState, detail: String?)

    @Query("UPDATE messages SET read = 1 WHERE conversationId = :conversationId")
    suspend fun markConversationRead(conversationId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND read = 0")
    suspend fun unreadCount(conversationId: String): Int

    @Query("SELECT COUNT(*) FROM messages WHERE read = 0")
    fun observeTotalUnread(): Flow<Int>

    /** Trims history so a long deployment cannot fill the phone. */
    @Query(
        """
        DELETE FROM messages WHERE id IN (
            SELECT id FROM messages ORDER BY timestamp DESC LIMIT -1 OFFSET :keep
        )
        """,
    )
    suspend fun trimTo(keep: Int)
}

@Dao
interface ConversationDao {

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations ORDER BY lastMessageAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE protocol = :protocol ORDER BY lastMessageAt DESC")
    fun observeForProtocol(protocol: MeshProtocol): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun observeById(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun byId(id: String): ConversationEntity?

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :id")
    suspend fun clearUnread(id: String)
}

@Dao
interface NodeDao {

    @Upsert
    suspend fun upsertAll(nodes: List<NodeEntity>)

    @Query("SELECT * FROM nodes ORDER BY protocol, longName COLLATE NOCASE")
    fun observeAll(): Flow<List<NodeEntity>>

    @Query("SELECT * FROM nodes WHERE protocol = :protocol ORDER BY longName COLLATE NOCASE")
    fun observeForProtocol(protocol: MeshProtocol): Flow<List<NodeEntity>>

    /** Nodes with a usable position, for the map. */
    @Query("SELECT * FROM nodes WHERE latitude IS NOT NULL AND longitude IS NOT NULL")
    fun observePositioned(): Flow<List<NodeEntity>>

    @Query("SELECT * FROM nodes WHERE protocol = :protocol AND nodeId = :nodeId")
    suspend fun byId(protocol: MeshProtocol, nodeId: String): NodeEntity?

    @Query("DELETE FROM nodes WHERE protocol = :protocol")
    suspend fun clearProtocol(protocol: MeshProtocol)
}

@Dao
interface RadioAssignmentDao {

    @Upsert
    suspend fun upsert(assignment: RadioAssignmentEntity)

    @Query("SELECT * FROM radio_assignments")
    fun observeAll(): Flow<List<RadioAssignmentEntity>>

    @Query("SELECT * FROM radio_assignments")
    suspend fun all(): List<RadioAssignmentEntity>

    @Query("SELECT * FROM radio_assignments WHERE protocol = :protocol")
    suspend fun forProtocol(protocol: MeshProtocol): RadioAssignmentEntity?

    @Query("DELETE FROM radio_assignments WHERE protocol = :protocol")
    suspend fun clear(protocol: MeshProtocol)
}

@Dao
interface BridgeSeenDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: BridgeSeenEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM bridge_seen WHERE fingerprint = :fingerprint)")
    suspend fun hasFingerprint(fingerprint: String): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM bridge_seen WHERE bridgeId = :bridgeId)")
    suspend fun hasBridgeId(bridgeId: String): Boolean

    @Query("DELETE FROM bridge_seen WHERE seenAtMillis < :cutoffMillis")
    suspend fun purgeOlderThan(cutoffMillis: Long)

    @Query("SELECT COUNT(*) FROM bridge_seen")
    suspend fun count(): Int
}

@Dao
interface BridgeRuleDao {

    @Upsert
    suspend fun upsert(rule: BridgeRuleEntity)

    @Query("SELECT * FROM bridge_rules")
    fun observeAll(): Flow<List<BridgeRuleEntity>>

    @Query("SELECT * FROM bridge_rules")
    suspend fun all(): List<BridgeRuleEntity>

    @Query("DELETE FROM bridge_rules WHERE id = :id")
    suspend fun delete(id: String)

    @Transaction
    suspend fun replaceAll(rules: List<BridgeRuleEntity>) {
        deleteAll()
        rules.forEach { upsert(it) }
    }

    @Query("DELETE FROM bridge_rules")
    suspend fun deleteAll()
}

@Dao
interface DiagnosticDao {

    @Insert
    suspend fun insert(event: DiagnosticEntity)

    @Query("SELECT * FROM diagnostics ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<DiagnosticEntity>>

    @Query("SELECT * FROM diagnostics ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int = 2000): List<DiagnosticEntity>

    @Query("DELETE FROM diagnostics")
    suspend fun clear()

    /** Keeps the log bounded; diagnostics are high-volume and disposable. */
    @Query(
        """
        DELETE FROM diagnostics WHERE id IN (
            SELECT id FROM diagnostics ORDER BY timestamp DESC LIMIT -1 OFFSET :keep
        )
        """,
    )
    suspend fun trimTo(keep: Int)
}
