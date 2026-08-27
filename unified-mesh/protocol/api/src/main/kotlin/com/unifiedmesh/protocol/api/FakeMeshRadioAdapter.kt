package com.unifiedmesh.protocol.api

import com.unifiedmesh.core.model.Clock
import com.unifiedmesh.core.model.ConversationKey
import com.unifiedmesh.core.model.DeliveryState
import com.unifiedmesh.core.model.MeshChannel
import com.unifiedmesh.core.model.MeshNode
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.MessageDirection
import com.unifiedmesh.core.model.OutgoingMessage
import com.unifiedmesh.core.model.RadioConnectionState
import com.unifiedmesh.core.model.RadioDevice
import com.unifiedmesh.core.model.RadioDeviceInfo
import com.unifiedmesh.core.model.SendResult
import com.unifiedmesh.core.model.UnifiedMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory [MeshRadioAdapter] used for UI development, demo mode, and tests.
 *
 * It reproduces the parts of real adapter behaviour the rest of the app depends
 * on — asynchronous handshake, hot message flow, delayed delivery confirmation,
 * per-instance coroutine scope — without any Bluetooth.
 *
 * Each instance owns a private [SupervisorJob], which is what makes the
 * "one radio disconnecting must not break the other" property hold: cancelling
 * this adapter's scope is unobservable to any other instance.
 */
