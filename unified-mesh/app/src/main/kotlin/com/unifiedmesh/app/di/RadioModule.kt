package com.unifiedmesh.app.di

import android.content.Context
import com.unifiedmesh.core.bluetooth.MeshCoreBleTransport
import com.unifiedmesh.core.bluetooth.MeshtasticBleTransport
import com.unifiedmesh.core.bridge.BridgeEngine
import com.unifiedmesh.core.bridge.BridgeSeenCache
import com.unifiedmesh.core.database.DiagnosticsRepository
import com.unifiedmesh.core.database.MessageRepository
import com.unifiedmesh.core.database.SettingsRepository
import com.unifiedmesh.core.model.BridgeConfig
import com.unifiedmesh.core.model.Clock
import com.unifiedmesh.core.model.DiagnosticCategory
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.RadioDevice
import com.unifiedmesh.core.model.RadioTransport
import com.unifiedmesh.core.radio.RadioCoordinator
import com.unifiedmesh.core.radio.RadioSession
import com.unifiedmesh.app.notification.IncomingMessageNotifier
import com.unifiedmesh.protocol.api.MeshRadioAdapter
import com.unifiedmesh.protocol.meshcore.MeshCoreAdapter
import com.unifiedmesh.protocol.meshtastic.MeshtasticAdapter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object RadioModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun bridgeEngine(clock: Clock, seenCache: BridgeSeenCache): BridgeEngine =
        BridgeEngine(clock, seenCache)

    @Provides
    @Singleton
    fun bridgeConfigState(
        settings: SettingsRepository,
        @ApplicationScope scope: CoroutineScope,
    ): StateFlow<BridgeConfig> = settings.bridgeConfig.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        // Everything off until the stored configuration loads. A bridge that
        // relayed for even a moment on a default it was never given would be a
        // surprise on two networks at once.
        initialValue = BridgeConfig(),
    )

    /**
     * Builds the Meshtastic slot.
     *
     * The adapter factory is what keeps `:core:radio` free of Android: the
     * session asks for an adapter for a device and this module decides that a
     * BLE device means a [MeshtasticBleTransport] behind a [MeshtasticAdapter].
     * A USB or Wi-Fi transport would be another branch here and nothing else.
     */
    @Provides
    @Singleton
    @MeshtasticSession
    fun meshtasticSession(
        @ApplicationContext context: Context,
        clock: Clock,
        diagnostics: DiagnosticsRepository,
    ): RadioSession = RadioSession(
        protocol = MeshProtocol.MESHTASTIC,
        adapterFactory = { device -> meshtasticAdapter(context, device, clock, diagnostics) },
        clock = clock,
        dispatcher = Dispatchers.IO,
        diagnostics = { diagnostics.info(DiagnosticCategory.CONNECTION, it, MeshProtocol.MESHTASTIC) },
    )

    @Provides
    @Singleton
    @MeshCoreSession
    fun meshCoreSession(
        @ApplicationContext context: Context,
        clock: Clock,
        diagnostics: DiagnosticsRepository,
    ): RadioSession = RadioSession(
        protocol = MeshProtocol.MESHCORE,
        adapterFactory = { device -> meshCoreAdapter(context, device, clock, diagnostics) },
        clock = clock,
        dispatcher = Dispatchers.IO,
        diagnostics = { diagnostics.info(DiagnosticCategory.CONNECTION, it, MeshProtocol.MESHCORE) },
    )

    @Provides
    @Singleton
    fun radioCoordinator(
        @MeshtasticSession meshtastic: RadioSession,
        @MeshCoreSession meshCore: RadioSession,
        store: MessageRepository,
        bridgeEngine: BridgeEngine,
        bridgeConfig: StateFlow<BridgeConfig>,
        clock: Clock,
        diagnostics: DiagnosticsRepository,
        incomingNotifier: IncomingMessageNotifier,
    ): RadioCoordinator = RadioCoordinator(
        meshtastic = meshtastic,
        meshCore = meshCore,
        store = store,
        bridgeEngine = bridgeEngine,
        bridgeConfig = bridgeConfig,
        clock = clock,
        dispatcher = Dispatchers.Default,
        diagnostics = { diagnostics.debug(DiagnosticCategory.BRIDGE, it) },
        incomingListener = incomingNotifier,
    )

    private fun meshtasticAdapter(
        context: Context,
        device: RadioDevice,
        clock: Clock,
        diagnostics: DiagnosticsRepository,
    ): MeshRadioAdapter = when (device.transport) {
        RadioTransport.BLE -> MeshtasticAdapter(
            transport = MeshtasticBleTransport(
                context = context,
                diagnostics = { diagnostics.debug(DiagnosticCategory.BLE, it, MeshProtocol.MESHTASTIC) },
            ),
            clock = clock,
            diagnostics = { diagnostics.debug(DiagnosticCategory.RX, it, MeshProtocol.MESHTASTIC) },
        )

        // Declared in the model so persistence and UI already carry the
        // discriminator; adding one is a transport implementation, not a
        // change to the adapter or anything above it.
        RadioTransport.USB, RadioTransport.TCP ->
            throw UnsupportedOperationException("${device.transport} is not supported yet")
    }

    private fun meshCoreAdapter(
        context: Context,
        device: RadioDevice,
        clock: Clock,
        diagnostics: DiagnosticsRepository,
    ): MeshRadioAdapter = when (device.transport) {
        RadioTransport.BLE -> MeshCoreAdapter(
            transport = MeshCoreBleTransport(
                context = context,
                diagnostics = { diagnostics.debug(DiagnosticCategory.BLE, it, MeshProtocol.MESHCORE) },
            ),
            clock = clock,
            diagnostics = { diagnostics.debug(DiagnosticCategory.RX, it, MeshProtocol.MESHCORE) },
        )

        RadioTransport.USB, RadioTransport.TCP ->
            throw UnsupportedOperationException("${device.transport} is not supported yet")
    }
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MeshtasticSession

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MeshCoreSession
