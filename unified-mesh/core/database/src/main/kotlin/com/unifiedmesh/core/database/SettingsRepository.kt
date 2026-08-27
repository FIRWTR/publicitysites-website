package com.unifiedmesh.core.database

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.unifiedmesh.core.bridge.BridgeSeenCache
import com.unifiedmesh.core.bridge.BridgeSeenEntry
import com.unifiedmesh.core.database.dao.BridgeRuleDao
import com.unifiedmesh.core.database.dao.BridgeSeenDao
import com.unifiedmesh.core.database.dao.RadioAssignmentDao
import com.unifiedmesh.core.database.entity.BridgeRuleEntity
import com.unifiedmesh.core.database.entity.BridgeSeenEntity
import com.unifiedmesh.core.database.entity.RadioAssignmentEntity
import com.unifiedmesh.core.model.BridgeConfig
import com.unifiedmesh.core.model.BridgeRule
import com.unifiedmesh.core.model.Clock
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.RadioDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "unified-mesh-settings")

/** General app preferences, not tied to either protocol. */
data class GeneralSettings(
    val notificationsEnabled: Boolean = true,
    val backgroundOperationEnabled: Boolean = true,
    val keepRadiosConnected: Boolean = true,
    val meshtasticDefaultChannelId: String? = null,
    val meshCoreDefaultChannelId: String? = null,
    /**
     * Run against simulated radios instead of Bluetooth.
     *
     * Lets the whole app — inbox, composer, nodes, map, bridge, notifications,
     * the foreground service — be exercised on a phone with no hardware
     * attached. Off by default, and it never touches Bluetooth while on.
     */
    val demoMode: Boolean = false,
)