open class FakeMeshRadioAdapter(
    final override val protocol: MeshProtocol,
    private val clock: Clock = Clock.System,
    private val seedNodes: List<MeshNode> = emptyList(),
    private val seedChannels: List<MeshChannel> = emptyList(),
    private val deviceModel: String = "Fake Radio",
    private val firmwareVersion: String = "0.0.0-fake",
    /** Simulated traffic interval; null disables the generator. */
    private val chatterIntervalMillis: Long? = null,
    private val chatterScript: List<FakeIncoming> = emptyList(),
    /** When true, [sendMessage] always fails — used to exercise the ✕ Failed path. */
    var failSends: Boolean = false,
    parentDispatcher: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.Default,
) : MeshRadioAdapter {

    /** Private scope: never shared with another adapter. */
    private val scope = CoroutineScope(SupervisorJob() + parentDispatcher)
    private var sessionJob: Job? = null

    private val _connectionState = MutableStateFlow<RadioConnectionState>(RadioConnectionState.Disconnected)
    override val connectionState: kotlinx.coroutines.flow.StateFlow<RadioConnectionState> =
        _connectionState.asStateFlow()

    private val _incoming = MutableSharedFlow<UnifiedMessage>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val incomingMessages: Flow<UnifiedMessage> = _incoming.asSharedFlow()

    private val _deliveryUpdates = MutableSharedFlow<DeliveryUpdate>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val deliveryUpdates: Flow<DeliveryUpdate> = _deliveryUpdates.asSharedFlow()

    private val _nodes = MutableStateFlow<List<MeshNode>>(emptyList())
    override val nodes: kotlinx.coroutines.flow.StateFlow<List<MeshNode>> = _nodes.asStateFlow()

    private val _channels = MutableStateFlow<List<MeshChannel>>(emptyList())
    override val channels: kotlinx.coroutines.flow.StateFlow<List<MeshChannel>> = _channels.asStateFlow()

    private val _deviceInfo = MutableStateFlow<RadioDeviceInfo?>(null)
    override val deviceInfo: kotlinx.coroutines.flow.StateFlow<RadioDeviceInfo?> = _deviceInfo.asStateFlow()

    private val sentCounter = AtomicLong(0)

    /** Every message this adapter was asked to transmit, in order. */
    val sentMessages: List<OutgoingMessage> get() = _sentMessages.toList()
    private val _sentMessages = mutableListOf<OutgoingMessage>()

    override suspend fun connect(device: RadioDevice) {
        _connectionState.value = RadioConnectionState.Connecting
        delay(CONNECT_DELAY_MILLIS)
        _connectionState.value = RadioConnectionState.Handshaking
        delay(HANDSHAKE_DELAY_MILLIS)

        _nodes.value = seedNodes
        _channels.value = seedChannels
        val info = RadioDeviceInfo(
            protocol = protocol,
            deviceName = device.name ?: deviceModel,
            hardwareModel = deviceModel,
            firmwareVersion = firmwareVersion,
            batteryLevel = if (protocol == MeshProtocol.MESHTASTIC) 92 else 81,
            batteryMilliVolts = if (protocol == MeshProtocol.MESHCORE) 3920 else null,
            linkRssi = if (protocol == MeshProtocol.MESHTASTIC) -74 else -82,
            lastPacketSnr = if (protocol == MeshProtocol.MESHTASTIC) 6.5f else 4.25f,
            nodeId = seedNodes.firstOrNull { it.isSelf }?.id,
            nodeName = seedNodes.firstOrNull { it.isSelf }?.displayName,
        )
        _deviceInfo.value = info
        _connectionState.value = RadioConnectionState.Connected(info)

        sessionJob?.cancel()
        sessionJob = scope.launch { runChatter() }
    }

    override suspend fun disconnect() {
        // Cancel only this adapter's session. The scope itself stays alive so the
        // adapter can be reconnected, and no other adapter is touched.
        sessionJob?.cancel()
        sessionJob = null
        _deviceInfo.value = null
        _nodes.value = emptyList()
        _channels.value = emptyList()
        _connectionState.value = RadioConnectionState.Disconnected
    }

    override suspend fun sendMessage(message: OutgoingMessage): SendResult {
        if (!_connectionState.value.isConnected) {
            return SendResult.Failed("${protocol.displayName} radio is not connected", retryable = true)
        }
        _sentMessages += message
        if (failSends) {
            return SendResult.Failed("Simulated transmit failure", retryable = true)
        }
        val radioId = "fake-${protocol.name.lowercase()}-${sentCounter.incrementAndGet()}"
        // Confirm delivery a moment later, like a real ACK.
        scope.launch {
            delay(ACK_DELAY_MILLIS)
            _deliveryUpdates.emit(DeliveryUpdate(message.id, DeliveryState.DELIVERED))
        }
        return SendResult.Accepted(radioId)
    }

    override suspend fun getDeviceInfo(): RadioDeviceInfo =
        _deviceInfo.value ?: RadioDeviceInfo(protocol = protocol, hardwareModel = deviceModel)

    /** Injects an inbound message immediately. Used by tests and demo scripts. */
    suspend fun emitIncoming(incoming: FakeIncoming) {
        _incoming.emit(incoming.toUnifiedMessage(protocol, clock.nowMillis()))
    }

    /** Releases the adapter's scope. Only used when the whole radio slot is torn down. */
    fun shutdown() {
        scope.cancel()
    }

    private suspend fun runChatter() {
        val interval = chatterIntervalMillis ?: return
        if (chatterScript.isEmpty()) return
        var index = 0
        while (scope.isActive) {
            delay(interval)
            emitIncoming(chatterScript[index % chatterScript.size])
            index++
        }
    }

    private companion object {
        const val CONNECT_DELAY_MILLIS = 400L
        const val HANDSHAKE_DELAY_MILLIS = 600L
        const val ACK_DELAY_MILLIS = 900L
    }
}

/** A scripted inbound message for the fake adapters. */
data class FakeIncoming(
    val senderId: String,
    val senderName: String,
    val text: String,
    /** null => channel message on [channelId]. */
    val destinationId: String? = null,
    val channelId: String? = "0",
    val snr: Float? = null,
    val rssi: Int? = null,
) {
    fun toUnifiedMessage(protocol: MeshProtocol, now: Long): UnifiedMessage {
        val key = if (channelId != null) {
            ConversationKey.channel(protocol, channelId)
        } else {
            ConversationKey.direct(protocol, senderId)
        }
        return UnifiedMessage(
            id = "fake-${protocol.name.lowercase()}-$now-${text.hashCode()}",
            protocol = protocol,
            conversationId = key.asId(),
            senderId = senderId,
            senderName = senderName,
            destinationId = destinationId,
            channelId = channelId,
            text = text,
            timestamp = now,
            direction = MessageDirection.INCOMING,
            deliveryState = DeliveryState.RECEIVED,
            snr = snr,
            rssi = rssi,
        )
    }
}
