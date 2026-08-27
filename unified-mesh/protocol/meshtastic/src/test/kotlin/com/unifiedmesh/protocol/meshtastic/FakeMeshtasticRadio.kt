package com.unifiedmesh.protocol.meshtastic

import com.google.protobuf.ByteString
import com.unifiedmesh.core.model.RadioDevice
import com.unifiedmesh.protocol.api.LinkState
import com.unifiedmesh.protocol.api.RadioLinkException
import com.unifiedmesh.protocol.api.RadioLinkTransport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import org.meshtastic.proto.ChannelProtos
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.Portnums
import org.meshtastic.proto.TelemetryProtos

/**
 * A simulated Meshtastic radio speaking the real client API.
 *
 * On `want_config_id` it replies with the same stream shape the firmware sends:
 * `my_info`, `metadata`, channels, node infos, then `config_complete_id`.
 */
class FakeMeshtasticRadio(
    val myNodeNum: Long = 0x7C3F11A2L,
    private val firmwareVersion: String = "2.7.4",
) : RadioLinkTransport {

    private val _linkState = MutableStateFlow(LinkState.CLOSED)
    override val linkState = _linkState.asStateFlow()

    // Channel-backed per the RadioLinkTransport contract: replies are produced
    // before the reading coroutine is scheduled and must not be dropped.
    private val _incoming = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = _incoming.receiveAsFlow()

    override val maxFrameSize: Int = 512

    /** Every `ToRadio` the adapter wrote, decoded. */
    val toRadioSent = mutableListOf<MeshProtos.ToRadio>()

    var failWrites = false
    var linkRssi: Int? = -74

    /** Set false to simulate firmware that never finishes its config stream. */
    var completeHandshake = true

    override suspend fun open(device: RadioDevice) {
        _linkState.value = LinkState.OPEN
    }

    override suspend fun close() {
        _linkState.value = LinkState.CLOSED
    }

    override suspend fun readLinkRssi(): Int? = linkRssi

    override suspend fun send(frame: ByteArray) {
        if (_linkState.value != LinkState.OPEN) throw RadioLinkException("link is not open")
        if (failWrites) throw RadioLinkException("simulated GATT write failure")
        val toRadio = MeshProtos.ToRadio.parseFrom(frame)
        toRadioSent += toRadio
        if (toRadio.payloadVariantCase == MeshProtos.ToRadio.PayloadVariantCase.WANT_CONFIG_ID) {
            sendConfigStream(toRadio.wantConfigId)
        }
    }

    private suspend fun sendConfigStream(nonce: Int) {
        emit(
            MeshProtos.FromRadio.newBuilder().setMyInfo(
                MeshProtos.MyNodeInfo.newBuilder().setMyNodeNum(myNodeNum.toInt()),
            ),
        )
        emit(
            MeshProtos.FromRadio.newBuilder().setMetadata(
                MeshProtos.DeviceMetadata.newBuilder()
                    .setFirmwareVersion(firmwareVersion)
                    .setHwModel(MeshProtos.HardwareModel.T_DECK),
            ),
        )
        emitChannel(0, "", ChannelProtos.Channel.Role.PRIMARY)
        emitChannel(1, "Emergency", ChannelProtos.Channel.Role.SECONDARY)
        // A disabled channel must not appear as a send target.
        emitChannel(2, "Unused", ChannelProtos.Channel.Role.DISABLED)

        emitNode(myNodeNum, "Phone Node", "PHON", batteryLevel = 92, snr = 0f)
        emitNode(
            0xA1B2C3D4L,
            "Bear",
            "BEAR",
            batteryLevel = 78,
            snr = 6.25f,
            latitudeI = 444_280_000,
            longitudeI = -1_105_885_000,
            hopsAway = 1,
        )
        emitNode(0x55AA77BBL, "Sarah", "SRAH", batteryLevel = 101, snr = 2f)

        if (completeHandshake) {
            emit(MeshProtos.FromRadio.newBuilder().setConfigCompleteId(nonce))
        }
    }

    private suspend fun emitChannel(index: Int, name: String, role: ChannelProtos.Channel.Role) {
        emit(
            MeshProtos.FromRadio.newBuilder().setChannel(
                ChannelProtos.Channel.newBuilder()
                    .setIndex(index)
                    .setRole(role)
                    .setSettings(
                        ChannelProtos.ChannelSettings.newBuilder()
                            .setName(name)
                            // A real radio always carries a PSK here; the app must
                            // never surface it.
                            .setPsk(ByteString.copyFrom(ByteArray(16) { 0x2A })),
                    ),
            ),
        )
    }

    private suspend fun emitNode(
        num: Long,
        longName: String,
        shortName: String,
        batteryLevel: Int,
        snr: Float,
        latitudeI: Int? = null,
        longitudeI: Int? = null,
        hopsAway: Int? = null,
    ) {
        val node = MeshProtos.NodeInfo.newBuilder()
            .setNum(num.toInt())
            .setSnr(snr)
            .setLastHeard(1_700_000_000)
            .setUser(
                MeshProtos.User.newBuilder()
                    .setId(MeshtasticProtocol.formatNodeId(num))
                    .setLongName(longName)
                    .setShortName(shortName),
            )
            .setDeviceMetrics(TelemetryProtos.DeviceMetrics.newBuilder().setBatteryLevel(batteryLevel))
        if (latitudeI != null && longitudeI != null) {
            node.setPosition(
                MeshProtos.Position.newBuilder().setLatitudeI(latitudeI).setLongitudeI(longitudeI).setAltitude(2360),
            )
        }
        if (hopsAway != null) node.setHopsAway(hopsAway)
        emit(MeshProtos.FromRadio.newBuilder().setNodeInfo(node))
    }

    /** Delivers an inbound text packet on a channel. */
    suspend fun deliverChannelText(
        fromNodeNum: Long,
        channel: Int,
        text: String,
        rxTimeSeconds: Int = 1_700_000_100,
        snr: Float = 6.25f,
        rssi: Int = -84,
    ) {
        emit(
            MeshProtos.FromRadio.newBuilder().setPacket(
                textPacket(fromNodeNum, MeshtasticProtocol.BROADCAST_ADDRESS, text)
                    .setChannel(channel)
                    .setRxTime(rxTimeSeconds)
                    .setRxSnr(snr)
                    .setRxRssi(rssi),
            ),
        )
    }

    /** Delivers an inbound direct text packet addressed to this radio. */
    suspend fun deliverDirectText(fromNodeNum: Long, text: String, rxTimeSeconds: Int = 1_700_000_200) {
        emit(
            MeshProtos.FromRadio.newBuilder().setPacket(
                textPacket(fromNodeNum, myNodeNum, text).setRxTime(rxTimeSeconds),
            ),
        )
    }

    /** Delivers a telemetry packet — traffic that must never reach the inbox. */
    suspend fun deliverTelemetry(fromNodeNum: Long) {
        val telemetry = TelemetryProtos.Telemetry.newBuilder()
            .setDeviceMetrics(TelemetryProtos.DeviceMetrics.newBuilder().setBatteryLevel(55))
            .build()
        emit(
            MeshProtos.FromRadio.newBuilder().setPacket(
                MeshProtos.MeshPacket.newBuilder()
                    .setFrom(fromNodeNum.toInt())
                    .setTo(MeshtasticProtocol.BROADCAST_ADDRESS.toInt())
                    .setId(0x5150)
                    .setDecoded(
                        MeshProtos.Data.newBuilder()
                            .setPortnum(Portnums.PortNum.TELEMETRY_APP)
                            .setPayload(telemetry.toByteString()),
                    ),
            ),
        )
    }

    /** Delivers an encrypted packet — one this radio holds no key for. */
    suspend fun deliverEncrypted(fromNodeNum: Long) {
        emit(
            MeshProtos.FromRadio.newBuilder().setPacket(
                MeshProtos.MeshPacket.newBuilder()
                    .setFrom(fromNodeNum.toInt())
                    .setTo(MeshtasticProtocol.BROADCAST_ADDRESS.toInt())
                    .setId(0x6161)
                    .setEncrypted(ByteString.copyFrom(ByteArray(24) { 0x5C })),
            ),
        )
    }

    /** Answers a previously sent packet with a routing acknowledgement. */
    suspend fun deliverRoutingAck(
        requestId: Int,
        error: MeshProtos.Routing.Error = MeshProtos.Routing.Error.NONE,
    ) {
        val routing = MeshProtos.Routing.newBuilder().setErrorReason(error).build()
        emit(
            MeshProtos.FromRadio.newBuilder().setPacket(
                MeshProtos.MeshPacket.newBuilder()
                    .setFrom(0xA1B2C3D4.toInt())
                    .setTo(myNodeNum.toInt())
                    .setId(0x7777)
                    .setDecoded(
                        MeshProtos.Data.newBuilder()
                            .setPortnum(Portnums.PortNum.ROUTING_APP)
                            .setRequestId(requestId)
                            .setPayload(routing.toByteString()),
                    ),
            ),
        )
    }

    /** Tells the client the radio restarted. */
    suspend fun deliverReboot() {
        emit(MeshProtos.FromRadio.newBuilder().setRebooted(true))
    }

    private fun textPacket(from: Long, to: Long, text: String): MeshProtos.MeshPacket.Builder =
        MeshProtos.MeshPacket.newBuilder()
            .setFrom(from.toInt())
            .setTo(to.toInt())
            .setId(nextPacketId())
            .setDecoded(
                MeshProtos.Data.newBuilder()
                    .setPortnum(Portnums.PortNum.TEXT_MESSAGE_APP)
                    .setPayload(ByteString.copyFromUtf8(text)),
            )

    private var packetCounter = 0x1000

    private fun nextPacketId(): Int = ++packetCounter

    private suspend fun emit(builder: MeshProtos.FromRadio.Builder) {
        _incoming.send(builder.build().toByteArray())
    }
}
