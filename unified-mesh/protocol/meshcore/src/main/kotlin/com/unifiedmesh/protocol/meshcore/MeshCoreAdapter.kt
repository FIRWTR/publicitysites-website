package com.unifiedmesh.protocol.meshcore

import com.unifiedmesh.core.model.Clock
import com.unifiedmesh.core.model.ConversationKey
import com.unifiedmesh.core.model.DeliveryState
import com.unifiedmesh.core.model.MeshChannel
import com.unifiedmesh.core.model.MeshNode
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.MessageDirection
import com.unifiedmesh.core.model.NodePosition
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * [MeshRadioAdapter] for a MeshCore companion radio.
 *
 * ### Session shape
 *
 * 1. `CMD_DEVICE_QUERY` announces the protocol version this client understands.
 *    Sending 3 or higher is what makes the firmware emit the `*_V3` message
 *    frames that carry SNR.
 * 2. `CMD_APP_START` identifies the app and returns `RESP_CODE_SELF_INFO`, which
 *    is where this radio's own identity and LoRa parameters come from.
 * 3. `CMD_GET_CONTACTS` streams the contact list.
 * 4. The offline queue is drained with `CMD_SYNC_NEXT_MESSAGE` until the radio
 *    answers `RESP_CODE_NO_MORE_MESSAGES` — messages that arrived while the phone
 *    was away are waiting there.
 * 5. From then on a `PUSH_CODE_MSG_WAITING` tickle triggers another drain.
 *
 * ### Independence
 *
 * The adapter owns a private [SupervisorJob]. Nothing it does — including the
 * transport dying — is observable from another adapter instance.
 */
