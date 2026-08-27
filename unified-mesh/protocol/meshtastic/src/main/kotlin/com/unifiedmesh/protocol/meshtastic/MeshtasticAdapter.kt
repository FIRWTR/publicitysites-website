package com.unifiedmesh.protocol.meshtastic

import com.unifiedmesh.core.model.Clock
import com.unifiedmesh.core.model.DeliveryState
import com.unifiedmesh.core.model.MeshChannel
import com.unifiedmesh.core.model.MeshNode
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.MessageClass
import com.unifiedmesh.core.model.MessageDirection
import com.unifiedmesh.core.model.OutgoingMessage
import com.unifiedmesh.core.model.RadioConnectionState
import com.unifiedmesh.core.model.RadioDevice
import com.unifiedmesh.core.model.RadioDeviceInfo
import com.unifiedmesh.core.model.SendResult
import com.unifiedmesh.core.model.UnifiedMessage
import com.unifiedmesh.protocol.api.DeliveryUpdate
import com.unifiedmesh.protocol.api.MeshRadioAdapter
import com.unifiedmesh.protocol.api.RadioLinkException
import com.unifiedmesh.protocol.api.RadioLinkTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.proto.ChannelProtos
import org.meshtastic.proto.MeshProtos
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * [MeshRadioAdapter] for a Meshtastic radio.
 *
 * ### Session shape
 *
 * The client API is a stream of `FromRadio` protobufs pulled from the radio and
 * `ToRadio` protobufs written to it. The transport owns the BLE detail of that
 * (notify on `FROMNUM`, then read `FROMRADIO` until it returns empty); this
 * class only sees whole protobufs.
 *
 * 1. Write `ToRadio.want_config_id = CONFIG_NONCE`.
 * 2. Read the stream the radio sends back: `my_info`, `metadata`, the config and
 *    module-config blocks, `channel` entries, and `node_info` entries.
 * 3. The stream ends with a `FromRadio.config_complete_id` echoing the nonce.
 * 4. Stay connected, emitting text packets and applying node updates, and send a
 *    heartbeat periodically so the radio does not drop the phone API session.
 *
 * ### Independence
 *
 * The adapter owns a private [SupervisorJob]; nothing it does is observable from
 * another adapter instance.
 */
