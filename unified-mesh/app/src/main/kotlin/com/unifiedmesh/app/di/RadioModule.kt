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
import com.unifiedmesh.protocol.api.FakeMeshCoreAdapter
import com.unifiedmesh.protocol.api.FakeMeshtasticAdapter
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
import kotlinx.coroutines.flow.map
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
     * Whether the app is running against simulated radios.
     *
     * Held as a StateFlow so the adapter factories can read it at the moment a
     * connection is made, rather than capturing it when the graph is built.
     */
    @Provides
    @Singleton
    @DemoMode
    fun demoModeState(
        settings: SettingsRepository,
        @ApplicationScope scope: CoroutineScope,
    ): StateFlow<Boolean> = settings.general
        .map { it.demoMode }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * Whether dropped links should be retried.
     *
     * Read per drop for the same reason: turning the setting off should take
     * effect on the next drop, not the next app start.
     */
    @Provides
    @Singleton
    @AutoReconnect
    fun autoReconnectState(
        settings: SettingsRepository,
        @ApplicationScope scope: CoroutineScope,
    ): StateFlow<Boolean> = settings.general
        .map { it.keepRadiosConnected }
        .stateIn(scope, SharingStarted.Eagerly, true)

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
        @DemoMode demoMode: StateFlow<Boolean>,
        @AutoReconnect autoReconnect: StateFlow<Boolean>,
    ): RadioSession = RadioSession(
        protocol = MeshProtocol.MESHTASTIC,
        adapterFactory = { device ->
            if (demoMode.value) {
                FakeMeshtasticAdapter(clock = clock, chatterIntervalMillis = DEMO_CHATTER_INTERVAL_MILLIS)
            } else {
                meshtasticAdapter(context, device, clock, diagnostics)
            }
        },
        clock = clock,
        dispatcher = Dispatchers.IO,
        diagnostics = { diagnostics.info(DiagnosticCategory.CONNECTION, it, MeshProtocol.MESHTASTIC) },
        autoReconnectEnabled = { autoReconnect.value },
    )

    @Provides
    @Singleton
    @MeshCoreSession
    fun meshCoreSession(
        @ApplicationContext context: Context,
        clock: Clock,
        diagnostics: DiagnosticsRepository,
        @DemoMode demoMode: StateFlow<Boolean>,
        @AutoReconnect autoReconnect: StateFlow<Boolean>,
    ): RadioSession = RadioSession(
        protocol = MeshProtocol.MESHCORE,
        adapterFactory = { device ->
            if (demoMode.value) {
                // Offset from the Meshtastic interval so the two demo radios do
                // not speak in lockstep, which would make a bridge loop look
                // like ordinary traffic.
                FakeMeshCoreAdapter(clock = clock, chatterIntervalMillis = DEMO_CHATTER_INTERVAL_MILLIS + 7_000)
            } else {
                meshCoreAdapter(context, device, clock, diagnostics)
            }
        },
        clock = clock,
        dispatcher = Dispatchers.IO,
        diagnostics = { diagnostics.info(DiagnosticCategory.CONNECTION, it, MeshProtocol.MESHCORE) },
        autoReconnectEnabled = { autoReconnect.value },
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

    /** How often a demo radio produces a message. Slow enough to read, fast enough to test. */
    private const val DEMO_CHATTER_INTERVAL_MILLIS = 20_000L
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DemoMode

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AutoReconnect

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MeshtasticSession

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MeshCoreSession