/**
 * Settings storage.
 *
 * Scalar preferences live in DataStore; bridge rules and radio assignments live
 * in Room because they are lists that want querying.
 *
 * **No secrets are stored here.** The app never reads a Meshtastic channel PSK
 * or a MeshCore channel secret out of the protocol layer, so there is nothing
 * sensitive to protect at this level; each radio keeps its own key material and
 * this app does not weaken that.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridgeRules: BridgeRuleDao,
    private val assignments: RadioAssignmentDao,
    private val clock: Clock,
) {

    val general: Flow<GeneralSettings> = context.dataStore.data.map { prefs ->
        GeneralSettings(
            notificationsEnabled = prefs[NOTIFICATIONS] ?: true,
            backgroundOperationEnabled = prefs[BACKGROUND] ?: true,
            keepRadiosConnected = prefs[KEEP_CONNECTED] ?: true,
            meshtasticDefaultChannelId = prefs[MT_DEFAULT_CHANNEL],
            meshCoreDefaultChannelId = prefs[MC_DEFAULT_CHANNEL],
            demoMode = prefs[DEMO_MODE] ?: false,
        )
    }

    /**
     * Bridge configuration, assembled from the scalar switches and the rule table.
     *
     * Every knob defaults to off. The bridge changes what goes on the air on two
     * networks at once, so it is never on until an operator says so.
     */
    val bridgeConfig: Flow<BridgeConfig> = combine(
        context.dataStore.data,
        bridgeRules.observeAll(),
    ) { prefs, rules ->
        BridgeConfig(
            masterEnabled = prefs[BRIDGE_MASTER] ?: false,
            meshtasticToMeshCore = prefs[BRIDGE_MT_TO_MC] ?: false,
            meshCoreToMeshtastic = prefs[BRIDGE_MC_TO_MT] ?: false,
            maxHops = prefs[BRIDGE_MAX_HOPS] ?: BridgeConfig.DEFAULT_MAX_HOPS,
            duplicateWindowMillis = prefs[BRIDGE_DUP_WINDOW]
                ?: BridgeConfig.DEFAULT_DUPLICATE_WINDOW_MILLIS,
            maxMessageAgeMillis = prefs[BRIDGE_MAX_AGE]
                ?: BridgeConfig.DEFAULT_MAX_MESSAGE_AGE_MILLIS,
            annotateRelayedText = prefs[BRIDGE_ANNOTATE] ?: true,
            rules = rules.map { it.toModel() },
        )
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) = putBoolean(NOTIFICATIONS, enabled)

    suspend fun setBackgroundOperationEnabled(enabled: Boolean) = putBoolean(BACKGROUND, enabled)

    suspend fun setKeepRadiosConnected(enabled: Boolean) = putBoolean(KEEP_CONNECTED, enabled)

    suspend fun setDemoMode(enabled: Boolean) = putBoolean(DEMO_MODE, enabled)

    suspend fun setDefaultChannel(protocol: MeshProtocol, channelId: String?) {
        val key = when (protocol) {
            MeshProtocol.MESHTASTIC -> MT_DEFAULT_CHANNEL
            MeshProtocol.MESHCORE -> MC_DEFAULT_CHANNEL
        }
        context.dataStore.edit { prefs ->
            if (channelId == null) prefs.remove(key) else prefs[key] = channelId
        }
    }

    suspend fun setBridgeMaster(enabled: Boolean) = putBoolean(BRIDGE_MASTER, enabled)

    suspend fun setBridgeDirection(from: MeshProtocol, enabled: Boolean) {
        val key = when (from) {
            MeshProtocol.MESHTASTIC -> BRIDGE_MT_TO_MC
            MeshProtocol.MESHCORE -> BRIDGE_MC_TO_MT
        }
        putBoolean(key, enabled)
    }

    suspend fun setBridgeAnnotate(enabled: Boolean) = putBoolean(BRIDGE_ANNOTATE, enabled)

    /**
     * Sets the bridge hop limit.
     *
     * Clamped: a large value re-opens exactly the loop the bridge exists to
     * prevent, and there is no operational reason to relay a message across more
     * than a couple of bridges.
     */
    suspend fun setBridgeMaxHops(hops: Int) {
        context.dataStore.edit { it[BRIDGE_MAX_HOPS] = hops.coerceIn(1, MAX_ALLOWED_HOPS) }
    }

    /** Sets the duplicate-suppression window, clamped to a sane range. */
    suspend fun setBridgeDuplicateWindow(millis: Long) {
        context.dataStore.edit {
            it[BRIDGE_DUP_WINDOW] = millis.coerceIn(MIN_DUPLICATE_WINDOW_MILLIS, MAX_DUPLICATE_WINDOW_MILLIS)
        }
    }

    suspend fun upsertBridgeRule(rule: BridgeRule) = bridgeRules.upsert(rule.toEntity())

    suspend fun deleteBridgeRule(id: String) = bridgeRules.delete(id)

    // --- Radio assignments --------------------------------------------------

    fun assignedRadios(): Flow<Map<MeshProtocol, RadioDevice>> =
        assignments.observeAll().map { rows -> rows.associate { it.protocol to it.toDevice() } }

    suspend fun assignRadio(protocol: MeshProtocol, device: RadioDevice, autoConnect: Boolean = true) {
        assignments.upsert(
            RadioAssignmentEntity(
                protocol = protocol,
                address = device.address,
                name = device.name,
                transport = device.transport,
                autoConnect = autoConnect,
                assignedAt = clock.nowMillis(),
            ),
        )
    }

    suspend fun clearAssignment(protocol: MeshProtocol) = assignments.clear(protocol)

    suspend fun assignmentsToAutoConnect(): List<Pair<MeshProtocol, RadioDevice>> =
        assignments.all().filter { it.autoConnect }.map { it.protocol to it.toDevice() }

    private suspend fun putBoolean(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) {
        context.dataStore.edit { it[key] = value }
    }

    private companion object {
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val BACKGROUND = booleanPreferencesKey("background_enabled")
        val KEEP_CONNECTED = booleanPreferencesKey("keep_radios_connected")
        val MT_DEFAULT_CHANNEL = stringPreferencesKey("meshtastic_default_channel")
        val MC_DEFAULT_CHANNEL = stringPreferencesKey("meshcore_default_channel")
        val DEMO_MODE = booleanPreferencesKey("demo_mode")

        val BRIDGE_MASTER = booleanPreferencesKey("bridge_master")
        val BRIDGE_MT_TO_MC = booleanPreferencesKey("bridge_mt_to_mc")
        val BRIDGE_MC_TO_MT = booleanPreferencesKey("bridge_mc_to_mt")
        val BRIDGE_ANNOTATE = booleanPreferencesKey("bridge_annotate")
        val BRIDGE_MAX_HOPS = intPreferencesKey("bridge_max_hops")
        val BRIDGE_DUP_WINDOW = longPreferencesKey("bridge_duplicate_window")
        val BRIDGE_MAX_AGE = longPreferencesKey("bridge_max_message_age")

        const val MAX_ALLOWED_HOPS = 3
        const val MIN_DUPLICATE_WINDOW_MILLIS = 60_000L
        const val MAX_DUPLICATE_WINDOW_MILLIS = 6 * 60 * 60 * 1000L
    }
}

/**
 * Room-backed [BridgeSeenCache].
 *
 * Persisting the cache is what stops a phone that was killed mid-conversation
 * from re-relaying everything it had already bridged when it comes back.
 */
@Singleton
class PersistentBridgeSeenCache @Inject constructor(
    private val dao: BridgeSeenDao,
) : BridgeSeenCache {

    override suspend fun hasFingerprint(fingerprint: String): Boolean = dao.hasFingerprint(fingerprint)

    override suspend fun hasBridgeId(bridgeId: String): Boolean = dao.hasBridgeId(bridgeId)

    override suspend fun record(entry: BridgeSeenEntry) {
        dao.insert(
            BridgeSeenEntity(
                fingerprint = entry.fingerprint,
                bridgeId = entry.bridgeId,
                protocol = entry.protocol,
                seenAtMillis = entry.seenAtMillis,
            ),
        )
    }

    override suspend fun purgeOlderThan(cutoffMillis: Long) = dao.purgeOlderThan(cutoffMillis)
}

private fun BridgeRuleEntity.toModel() = BridgeRule(
    id = id,
    enabled = enabled,
    fromProtocol = fromProtocol,
    fromChannelId = fromChannelId,
    toProtocol = toProtocol,
    toChannelId = toChannelId,
    label = label,
)

private fun BridgeRule.toEntity() = BridgeRuleEntity(
    id = id,
    enabled = enabled,
    fromProtocol = fromProtocol,
    fromChannelId = fromChannelId,
    toProtocol = toProtocol,
    toChannelId = toChannelId,
    label = label,
)
