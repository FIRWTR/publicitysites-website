package com.unifiedmesh.protocol.meshtastic

import com.google.protobuf.ByteString
import com.google.common.truth.Truth.assertThat
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.MessageClass
import com.unifiedmesh.core.model.MessageDirection
import com.unifiedmesh.core.model.OutgoingMessage
import org.junit.Test
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.Portnums

/** Conversion between Meshtastic protobufs and the app's model. */
class MeshtasticCodecTest {

    private val selfNodeNum = 0x7C3F11A2L
    private val bear = 0xA1B2C3D4L

    private fun textPacket(
        from: Long = bear,
        to: Long = MeshtasticProtocol.BROADCAST_ADDRESS,
        text: String = "hello",
        channel: Int = 0,
        rxTime: Int = 0,
        portnum: Portnums.PortNum = Portnums.PortNum.TEXT_MESSAGE_APP,
    ): MeshProtos.MeshPacket = MeshProtos.MeshPacket.newBuilder()
        .setFrom(from.toInt())
        .setTo(to.toInt())
        .setChannel(channel)
        .setId(0x1234)
        .apply { if (rxTime != 0) setRxTime(rxTime) }
        .setDecoded(
            MeshProtos.Data.newBuilder()
                .setPortnum(portnum)
                .setPayload(ByteString.copyFromUtf8(text)),
        )
        .build()

    @Test
    fun `node numbers format the way the firmware writes User_id`() {
        assertThat(MeshtasticProtocol.formatNodeId(0xA1B2C3D4L)).isEqualTo("!a1b2c3d4")
        // The high bit set must not produce a negative or sign-extended id.
        assertThat(MeshtasticProtocol.formatNodeId(0xFFFFFFFFL)).isEqualTo("!ffffffff")
        assertThat(MeshtasticProtocol.parseNodeId("!a1b2c3d4")).isEqualTo(0xA1B2C3D4L)
        assertThat(MeshtasticProtocol.parseNodeId("not-a-node")).isNull()
    }

    @Test
    fun `only text packets are classified as chat`() {
        assertThat(MeshtasticCodec.classify(textPacket())).isEqualTo(MessageClass.TEXT)
        assertThat(MeshtasticCodec.classify(textPacket(portnum = Portnums.PortNum.POSITION_APP)))
            .isEqualTo(MessageClass.POSITION)
        assertThat(MeshtasticCodec.classify(textPacket(portnum = Portnums.PortNum.TELEMETRY_APP)))
            .isEqualTo(MessageClass.TELEMETRY)
        assertThat(MeshtasticCodec.classify(textPacket(portnum = Portnums.PortNum.ROUTING_APP)))
            .isEqualTo(MessageClass.ROUTING)
        assertThat(MeshtasticCodec.classify(textPacket(portnum = Portnums.PortNum.ADMIN_APP)))
            .isEqualTo(MessageClass.ADMIN)
    }

    @Test
    fun `an encrypted packet is never treated as text`() {
        val encrypted = MeshProtos.MeshPacket.newBuilder()
            .setFrom(bear.toInt())
            .setTo(MeshtasticProtocol.BROADCAST_ADDRESS.toInt())
            .setEncrypted(ByteString.copyFrom(ByteArray(16)))
            .build()

        assertThat(MeshtasticCodec.classify(encrypted)).isEqualTo(MessageClass.OTHER)
        assertThat(
            MeshtasticCodec.toUnifiedMessage(encrypted, selfNodeNum, receivedAtMillis = 1L),
        ).isNull()
    }

