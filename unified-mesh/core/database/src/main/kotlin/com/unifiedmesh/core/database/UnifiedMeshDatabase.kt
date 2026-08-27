package com.unifiedmesh.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.unifiedmesh.core.database.dao.BridgeRuleDao
import com.unifiedmesh.core.database.dao.BridgeSeenDao
import com.unifiedmesh.core.database.dao.ConversationDao
import com.unifiedmesh.core.database.dao.DiagnosticDao
import com.unifiedmesh.core.database.dao.MessageDao
import com.unifiedmesh.core.database.dao.NodeDao
import com.unifiedmesh.core.database.dao.RadioAssignmentDao
import com.unifiedmesh.core.database.entity.BridgeRuleEntity
import com.unifiedmesh.core.database.entity.BridgeSeenEntity
import com.unifiedmesh.core.database.entity.ConversationEntity
import com.unifiedmesh.core.database.entity.DiagnosticEntity
import com.unifiedmesh.core.database.entity.MessageEntity
import com.unifiedmesh.core.database.entity.NodeEntity
import com.unifiedmesh.core.database.entity.RadioAssignmentEntity
import com.unifiedmesh.core.model.DeliveryState
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.MessageDirection
import com.unifiedmesh.core.model.RadioTransport

/**
 * Enum converters.
 *
 * Enums are stored by name, not ordinal: reordering an enum is a routine edit
 * and must not silently reinterpret existing rows.
 */
class Converters {
    @TypeConverter fun protocolToString(value: MeshProtocol?): String? = value?.name

    @TypeConverter fun stringToProtocol(value: String?): MeshProtocol? =
        value?.let { name -> MeshProtocol.entries.firstOrNull { it.name == name } }

    @TypeConverter fun directionToString(value: MessageDirection): String = value.name

    @TypeConverter fun stringToDirection(value: String): MessageDirection =
        MessageDirection.entries.first { it.name == value }

    @TypeConverter fun deliveryToString(value: DeliveryState): String = value.name

    @TypeConverter fun stringToDelivery(value: String): DeliveryState =
        DeliveryState.entries.firstOrNull { it.name == value } ?: DeliveryState.RECEIVED

    @TypeConverter fun transportToString(value: RadioTransport): String = value.name

    @TypeConverter fun stringToTransport(value: String): RadioTransport =
        RadioTransport.entries.firstOrNull { it.name == value } ?: RadioTransport.BLE
}

@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        NodeEntity::class,
        RadioAssignmentEntity::class,
        BridgeSeenEntity::class,
        BridgeRuleEntity::class,
        DiagnosticEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class UnifiedMeshDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao

    abstract fun conversationDao(): ConversationDao

    abstract fun nodeDao(): NodeDao

    abstract fun radioAssignmentDao(): RadioAssignmentDao

    abstract fun bridgeSeenDao(): BridgeSeenDao

    abstract fun bridgeRuleDao(): BridgeRuleDao

    abstract fun diagnosticDao(): DiagnosticDao

    companion object {
        const val NAME = "unified-mesh.db"
    }
}