class MeshtasticAdapter(
    private val transport: RadioLinkTransport,
    private val clock: Clock = Clock.System,
    dispatcher: CoroutineContext = kotlinx.coroutines.Dispatchers.IO,
    /** Diagnostics sink; metadata only, never message text. */
    private val diagnostics: (String) -> Unit = {},
) : MeshRadioAdapter {

    override val protocol: MeshProtocol = MeshProtocol.MESHTASTIC

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var readerJob: Job? = null
    private var heartbeatJob: Job? = null

    private val _connectionState = MutableStateFlow<RadioConnectionState>(RadioConnectionState.Disconnected)
    override val connectionState: StateFlow<RadioConnectionState> = _connectionState.asStateFlow()

    private val _incoming = MutableSharedFlow<UnifiedMessage>(
        replay = 0,
        extraBufferCapacity = 128,
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
    override val nodes: StateFlow<List<MeshNode>> = _nodes.asStateFlow()

    private val _channels = MutableStateFlow<List<MeshChannel>>(emptyList())
    override val channels: StateFlow<List<MeshChannel>> = _channels.asStateFlow()

    private val _deviceInfo = MutableStateFlow<RadioDeviceInfo?>(null)
    override val deviceInfo: StateFlow<RadioDeviceInfo?> = _deviceInfo.asStateFlow()

    private var selfNodeNum: Long = 0
    private var metadata: MeshProtos.DeviceMetadata? = null
    private var currentDevice: RadioDevice? = null

    /** Meshtastic packet id -> our message id, for routing acknowledgements. */
    private val pendingAcks = ConcurrentHashMap<Int, String>()

    /** Completed when the radio echoes our config nonce. */
    private var configComplete: CompletableDeferred<Unit>? = null

    private val nodeIndex = ConcurrentHashMap<Long, MeshNode>()

    override suspend fun connect(device: RadioDevice) {
        currentDevice = device
        _connectionState.value = RadioConnectionState.Connecting
        try {
            transport.open(device)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Could not open link: ${e.message}")
            return
        }

        _connectionState.value = RadioConnectionState.Handshaking
        val handshakeDone = CompletableDeferred<Unit>()
        configComplete = handshakeDone

        readerJob = scope.launch {
            try {
                transport.incoming.collect { bytes -> handleFromRadio(bytes) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A dead transport moves *this* adapter to an error state and no
                // further: the exception must not escape the adapter's scope.
                diagnostics("link failed: ${e.javaClass.simpleName}: ${e.message}")
                fail("Link lost: ${e.message}")
            }
        }

        try {
            transport.send(MeshtasticCodec.wantConfig(MeshtasticProtocol.CONFIG_NONCE).toByteArray())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("Could not start handshake: ${e.message}")
            return
        }

        val completed = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MILLIS) { handshakeDone.await() } != null
        if (!completed) {
            fail("Radio did not finish sending its configuration")
            return
        }

        publishDeviceInfo()
        _connectionState.value = RadioConnectionState.Connected(requireNotNull(_deviceInfo.value))
        heartbeatJob = scope.launch { runHeartbeat() }
    }

    private suspend fun handleFromRadio(bytes: ByteArray) {
        val fromRadio = try {
            MeshProtos.FromRadio.parseFrom(bytes)
        } catch (e: Exception) {
            diagnostics("undecodable FromRadio, ${bytes.size} bytes: ${e.javaClass.simpleName}")
            return
        }

        when (fromRadio.payloadVariantCase) {
            MeshProtos.FromRadio.PayloadVariantCase.MY_INFO -> {
                selfNodeNum = fromRadio.myInfo.myNodeNum.toLong() and 0xFFFFFFFFL
                diagnostics("my node num ${MeshtasticProtocol.formatNodeId(selfNodeNum)}")
            }

            MeshProtos.FromRadio.PayloadVariantCase.METADATA -> {
                metadata = fromRadio.metadata
                diagnostics("firmware ${fromRadio.metadata.firmwareVersion} hw ${fromRadio.metadata.hwModel}")
            }

            MeshProtos.FromRadio.PayloadVariantCase.NODE_INFO -> applyNodeInfo(fromRadio.nodeInfo)

            MeshProtos.FromRadio.PayloadVariantCase.CHANNEL -> applyChannel(fromRadio.channel)

            MeshProtos.FromRadio.PayloadVariantCase.CONFIG_COMPLETE_ID -> {
                if (fromRadio.configCompleteId == MeshtasticProtocol.CONFIG_NONCE) {
                    diagnostics("config stream complete")
                    configComplete?.complete(Unit)
                } else {
                    diagnostics("ignoring config_complete_id ${fromRadio.configCompleteId}")
                }
            }

            MeshProtos.FromRadio.PayloadVariantCase.PACKET -> handlePacket(fromRadio.packet)

            MeshProtos.FromRadio.PayloadVariantCase.REBOOTED -> {
                // The radio restarted; its node database and our packet ids are
                // stale, so the whole session has to be rebuilt.
                diagnostics("radio rebooted, session invalid")
                fail("Radio rebooted")
            }

            else -> Unit // config, moduleConfig, queueStatus, logs: nothing to do here
        }
    }

    private suspend fun handlePacket(packet: MeshProtos.MeshPacket) {
        when (MeshtasticCodec.classify(packet)) {
            MessageClass.TEXT -> {
                val message = MeshtasticCodec.toUnifiedMessage(
                    packet = packet,
                    selfNodeNum = selfNodeNum,
                    nameLookup = { num -> nodeIndex[num]?.displayName },
                    receivedAtMillis = clock.nowMillis(),
                ) ?: return
                // A packet echoed back from our own radio is our own transmission;
                // it belongs in the thread but not on the inbound path, and the
                // bridge refuses it on direction anyway.
                if (message.direction == MessageDirection.INCOMING) {
                    _incoming.emit(message)
                }
            }

            MessageClass.ROUTING -> {
                val outcome = MeshtasticCodec.toDeliveryOutcome(packet) ?: return
                val messageId = pendingAcks.remove(outcome.packetId) ?: return
                _deliveryUpdates.emit(DeliveryUpdate(messageId, outcome.state, outcome.detail))
                diagnostics("routing outcome for $messageId: ${outcome.state}")
            }

            // Position, node info, telemetry and admin traffic update the node
            // list or are simply recorded. None of it reaches incomingMessages,
            // which is what keeps it out of the bridge.
            MessageClass.POSITION, MessageClass.NODE_INFO, MessageClass.TELEMETRY -> {
                diagnostics("node data packet from ${MeshtasticProtocol.formatNodeId(packet.from.toLong() and 0xFFFFFFFFL)}")
            }

            else -> Unit
        }
    }

    private fun applyNodeInfo(nodeInfo: MeshProtos.NodeInfo) {
        val node = MeshtasticCodec.toMeshNode(nodeInfo, selfNodeNum)
        nodeIndex[nodeInfo.num.toLong() and 0xFFFFFFFFL] = node
        _nodes.value = nodeIndex.values.sortedWith(
            compareByDescending<MeshNode> { it.isSelf }.thenBy { it.displayName.lowercase() },
        )
    }

    private fun applyChannel(channel: ChannelProtos.Channel) {
        // DISABLED channels cannot carry traffic, so they are not offered as a
        // send target. The PSK in channel.settings is never read.
        if (channel.role == ChannelProtos.Channel.Role.DISABLED) return
        val name = channel.settings.name.takeIf { it.isNotBlank() }
            ?: if (channel.index == 0) DEFAULT_PRIMARY_CHANNEL_NAME else "Channel ${channel.index}"
        val updated = _channels.value.filterNot { it.index == channel.index } + MeshChannel(
            protocol = MeshProtocol.MESHTASTIC,
            id = channel.index.toString(),
            name = name,
            index = channel.index,
            isPrimary = channel.role == ChannelProtos.Channel.Role.PRIMARY,
        )
        _channels.value = updated.sortedBy { it.index }
    }

    private suspend fun publishDeviceInfo() {
        val self = nodeIndex[selfNodeNum]
        _deviceInfo.value = RadioDeviceInfo(
            protocol = MeshProtocol.MESHTASTIC,
            deviceName = currentDevice?.name ?: self?.displayName,
            hardwareModel = metadata?.hwModel?.name,
            firmwareVersion = metadata?.firmwareVersion,
            batteryLevel = self?.batteryLevel,
            batteryMilliVolts = null,
            linkRssi = runCatching { transport.readLinkRssi() }.getOrNull(),
            lastPacketSnr = self?.snr,
            nodeId = self?.id ?: MeshtasticProtocol.formatNodeId(selfNodeNum),
            nodeName = self?.displayName,
        )
    }

    /**
     * Keeps the radio's phone-API session alive.
     *
     * Without traffic the firmware eventually drops the client, and on BLE that
     * looks like a silently dead link rather than a disconnect.
     */
    private suspend fun runHeartbeat() {
        while (scope.isActive) {
            delay(HEARTBEAT_INTERVAL_MILLIS)
            if (!_connectionState.value.isConnected) continue
            try {
                transport.send(MeshtasticCodec.heartbeat().toByteArray())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnostics("heartbeat failed: ${e.message}")
                fail("Link lost: ${e.message}")
                return
            }
        }
    }

    override suspend fun disconnect() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        // Tell the radio we are going, so it can release the client slot rather
        // than waiting for its own timeout. Best effort: the link may already be
        // gone, which is exactly when this throws.
        runCatching { transport.send(MeshtasticCodec.disconnect().toByteArray()) }
        readerJob?.cancel()
        readerJob = null
        configComplete = null
        pendingAcks.clear()
        nodeIndex.clear()
        runCatching { transport.close() }
        _nodes.value = emptyList()
        _channels.value = emptyList()
        _deviceInfo.value = null
        _connectionState.value = RadioConnectionState.Disconnected
    }

    override suspend fun sendMessage(message: OutgoingMessage): SendResult {
        if (!_connectionState.value.isConnected) {
            return SendResult.Failed("Meshtastic radio is not connected", retryable = true)
        }
        val bytes = message.text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MeshtasticProtocol.MAX_TEXT_BYTES) {
            return SendResult.Failed(
                "Message is ${bytes.size} bytes; Meshtastic carries at most ${MeshtasticProtocol.MAX_TEXT_BYTES}",
                retryable = false,
            )
        }

        val packetId = MeshtasticCodec.newPacketId()
        val toRadio = try {
            MeshtasticCodec.toRadioTextPacket(message, packetId)
        } catch (e: IllegalArgumentException) {
            return SendResult.Failed(e.message ?: "Invalid destination", retryable = false)
        }

        // Register the ack expectation *before* transmitting: the radio can answer
        // faster than this coroutine is rescheduled.
        if (toRadio.packet.wantAck) {
            pendingAcks[packetId] = message.id
        }

        return try {
            transport.send(toRadio.toByteArray())
            if (toRadio.packet.wantAck) {
                scope.launch { expireAck(packetId, message.id) }
            }
            SendResult.Accepted(radioMessageId = packetId.toUInt().toString(16))
        } catch (e: CancellationException) {
            pendingAcks.remove(packetId)
            throw e
        } catch (e: RadioLinkException) {
            pendingAcks.remove(packetId)
            SendResult.Failed("Meshtastic link error: ${e.message}", retryable = true)
        } catch (e: Exception) {
            pendingAcks.remove(packetId)
            SendResult.Failed("Could not write to the radio: ${e.message}", retryable = true)
        }
    }

    /**
     * Fails a message whose acknowledgement never arrives.
     *
     * Without this a direct message sits at "Sending" forever when the
     * destination is out of range, which reads as a hung app rather than a
     * failed delivery.
     */
    private suspend fun expireAck(packetId: Int, messageId: String) {
        delay(MeshtasticProtocol.ACK_TIMEOUT_MILLIS)
        if (pendingAcks.remove(packetId) != null) {
            _deliveryUpdates.emit(
                DeliveryUpdate(messageId, DeliveryState.FAILED, "No acknowledgement from the mesh"),
            )
        }
    }

    override suspend fun getDeviceInfo(): RadioDeviceInfo {
        if (_connectionState.value.isConnected) publishDeviceInfo()
        return _deviceInfo.value ?: RadioDeviceInfo(protocol = MeshProtocol.MESHTASTIC)
    }

    /** Releases the adapter's scope. Only for a full teardown of this radio slot. */
    fun shutdown() {
        scope.cancel()
    }

    private fun fail(reason: String) {
        diagnostics(reason)
        configComplete?.complete(Unit)
        _connectionState.value = RadioConnectionState.Error(reason, recoverable = true)
    }

    private companion object {
        /**
         * The config stream includes the whole node database, which on a busy
         * mesh is hundreds of entries over BLE.
         */
        const val HANDSHAKE_TIMEOUT_MILLIS = 60_000L

        const val HEARTBEAT_INTERVAL_MILLIS = 5 * 60 * 1000L

        /** The firmware leaves the primary channel's name empty for the default preset. */
        const val DEFAULT_PRIMARY_CHANNEL_NAME = "LongFast"
    }
}
