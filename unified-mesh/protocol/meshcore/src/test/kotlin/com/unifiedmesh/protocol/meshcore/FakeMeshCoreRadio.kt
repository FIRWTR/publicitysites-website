package com.unifiedmesh.protocol.meshcore

import com.unifiedmesh.core.model.RadioDevice
import com.unifiedmesh.protocol.api.LinkState
import com.unifiedmesh.protocol.api.RadioLinkException
import com.unifiedmesh.protocol.api.RadioLinkTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A simulated MeshCore companion radio, driven entirely through the real
 * [MeshCoreCodec] frame formats.
 *
 * It answers the commands the adapter actually issues, so a test exercises the
 * genuine handshake sequence and frame encoding rather than a mock of it.
 */
class FakeMeshCoreRadio(
    private val nodeName: String = "Phone Companion",
    private val contacts: List<ContactSpec> = DEFAULT_CONTACTS,
    private val channelNames: List<String> = listOf("Public", "Emergency"),
) : RadioLinkTransport {

    data class ContactSpec(
        val keyByte: Int,
        val name: String,
        val latMicroDegrees: Int = 0,
        val lonMicroDegrees: Int = 0,
        val lastMod: Long = 100,
    )

    private val _linkState = MutableStateFlow(LinkState.CLOSED)
    override val linkState = _linkState.asStateFlow()

    // Channel-backed, matching the RadioLinkTransport contract: a radio answers
    // faster than the reading coroutine can be scheduled, so replies emitted
    // before collection starts have to be buffered rather than dropped.
    private val _incoming = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = _incoming.receiveAsFlow()

    override val maxFrameSize: Int = MeshCoreProtocol.MAX_FRAME_SIZE

    /** Every command frame the adapter wrote, in order. */
    val commandsReceived = mutableListOf<ByteArray>()

    /** Queue the radio hands out one at a time in response to CMD_SYNC_NEXT_MESSAGE. */
    private val messageQueue = ArrayDeque<ByteArray>()

    var failNextSend = false
    var sendErrorCode = MeshCoreProtocol.ERR_CODE_TABLE_FULL
    var linkRssi: Int? = -82

    override suspend fun open(device: RadioDevice) {
        _linkState.value = LinkState.OPEN
    }

    override suspend fun close() {
        _linkState.value = LinkState.CLOSED
    }

    override suspend fun readLinkRssi(): Int? = linkRssi

    override suspend fun send(frame: ByteArray) {
        if (_linkState.value != LinkState.OPEN) throw RadioLinkException("link is not open")
        commandsReceived += frame
        respondTo(frame)
    }

    /** Queues an inbound channel message and tickles the adapter, like real firmware. */
    suspend fun deliverChannelMessage(
        channelIndex: Int,
        text: String,
        timestampSeconds: Long,
        snrQuarterDb: Int = 20,
    ) {
        messageQueue += bytes(MeshCoreProtocol.RESP_CODE_CHANNEL_MSG_RECV_V3, snrQuarterDb, 0, 0) +
            bytes(channelIndex, MeshCoreProtocol.PATH_UNKNOWN, MeshCoreProtocol.TXT_TYPE_PLAIN) +
            le32(timestampSeconds) + text.toByteArray(Charsets.UTF_8)
        _incoming.send(bytes(MeshCoreProtocol.PUSH_CODE_MSG_WAITING))
    }

    /** Queues an inbound direct message and tickles the adapter. */
    suspend fun deliverDirectMessage(
        senderPrefixHex: String,
        text: String,
        timestampSeconds: Long,
        snrQuarterDb: Int = 16,
    ) {
        messageQueue += bytes(MeshCoreProtocol.RESP_CODE_CONTACT_MSG_RECV_V3, snrQuarterDb, 0, 0) +
            hex(senderPrefixHex) +
            bytes(MeshCoreProtocol.PATH_UNKNOWN, MeshCoreProtocol.TXT_TYPE_PLAIN) +
            le32(timestampSeconds) + text.toByteArray(Charsets.UTF_8)
        _incoming.send(bytes(MeshCoreProtocol.PUSH_CODE_MSG_WAITING))
    }

    /**
     * Queues a `TXT_TYPE_CLI_DATA` reply — the radio answering a CLI command.
     *
     * This is machine traffic and must never surface as chat.
     */
    suspend fun deliverCliData(senderPrefixHex: String, text: String, timestampSeconds: Long) {
        messageQueue += bytes(MeshCoreProtocol.RESP_CODE_CONTACT_MSG_RECV_V3, 16, 0, 0) +
            hex(senderPrefixHex) +
            bytes(MeshCoreProtocol.PATH_UNKNOWN, MeshCoreProtocol.TXT_TYPE_CLI_DATA) +
            le32(timestampSeconds) + text.toByteArray(Charsets.UTF_8)
        _incoming.send(bytes(MeshCoreProtocol.PUSH_CODE_MSG_WAITING))
    }

    /**
     * Sends a `PUSH_CODE_NEW_ADVERT`, which the firmware builds with
     * `writeContactRespFrame` and so shares the contact frame layout.
     */
    suspend fun deliverNewAdvert(keyByte: Int, name: String, lastMod: Long = 300) {
        _incoming.send(
            contactFrame(
                MeshCoreProtocol.PUSH_CODE_NEW_ADVERT,
                ContactSpec(keyByte = keyByte, name = name, lastMod = lastMod),
            ),
        )
    }

    /** Sends a `PUSH_CODE_SEND_CONFIRMED` for a previously reported ack code. */
    suspend fun confirmSend(ackCode: Long, roundTripMillis: Long = 1200) {
        _incoming.send(bytes(MeshCoreProtocol.PUSH_CODE_SEND_CONFIRMED) + le32(ackCode) + le32(roundTripMillis))
    }

    private suspend fun respondTo(frame: ByteArray) {
        when (frame[0].toInt() and 0xFF) {
            MeshCoreProtocol.CMD_DEVICE_QUERY -> _incoming.send(deviceInfoFrame())
            MeshCoreProtocol.CMD_APP_START -> _incoming.send(selfInfoFrame())
            MeshCoreProtocol.CMD_SET_DEVICE_TIME -> _incoming.send(bytes(MeshCoreProtocol.RESP_CODE_OK))

            MeshCoreProtocol.CMD_GET_CONTACTS -> {
                _incoming.send(bytes(MeshCoreProtocol.RESP_CODE_CONTACTS_START) + le32(contacts.size.toLong()))
                contacts.forEach { _incoming.send(contactFrame(MeshCoreProtocol.RESP_CODE_CONTACT, it)) }
                val newest = contacts.maxOfOrNull { it.lastMod } ?: 0L
                _incoming.send(bytes(MeshCoreProtocol.RESP_CODE_END_OF_CONTACTS) + le32(newest))
            }

            MeshCoreProtocol.CMD_GET_CHANNEL -> {
                val index = frame[1].toInt() and 0xFF
                val name = channelNames.getOrNull(index)
                if (name == null) {
                    _incoming.send(bytes(MeshCoreProtocol.RESP_CODE_ERR, MeshCoreProtocol.ERR_CODE_NOT_FOUND))
                } else {
                    _incoming.send(
                        bytes(MeshCoreProtocol.RESP_CODE_CHANNEL_INFO, index) +
                            padded(name, 32) + ByteArray(16),
                    )
                }
            }

            MeshCoreProtocol.CMD_GET_BATT_AND_STORAGE ->
                _incoming.send(
                    bytes(MeshCoreProtocol.RESP_CODE_BATT_AND_STORAGE) +
                        le16(3920) + le32(120) + le32(4096),
                )

            MeshCoreProtocol.CMD_SYNC_NEXT_MESSAGE ->
                _incoming.send(messageQueue.removeFirstOrNull() ?: bytes(MeshCoreProtocol.RESP_CODE_NO_MORE_MESSAGES))

            MeshCoreProtocol.CMD_SEND_TXT_MSG ->
                if (failNextSend) {
                    failNextSend = false
                    _incoming.send(bytes(MeshCoreProtocol.RESP_CODE_ERR, sendErrorCode))
                } else {
                    _incoming.send(
                        bytes(MeshCoreProtocol.RESP_CODE_SENT, 1) + le32(LAST_ACK_CODE) + le32(4000),
                    )
                }

            MeshCoreProtocol.CMD_SEND_CHANNEL_TXT_MSG ->
                if (failNextSend) {
                    failNextSend = false
                    _incoming.send(bytes(MeshCoreProtocol.RESP_CODE_ERR, sendErrorCode))
                } else {
                    _incoming.send(bytes(MeshCoreProtocol.RESP_CODE_OK))
                }

            else -> _incoming.send(bytes(MeshCoreProtocol.RESP_CODE_ERR, MeshCoreProtocol.ERR_CODE_UNSUPPORTED_CMD))
        }
    }

    private fun deviceInfoFrame(): ByteArray =
        bytes(MeshCoreProtocol.RESP_CODE_DEVICE_INFO, 13, 50, channelNames.size) +
            le32(0) + padded("2026-08-01", 12) + padded("T1000-E", 40) + padded("v1.9.0", 20) +
            bytes(0, 0)

    private fun selfInfoFrame(): ByteArray =
        bytes(MeshCoreProtocol.RESP_CODE_SELF_INFO, MeshCoreProtocol.ADV_TYPE_CHAT, 20, 22) +
            ByteArray(MeshCoreProtocol.PUB_KEY_SIZE) { (0x9F + it).toByte() } +
            le32(0) + le32(0) +
            bytes(1, 0, 0, 0) +
            le32(869_525) + le32(250_000) +
            bytes(11, 5) +
            nodeName.toByteArray(Charsets.UTF_8)

    private fun contactFrame(code: Int, spec: ContactSpec): ByteArray =
        bytes(code) +
            ByteArray(MeshCoreProtocol.PUB_KEY_SIZE) { (spec.keyByte + it).toByte() } +
            bytes(MeshCoreProtocol.ADV_TYPE_CHAT, 0, MeshCoreProtocol.PATH_UNKNOWN) +
            ByteArray(MeshCoreProtocol.MAX_PATH_SIZE) +
            padded(spec.name, 32) +
            le32(spec.lastMod) +
            le32(spec.latMicroDegrees.toLong() and 0xFFFFFFFFL) +
            le32(spec.lonMicroDegrees.toLong() and 0xFFFFFFFFL) +
            le32(spec.lastMod)

    companion object {
        const val LAST_ACK_CODE = 0x1234ABCDL

        val DEFAULT_CONTACTS = listOf(
            ContactSpec(0x1A, "Elliott", lastMod = 100),
            ContactSpec(0xAA, "North Ridge", latMicroDegrees = 44_501_900, lonMicroDegrees = -110_796_100, lastMod = 200),
        )

        fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

        fun le16(value: Int): ByteArray =
            byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

        fun le32(value: Long): ByteArray = ByteArray(4) { ((value shr (it * 8)) and 0xFF).toByte() }

        fun padded(value: String, width: Int): ByteArray =
            ByteArray(width).also { value.toByteArray(Charsets.UTF_8).copyInto(it) }

        fun hex(value: String): ByteArray = ByteArray(value.length / 2) {
            value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }
}
