package com.unifiedmesh.core.database.di

import android.content.Context
import androidx.room.Room
import com.unifiedmesh.core.bridge.BridgeSeenCache
import com.unifiedmesh.core.database.PersistentBridgeSeenCache
import com.unifiedmesh.core.database.UnifiedMeshDatabase
import com.unifiedmesh.core.database.dao.BridgeRuleDao
import com.unifiedmesh.core.database.dao.BridgeSeenDao
import com.unifiedmesh.core.database.dao.ConversationDao
import com.unifiedmesh.core.database.dao.DiagnosticDao
import com.unifiedmesh.core.database.dao.MessageDao
import com.unifiedmesh.core.database.dao.NodeDao
import com.unifiedmesh.core.database.dao.RadioAssignmentDao
import com.unifiedmesh.core.model.Clock
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): UnifiedMeshDatabase =
        Room.databaseBuilder(context, UnifiedMeshDatabase::class.java, UnifiedMeshDatabase.NAME)
            // No fallbackToDestructiveMigration: message history is the point of
            // the app, and silently wiping it on a schema change would be worse
            // than a crash that gets a real migration written.
            .build()

    @Provides fun messageDao(db: UnifiedMeshDatabase): MessageDao = db.messageDao()

    @Provides fun conversationDao(db: UnifiedMeshDatabase): ConversationDao = db.conversationDao()

    @Provides fun nodeDao(db: UnifiedMeshDatabase): NodeDao = db.nodeDao()

    @Provides fun radioAssignmentDao(db: UnifiedMeshDatabase): RadioAssignmentDao = db.radioAssignmentDao()

    @Provides fun bridgeSeenDao(db: UnifiedMeshDatabase): BridgeSeenDao = db.bridgeSeenDao()

    @Provides fun bridgeRuleDao(db: UnifiedMeshDatabase): BridgeRuleDao = db.bridgeRuleDao()

    @Provides fun diagnosticDao(db: UnifiedMeshDatabase): DiagnosticDao = db.diagnosticDao()

    @Provides
    @Singleton
    fun clock(): Clock = Clock.System
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DatabaseBindings {

    @Binds
    @Singleton
    abstract fun bridgeSeenCache(impl: PersistentBridgeSeenCache): BridgeSeenCache
}