    @Test
    fun `a broadcast packet becomes a channel message`() {
        val message = MeshtasticCodec.toUnifiedMessage(
            packet = textPacket(channel = 2, rxTime = 1_700_000_000),
            selfNodeNum = selfNodeNum,
            nameLookup = { if (it == bear) "Bear" else null },
            receivedAtMillis = 999L,
        )!!

        assertThat(message.protocol).isEqualTo(MeshProtocol.MESHTASTIC)
        assertThat(message.channelId).isEqualTo("2")
        assertThat(message.destinationId).isNull()
        assertThat(message.senderName).isEqualTo("Bear")
        assertThat(message.conversationId).isEqualTo("MESHTASTIC:CHANNEL:2")
        assertThat(message.direction).isEqualTo(MessageDirection.INCOMING)
        assertThat(message.timestamp).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun `a packet with no rx_time falls back to local receipt time`() {
        // The firmware omits rx_time until it has a clock; without the fallback
        // every such message would sort to 1970 in the inbox.
        val message = MeshtasticCodec.toUnifiedMessage(
            packet = textPacket(rxTime = 0),
            selfNodeNum = selfNodeNum,
            receivedAtMillis = 1_700_000_777_000L,
        )!!

        assertThat(message.timestamp).isEqualTo(1_700_000_777_000L)
    }

    @Test
    fun `a packet the radio echoes back from us is marked outgoing`() {
        val message = MeshtasticCodec.toUnifiedMessage(
            packet = textPacket(from = selfNodeNum, to = bear),
            selfNodeNum = selfNodeNum,
            receivedAtMillis = 1L,
        )!!

        assertThat(message.direction).isEqualTo(MessageDirection.OUTGOING)
        // A direct message threads under the other party whichever end we are.
        assertThat(message.conversationId).isEqualTo("MESHTASTIC:DIRECT:!a1b2c3d4")
    }

    @Test
    fun `an empty text payload produces nothing`() {
        assertThat(
            MeshtasticCodec.toUnifiedMessage(textPacket(text = ""), selfNodeNum, receivedAtMillis = 1L),
        ).isNull()
    }

    @Test
    fun `outgoing channel messages broadcast and outgoing direct messages address a node`() {
        val channelPacket = MeshtasticCodec.toRadioTextPacket(
            OutgoingMessage("a", MeshProtocol.MESHTASTIC, channelId = "1", text = "hi", timestamp = 0),
            packetId = 7,
        ).packet
        assertThat(channelPacket.to.toLong() and 0xFFFFFFFFL).isEqualTo(MeshtasticProtocol.BROADCAST_ADDRESS)
        assertThat(channelPacket.channel).isEqualTo(1)
        assertThat(channelPacket.hopLimit).isEqualTo(MeshtasticCodec.DEFAULT_HOP_LIMIT)

        val directPacket = MeshtasticCodec.toRadioTextPacket(
            OutgoingMessage("b", MeshProtocol.MESHTASTIC, destinationId = "!a1b2c3d4", text = "hi", timestamp = 0),
            packetId = 8,
        ).packet
        assertThat(directPacket.to.toLong() and 0xFFFFFFFFL).isEqualTo(bear)
        assertThat(directPacket.wantAck).isTrue()
    }

    @Test
    fun `a destination that is not a node id is rejected`() {
        val error = runCatching {
            MeshtasticCodec.toRadioTextPacket(
                OutgoingMessage("c", MeshProtocol.MESHTASTIC, destinationId = "1a2b3c4d5e6f", text = "hi", timestamp = 0),
                packetId = 9,
            )
        }.exceptionOrNull()

        // That is a MeshCore public-key prefix; it must not be silently coerced.
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `generated packet ids are never zero`() {
        // Zero means "assign one for me" to the firmware, which would make the
        // routing acknowledgement impossible to correlate.
        repeat(500) { assertThat(MeshtasticCodec.newPacketId()).isNotEqualTo(0) }
    }

    @Test
    fun `a routing packet becomes a delivery outcome`() {
        val routing = MeshProtos.Routing.newBuilder()
            .setErrorReason(MeshProtos.Routing.Error.NONE)
            .build()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setFrom(bear.toInt())
            .setDecoded(
                MeshProtos.Data.newBuilder()
                    .setPortnum(Portnums.PortNum.ROUTING_APP)
                    .setRequestId(4242)
                    .setPayload(routing.toByteString()),
            )
            .build()

        val outcome = MeshtasticCodec.toDeliveryOutcome(packet)!!
        assertThat(outcome.packetId).isEqualTo(4242)
        assertThat(outcome.state).isEqualTo(com.unifiedmesh.core.model.DeliveryState.DELIVERED)

        // A routing packet with no request_id answers nothing of ours.
        assertThat(MeshtasticCodec.toDeliveryOutcome(textPacket(portnum = Portnums.PortNum.ROUTING_APP))).isNull()
    }

    @Test
    fun `node info converts including position and hops`() {
        val nodeInfo = MeshProtos.NodeInfo.newBuilder()
            .setNum(bear.toInt())
            .setSnr(6.25f)
            .setLastHeard(1_700_000_000)
            .setHopsAway(2)
            .setUser(
                MeshProtos.User.newBuilder()
                    .setId("!a1b2c3d4")
                    .setLongName("Bear")
                    .setShortName("BEAR"),
            )
            .setPosition(
                MeshProtos.Position.newBuilder()
                    .setLatitudeI(444_280_000)
                    .setLongitudeI(-1_105_885_000)
                    .setAltitude(2360),
            )
            .build()

        val node = MeshtasticCodec.toMeshNode(nodeInfo, selfNodeNum)

        assertThat(node.id).isEqualTo("!a1b2c3d4")
        assertThat(node.displayName).isEqualTo("Bear")
        assertThat(node.hopsAway).isEqualTo(2)
        assertThat(node.lastHeard).isEqualTo(1_700_000_000_000L)
        assertThat(node.position!!.altitudeMeters).isEqualTo(2360)
        assertThat(node.isSelf).isFalse()
    }

    @Test
    fun `a node with no position fix has no position`() {
        val nodeInfo = MeshProtos.NodeInfo.newBuilder()
            .setNum(bear.toInt())
            .setUser(MeshProtos.User.newBuilder().setId("!a1b2c3d4").setLongName("Bear"))
            // A Position message with no latitude_i/longitude_i set at all.
            .setPosition(MeshProtos.Position.newBuilder().setTime(1_700_000_000))
            .build()

        assertThat(MeshtasticCodec.toMeshNode(nodeInfo, selfNodeNum).position).isNull()
    }

    @Test
    fun `a node reporting zero-zero is treated as having no fix`() {
        val nodeInfo = MeshProtos.NodeInfo.newBuilder()
            .setNum(bear.toInt())
            .setUser(MeshProtos.User.newBuilder().setId("!a1b2c3d4"))
            .setPosition(MeshProtos.Position.newBuilder().setLatitudeI(0).setLongitudeI(0))
            .build()

        assertThat(MeshtasticCodec.toMeshNode(nodeInfo, selfNodeNum).position).isNull()
    }
}