class MeshCoreAdapter(
    private val transport: RadioLinkTransport,
    private val clock: Clock = Clock.System,
    private val appName: String = DEFAULT_APP_NAME,
    dispatcher: CoroutineContext = kotlinx.coroutines.Dispatchers.IO,
    /** Diagnostics sink; metadata only, never message text. */
    private val diagnostics: (String) -> Unit = {},
) : MeshRadioAdapter {

    override val protocol: MeshProtocol = MeshProtocol.MESHCORE

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var session: MeshCoreSession? = null
    private var sessionJob: Job? = null

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

    /** `RESP_CODE_SENT.expectedAck` -> our message id, for [MeshCoreFrame.SendConfirmed]. */
    private val pendingAcks = ConcurrentHashMap<Long, String>()

    private var selfInfo: MeshCoreFrame.SelfInfo? = null
    private var firmwareInfo: MeshCoreFrame.DeviceInfo? = null

    /** `lastMod` watermark so reconnects fetch only changed contacts. */
    private var contactsSince: Long? = null

    override suspend fun connect(device: RadioDevice) {
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
        val session = MeshCoreSession(
            transport = transport,
            scope = scope,
            onFrame = { direction, code, size ->
                diagnostics("frame $direction code=0x${code.toString(16)} len=$size")
            },
        )
        this.session = session
        session.start()

        try {
            handshake(session, device)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics("handshake failed: ${e.javaClass.simpleName}: ${e.message}")
            fail("Handshake failed: ${e.message}")
            session.stop()
            runCatching { transport.close() }
            return
        }

        sessionJob = scope.launch { consumePushes(session) }
    }

    private suspend fun handshake(session: MeshCoreSession, device: RadioDevice) {
        val info = session.request(
            MeshCoreCodec.deviceQuery(),
            matches = { it is MeshCoreFrame.DeviceInfo },
        ) as MeshCoreFrame.DeviceInfo
        firmwareInfo = info
        diagnostics("device info: model=${info.manufacturerModel} fw=${info.firmwareVersion}")

        val self = session.request(
            MeshCoreCodec.appStart(appName),
            matches = { it is MeshCoreFrame.SelfInfo },
        ) as MeshCoreFrame.SelfInfo
        selfInfo = self

        // The radio has no RTC battery on most boards, so its clock is whatever it
        // was when it last spoke to a phone. Message timestamps come from it, so a
        // wrong clock shows up directly in the inbox ordering.
        runCatching {
            session.request(
                MeshCoreCodec.setDeviceTime(clock.nowMillis() / 1000),
                matches = { it is MeshCoreFrame.Ok },
            )
        }.onFailure { diagnostics("clock sync skipped: ${it.message}") }

        refreshContacts(session)
        refreshChannels(session, info.maxChannels)
        val battery = runCatching { readBattery(session) }.getOrNull()

        publishDeviceInfo(device, battery)
        _connectionState.value = RadioConnectionState.Connected(requireNotNull(_deviceInfo.value))

        // Anything that arrived while the phone was away is sitting in the radio's
        // offline queue; drain it before reporting steady state.
        drainMessages(session)
    }

    private suspend fun consumePushes(session: MeshCoreSession) {
        scope.launch {
            session.malformedFrames.collect { diagnostics("malformed frame: $it") }
        }
        session.pushes.collect { frame ->
            when (frame) {
                is MeshCoreFrame.MessagesWaiting -> drainMessages(session)

                is MeshCoreFrame.SendConfirmed -> {
                    val messageId = pendingAcks.remove(frame.ackCode)
                    if (messageId != null) {
                        _deliveryUpdates.emit(DeliveryUpdate(messageId, DeliveryState.DELIVERED))
                        diagnostics("ack for $messageId after ${frame.roundTripMillis}ms")
                    }
                }

                is MeshCoreFrame.NewContact -> upsertContact(frame.contact)

                is MeshCoreFrame.AdvertReceived ->
                    // A known contact re-advertised; its path or position may have
                    // changed, so re-sync incrementally.
                    runCatching { refreshContacts(session) }

                is MeshCoreFrame.Contact -> upsertContact(frame)

                is MeshCoreFrame.Unhandled ->
                    diagnostics("unhandled push 0x${frame.code.toString(16)} len=${frame.length}")

                else -> Unit
            }
        }
    }

    /**
     * Pulls messages until the radio says the queue is empty.
     *
     * Bounded so a firmware that never returns `RESP_CODE_NO_MORE_MESSAGES`
     * cannot spin this coroutine forever.
     */
    private suspend fun drainMessages(session: MeshCoreSession) {
        var drained = 0
        while (drained < MAX_DRAIN_PER_TICKLE) {
            val frame = try {
                session.request(
                    MeshCoreCodec.syncNextMessage(),
                    matches = {
                        it is MeshCoreFrame.ContactMessage ||
                            it is MeshCoreFrame.ChannelMessage ||
                            it is MeshCoreFrame.NoMoreMessages
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnostics("message drain stopped: ${e.message}")
                return
            }

            when (frame) {
                is MeshCoreFrame.NoMoreMessages -> return
                is MeshCoreFrame.ContactMessage -> emitContactMessage(frame)
                is MeshCoreFrame.ChannelMessage -> emitChannelMessage(frame)
                else -> return
            }
            drained++
        }
        diagnostics("drain hit the $MAX_DRAIN_PER_TICKLE message cap; will resume on next tickle")
    }

    private suspend fun emitContactMessage(frame: MeshCoreFrame.ContactMessage) {
        // CLI responses are machine traffic, not chat: they must never reach the
        // inbox and so can never reach the bridge.
        if (frame.textType == MeshCoreProtocol.TXT_TYPE_CLI_DATA) {
            diagnostics("dropped CLI data frame from ${frame.senderPrefix}")
            return
        }
        val sender = frame.senderPrefix
        val name = _nodes.value.firstOrNull { it.id == sender }?.displayName
        _incoming.emit(
            UnifiedMessage(
                id = messageId(sender, frame.senderTimestamp, frame.text),
                protocol = MeshProtocol.MESHCORE,
                conversationId = ConversationKey.direct(MeshProtocol.MESHCORE, sender).asId(),
                senderId = sender,
                senderName = name,
                destinationId = selfInfo?.nodeId,
                channelId = null,
                text = frame.text,
                timestamp = frame.senderTimestamp * 1000,
                direction = MessageDirection.INCOMING,
                deliveryState = DeliveryState.RECEIVED,
                snr = frame.snr,
            ),
        )
    }

    private suspend fun emitChannelMessage(frame: MeshCoreFrame.ChannelMessage) {
        val channelId = frame.channelIndex.toString()
        // Channel messages carry the sender's name inside the payload as
        // "name: text"; the frame itself has no sender identity field.
        val (sender, body) = splitChannelSender(frame.text)
        _incoming.emit(
            UnifiedMessage(
                id = messageId(sender ?: channelId, frame.senderTimestamp, frame.text),
                protocol = MeshProtocol.MESHCORE,
                conversationId = ConversationKey.channel(MeshProtocol.MESHCORE, channelId).asId(),
                senderId = sender ?: "channel:$channelId",
                senderName = sender,
                destinationId = null,
                channelId = channelId,
                text = body,
                timestamp = frame.senderTimestamp * 1000,
                direction = MessageDirection.INCOMING,
                deliveryState = DeliveryState.RECEIVED,
                snr = frame.snr,
            ),
        )
    }

    /**
     * Reads the contact list.
     *
     * One `CMD_GET_CONTACTS` produces a whole stream of frames —
     * `RESP_CODE_CONTACTS_START`, then a `RESP_CODE_CONTACT` per contact, then
     * `RESP_CODE_END_OF_CONTACTS` — so it goes through [MeshCoreSession.requestStream]
     * as a single exchange.
     *
     * The `lastMod` watermark returned in the terminal frame is kept, so a
     * reconnect or an advert only pulls contacts that actually changed.
     */
    private suspend fun refreshContacts(session: MeshCoreSession) {
        val frames = try {
            session.requestStream(
                command = MeshCoreCodec.getContacts(contactsSince),
                timeoutMillis = CONTACT_SYNC_TIMEOUT_MILLIS,
                accept = { it is MeshCoreFrame.Contact || it is MeshCoreFrame.EndOfContacts },
                isTerminal = { it is MeshCoreFrame.EndOfContacts },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics("contact sync failed: ${e.message}")
            return
        }

        applyContacts(frames.filterIsInstance<MeshCoreFrame.Contact>())
        frames.filterIsInstance<MeshCoreFrame.EndOfContacts>().lastOrNull()?.let {
            if (it.mostRecentLastMod > 0) {
                contactsSince = maxOf(contactsSince ?: 0, it.mostRecentLastMod)
            }
        }
    }

    private fun applyContacts(contacts: List<MeshCoreFrame.Contact>) {
        if (contacts.isEmpty()) return
        val merged = _nodes.value.associateBy { it.id }.toMutableMap()
        contacts.forEach { contact -> merged[contact.id] = contact.toMeshNode() }
        _nodes.value = merged.values.sortedBy { it.displayName.lowercase() }
    }

    private fun upsertContact(contact: MeshCoreFrame.Contact) {
        applyContacts(listOf(contact))
        contactsSince = maxOf(contactsSince ?: 0, contact.lastMod)
    }

    private suspend fun refreshChannels(session: MeshCoreSession, maxChannels: Int) {
        val found = mutableListOf<MeshChannel>()
        for (index in 0 until maxChannels.coerceAtMost(MAX_CHANNEL_PROBE)) {
            val frame = runCatching {
                session.request(
                    MeshCoreCodec.getChannel(index),
                    matches = { it is MeshCoreFrame.ChannelInfo },
                )
            }.getOrNull() as? MeshCoreFrame.ChannelInfo ?: continue
            if (frame.name.isBlank()) continue
            found += MeshChannel(
                protocol = MeshProtocol.MESHCORE,
                id = frame.index.toString(),
                name = frame.name,
                index = frame.index,
                // Channel 0 is reserved for the public channel.
                isPrimary = frame.index == 0,
            )
        }
        if (found.isNotEmpty()) _channels.value = found
    }

    private suspend fun readBattery(session: MeshCoreSession): MeshCoreFrame.BatteryAndStorage? =
        session.request(
            MeshCoreCodec.getBatteryAndStorage(),
            matches = { it is MeshCoreFrame.BatteryAndStorage },
        ) as? MeshCoreFrame.BatteryAndStorage

    private suspend fun publishDeviceInfo(device: RadioDevice, battery: MeshCoreFrame.BatteryAndStorage?) {
        val self = selfInfo
        val firmware = firmwareInfo
        _deviceInfo.value = RadioDeviceInfo(
            protocol = MeshProtocol.MESHCORE,
            deviceName = device.name ?: firmware?.manufacturerModel,
            hardwareModel = firmware?.manufacturerModel,
            firmwareVersion = firmware?.firmwareVersion,
            // MeshCore reports raw millivolts and no percentage, so the UI shows
            // volts for this radio rather than inventing a charge curve.
            batteryLevel = null,
            batteryMilliVolts = battery?.batteryMilliVolts,
            linkRssi = runCatching { transport.readLinkRssi() }.getOrNull(),
            lastPacketSnr = null,
            nodeId = self?.nodeId,
            nodeName = self?.nodeName,
        )
    }

    override suspend fun disconnect() {
        sessionJob?.cancel()
        sessionJob = null
        session?.stop()
        session = null
        pendingAcks.clear()
        runCatching { transport.close() }
        _deviceInfo.value = null
        _nodes.value = emptyList()
        _channels.value = emptyList()
        _connectionState.value = RadioConnectionState.Disconnected
    }

    override suspend fun sendMessage(message: OutgoingMessage): SendResult {
        val session = session ?: return SendResult.Failed("MeshCore radio is not connected", retryable = true)
        if (!_connectionState.value.isConnected) {
            return SendResult.Failed("MeshCore radio is not connected", retryable = true)
        }
        if (message.text.length > MeshCoreProtocol.MAX_TEXT_LENGTH) {
            return SendResult.Failed(
                "Message is ${message.text.length} characters; MeshCore carries at most " +
                    "${MeshCoreProtocol.MAX_TEXT_LENGTH}",
                retryable = false,
            )
        }

        val timestampSeconds = message.timestamp / 1000
        return try {
            when {
                message.channelId != null -> {
                    val index = message.channelId.toIntOrNull()
                        ?: return SendResult.Failed("Not a MeshCore channel index: ${message.channelId}", false)
                    // The firmware answers a channel send with a plain OK; there is
                    // no per-message ack code for group traffic.
                    session.request(
                        MeshCoreCodec.sendChannelTextMessage(index, message.text, timestampSeconds),
                        matches = { it is MeshCoreFrame.Ok },
                    )
                    SendResult.Accepted()
                }

                else -> {
                    val sent = session.request(
                        MeshCoreCodec.sendTextMessage(
                            recipientPrefixHex = requireNotNull(message.destinationId),
                            text = message.text,
                            timestampSeconds = timestampSeconds,
                        ),
                        matches = { it is MeshCoreFrame.Sent },
                    ) as MeshCoreFrame.Sent
                    if (message.wantAck) {
                        pendingAcks[sent.expectedAck] = message.id
                    }
                    SendResult.Accepted(radioMessageId = sent.expectedAck.toString())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MeshCoreErrorException) {
            SendResult.Failed(
                "MeshCore rejected the message: ${MeshCoreProtocol.errorMessage(e.errorCode)}",
                // A full table clears as the radio drains its queue; the rest are
                // permanent for this message as written.
                retryable = e.errorCode == MeshCoreProtocol.ERR_CODE_TABLE_FULL,
            )
        } catch (e: MeshCoreTimeoutException) {
            SendResult.Failed("MeshCore radio did not respond", retryable = true)
        } catch (e: RadioLinkException) {
            SendResult.Failed("MeshCore link error: ${e.message}", retryable = true)
        }
    }

    override suspend fun getDeviceInfo(): RadioDeviceInfo {
        val session = session
        if (session != null && _connectionState.value.isConnected) {
            val battery = runCatching { readBattery(session) }.getOrNull()
            val current = _deviceInfo.value
            if (current != null) {
                _deviceInfo.value = current.copy(
                    batteryMilliVolts = battery?.batteryMilliVolts ?: current.batteryMilliVolts,
                    linkRssi = runCatching { transport.readLinkRssi() }.getOrNull() ?: current.linkRssi,
                )
            }
        }
        return _deviceInfo.value ?: RadioDeviceInfo(protocol = MeshProtocol.MESHCORE)
    }

    /** Releases the adapter's scope. Only for a full teardown of this radio slot. */
    fun shutdown() {
        scope.cancel()
    }

    private fun fail(reason: String) {
        diagnostics(reason)
        _connectionState.value = RadioConnectionState.Error(reason, recoverable = true)
    }

    /**
     * Stable id for an inbound message.
     *
     * MeshCore does not give messages ids, so one is derived from sender,
     * timestamp and content. Two identical texts from the same contact in the
     * same second collapse to one row — which is the desired behaviour for a
     * flood-routed network that can deliver the same message twice.
     */
    private fun messageId(sender: String, timestampSeconds: Long, text: String): String =
        "mc-$sender-$timestampSeconds-${text.hashCode().toUInt().toString(16)}"

    private companion object {
        const val DEFAULT_APP_NAME = "UnifiedMesh"
        const val MAX_DRAIN_PER_TICKLE = 64
        const val MAX_CHANNEL_PROBE = 8

        /**
         * Contact sync can stream many frames over a slow BLE link, so it gets a
         * longer budget than an ordinary single-reply command.
         */
        const val CONTACT_SYNC_TIMEOUT_MILLIS = 20_000L

        /**
         * MeshCore group messages are transmitted as `"<sender>: <text>"`; the
         * frame has no separate sender field for channel traffic.
         */
        fun splitChannelSender(raw: String): Pair<String?, String> {
            val separator = raw.indexOf(": ")
            if (separator <= 0 || separator > 32) return null to raw
            val name = raw.substring(0, separator)
            if (name.any { it == '\n' }) return null to raw
            return name to raw.substring(separator + 2)
        }
    }
}

private fun MeshCoreFrame.Contact.toMeshNode(): MeshNode = MeshNode(
    protocol = MeshProtocol.MESHCORE,
    id = id,
    longName = name,
    shortName = null,
    lastHeard = lastAdvertTimestamp.takeIf { it > 0 }?.times(1000),
    position = if (latitude != null && longitude != null) {
        NodePosition(latitude, longitude).takeIf { it.isValid }
    } else {
        null
    },
    // The contact frame carries no battery or SNR: those only arrive with traffic.
    batteryLevel = null,
    snr = null,
    hopsAway = null,
    isSelf = false,
)
