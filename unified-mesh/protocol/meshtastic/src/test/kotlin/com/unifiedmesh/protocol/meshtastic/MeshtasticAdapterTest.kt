package com.unifiedmesh.protocol.meshtastic

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.unifiedmesh.core.model.Clock
import com.unifiedmesh.core.model.DeliveryState
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.OutgoingMessage
import com.unifiedmesh.core.model.RadioDevice
import com.unifiedmesh.core.model.SendResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.meshtastic.proto.MeshProtos

/**
 * Drives [MeshtasticAdapter] against a simulated radio that speaks the real
 * client API, so the handshake and the protobufs on the wire are both checked.
 *
 * A connected adapter runs a periodic heartbeat for the lifetime of the session,
 * which means the virtual clock always has another task scheduled in the future.
 * Two consequences shape these tests:
 *
 *  - they use `runCurrent()` rather than `advanceUntilIdle()`, which would never
 *    return; where time genuinely has to pass, they advance by a bounded amount;
 *  - every test runs through [withAdapter], which shuts the adapter's scope down
 *    afterwards. Without that, `runTest`'s own cleanup keeps draining the
 *    scheduler and the never-ending heartbeat exhausts the heap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshtasticAdapterTest {

    private val device = RadioDevice(address = "AA:BB:CC:DD:EE:01", name = "T-Deck")
    private val clock = Clock { 1_700_000_000_000L }

    /**
     * Runs [block] with a fresh simulated radio and adapter, and guarantees the
     * adapter's coroutine scope is cancelled afterwards.
     */
    private fun withAdapter(
        radio: FakeMeshtasticRadio = FakeMeshtasticRadio(),
        block: suspend TestScope.(FakeMeshtasticRadio, MeshtasticAdapter) -> Unit,
    ) = runTest {
        val adapter = MeshtasticAdapter(
            transport = radio,
            clock = clock,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            block(radio, adapter)
        } finally {
            adapter.shutdown()
        }
    }

    @Test
    fun `handshake requests the config stream with the documented nonce`() = withAdapter { radio, adapter ->

        adapter.connect(device)
        runCurrent()

        val first = radio.toRadioSent.first()
        assertThat(first.payloadVariantCase)
            .isEqualTo(MeshProtos.ToRadio.PayloadVariantCase.WANT_CONFIG_ID)
        assertThat(first.wantConfigId).isEqualTo(MeshtasticProtocol.CONFIG_NONCE)
        // A zero nonce would make config_complete_id unmatchable.
        assertThat(first.wantConfigId).isNotEqualTo(0)
    }

    @Test
    fun `connect completes when the radio echoes the nonce`() = withAdapter { radio, adapter ->

        adapter.connect(device)
        runCurrent()

        assertThat(adapter.connectionState.value.isConnected).isTrue()
        val info = adapter.deviceInfo.value!!
        assertThat(info.protocol).isEqualTo(MeshProtocol.MESHTASTIC)
        assertThat(info.firmwareVersion).isEqualTo("2.7.4")
        assertThat(info.hardwareModel).isEqualTo("T_DECK")
        assertThat(info.nodeId).isEqualTo("!7c3f11a2")
        assertThat(info.batteryLevel).isEqualTo(92)
    }

    @Test
    fun `a radio that never finishes its config stream fails rather than hanging`() = withAdapter(FakeMeshtasticRadio().apply { completeHandshake = false }) { radio, adapter ->

        adapter.connect(device)
        runCurrent()

        assertThat(adapter.connectionState.value.isConnected).isFalse()
        assertThat(adapter.connectionState.value)
            .isInstanceOf(com.unifiedmesh.core.model.RadioConnectionState.Error::class.java)
    }

    @Test
    fun `node database becomes the node list`() = withAdapter { radio, adapter ->

        adapter.connect(device)
        runCurrent()

        val nodes = adapter.nodes.value
        assertThat(nodes.map { it.displayName }).containsExactly("Phone Node", "Bear", "Sarah")
        // Our own node sorts first so the Radios screen can find it.
        assertThat(nodes.first().isSelf).isTrue()

        val bear = nodes.first { it.displayName == "Bear" }
        assertThat(bear.id).isEqualTo("!a1b2c3d4")
        assertThat(bear.position!!.latitude).isWithin(1e-6).of(44.428)
        assertThat(bear.position!!.longitude).isWithin(1e-6).of(-110.5885)
        assertThat(bear.hopsAway).isEqualTo(1)

        // battery_level 101 means "plugged in, no battery", not 101% charge.
        assertThat(nodes.first { it.displayName == "Sarah" }.batteryLevel).isNull()
    }

    @Test
    fun `channels come from the radio and disabled ones are excluded`() = withAdapter { radio, adapter ->

        adapter.connect(device)
        runCurrent()

        val channels = adapter.channels.value
        assertThat(channels.map { it.name }).containsExactly("LongFast", "Emergency").inOrder()
        assertThat(channels.first().isPrimary).isTrue()
        // The PSK arrived on the wire but has no field to live in above this layer.
        assertThat(com.unifiedmesh.core.model.MeshChannel::class.java.declaredFields.map { it.name })
            .containsNoneOf("psk", "secret", "key")
    }

    @Test
    fun `an inbound channel message reaches the inbox`() = withAdapter { radio, adapter ->
        adapter.connect(device)
        runCurrent()

        adapter.incomingMessages.test {
            radio.deliverChannelText(0xA1B2C3D4L, channel = 0, text = "We are heading back to camp.")
            runCurrent()

            val message = awaitItem()
            assertThat(message.protocol).isEqualTo(MeshProtocol.MESHTASTIC)
            assertThat(message.senderId).isEqualTo("!a1b2c3d4")
            // The name is resolved from the node database, not carried in the packet.
            assertThat(message.senderName).isEqualTo("Bear")
            assertThat(message.channelId).isEqualTo("0")
            assertThat(message.text).isEqualTo("We are heading back to camp.")
            assertThat(message.snr).isEqualTo(6.25f)
            assertThat(message.rssi).isEqualTo(-84)
            assertThat(message.timestamp).isEqualTo(1_700_000_100_000L)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an inbound direct message threads under the sender`() = withAdapter { radio, adapter ->
        adapter.connect(device)
        runCurrent()

        adapter.incomingMessages.test {
            radio.deliverDirectText(0xA1B2C3D4L, "just you and me")
            runCurrent()

            val message = awaitItem()
            assertThat(message.channelId).isNull()
            assertThat(message.destinationId).isEqualTo("!7c3f11a2")
            assertThat(message.conversationId).isEqualTo("MESHTASTIC:DIRECT:!a1b2c3d4")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `telemetry and encrypted packets never reach the inbox`() = withAdapter { radio, adapter ->
        adapter.connect(device)
        runCurrent()

        adapter.incomingMessages.test {
            // If either of these surfaced as a message, the bridge could relay it.
            radio.deliverTelemetry(0xA1B2C3D4L)
            radio.deliverEncrypted(0x55AA77BBL)
            radio.deliverChannelText(0xA1B2C3D4L, channel = 0, text = "only this is chat")
            runCurrent()

            assertThat(awaitItem().text).isEqualTo("only this is chat")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sending a channel message builds a broadcast packet without want_ack`() = withAdapter { radio, adapter ->
        adapter.connect(device)
        runCurrent()
        radio.toRadioSent.clear()

        val result = adapter.sendMessage(
            OutgoingMessage(
                id = "m1",
                protocol = MeshProtocol.MESHTASTIC,
                channelId = "1",
                text = "on my way",
                timestamp = 1_700_000_500_000L,
            ),
        )
        runCurrent()

        assertThat(result).isInstanceOf(SendResult.Accepted::class.java)
        val packet = radio.toRadioSent.single().packet
        assertThat(packet.to.toLong() and 0xFFFFFFFFL).isEqualTo(MeshtasticProtocol.BROADCAST_ADDRESS)
        assertThat(packet.channel).isEqualTo(1)
        assertThat(packet.decoded.portnum).isEqualTo(org.meshtastic.proto.Portnums.PortNum.TEXT_MESSAGE_APP)
        assertThat(packet.decoded.payload.toStringUtf8()).isEqualTo("on my way")
        assertThat(packet.id).isNotEqualTo(0)
        // Asking every receiver on a channel to acknowledge would flood the air.
        assertThat(packet.wantAck).isFalse()
    }

    @Test
    fun `a direct message requests an ack and resolves on the routing reply`() = withAdapter { radio, adapter ->
        adapter.connect(device)
        runCurrent()
        radio.toRadioSent.clear()

        adapter.deliveryUpdates.test {
            val result = adapter.sendMessage(
                OutgoingMessage(
                    id = "dm-1",
                    protocol = MeshProtocol.MESHTASTIC,
                    destinationId = "!a1b2c3d4",
                    text = "meet at the ridge",
                    timestamp = 1_700_000_600_000L,
                ),
            )
            runCurrent()
            assertThat(result).isInstanceOf(SendResult.Accepted::class.java)

            val packet = radio.toRadioSent.single().packet
            assertThat(packet.wantAck).isTrue()
            assertThat(packet.to.toLong() and 0xFFFFFFFFL).isEqualTo(0xA1B2C3D4L)

            radio.deliverRoutingAck(packet.id)
            runCurrent()

            val update = awaitItem()
            assertThat(update.messageId).isEqualTo("dm-1")
            assertThat(update.state).isEqualTo(DeliveryState.DELIVERED)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a routing error marks the message failed with the reason`() = withAdapter { radio, adapter ->
        adapter.connect(device)
        runCurrent()
        radio.toRadioSent.clear()

        adapter.deliveryUpdates.test {
            adapter.sendMessage(
                OutgoingMessage("dm-2", MeshProtocol.MESHTASTIC, destinationId = "!a1b2c3d4", text = "hi", timestamp = 0),
            )
            runCurrent()

            radio.deliverRoutingAck(
                radio.toRadioSent.single().packet.id,
                MeshProtos.Routing.Error.MAX_RETRANSMIT,
            )
            runCurrent()

            val update = awaitItem()
            assertThat(update.state).isEqualTo(DeliveryState.FAILED)
            assertThat(update.detail).isEqualTo("MAX_RETRANSMIT")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a direct message with no acknowledgement eventually fails`() = withAdapter { radio, adapter ->
        adapter.connect(device)
        runCurrent()

        adapter.deliveryUpdates.test {
            adapter.sendMessage(
                OutgoingMessage("dm-3", MeshProtocol.MESHTASTIC, destinationId = "!a1b2c3d4", text = "hi", timestamp = 0),
            )
            runCurrent()

            // Nothing acknowledges. The message must not sit at "Sending" forever.
            advanceTimeBy(MeshtasticProtocol.ACK_TIMEOUT_MILLIS + 1000)
            runCurrent()

            val update = awaitItem()
            assertThat(update.messageId).isEqualTo("dm-3")
            assertThat(update.state).isEqualTo(DeliveryState.FAILED)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `text that exceeds the payload budget is refused before it hits the radio`() = withAdapter { radio, adapter ->
        adapter.connect(device)
        runCurrent()
        radio.toRadioSent.clear()

        val result = adapter.sendMessage(
            OutgoingMessage(
                id = "long",
                protocol = MeshProtocol.MESHTASTIC,
                channelId = "0",
                text = "z".repeat(MeshtasticProtocol.MAX_TEXT_BYTES + 1),
                timestamp = 0,
            ),
        )

        assertThat((result as SendResult.Failed).retryable).isFalse()
        assertThat(radio.toRadioSent).isEmpty()
    }

    @Test
    fun `a write failure is reported as retryable rather than thrown`() = withAdapter { radio, adapter ->
        adapter.connect(device)
        runCurrent()
        radio.failWrites = true

        val result = adapter.sendMessage(
            OutgoingMessage("m2", MeshProtocol.MESHTASTIC, channelId = "0", text = "hi", timestamp = 0),
        )
        runCurrent()

        assertThat((result as SendResult.Failed).retryable).isTrue()
    }

    @Test
    fun `a radio reboot invalidates the session`() = withAdapter { radio, adapter ->
        adapter.connect(device)
        runCurrent()

        radio.deliverReboot()
        runCurrent()

        assertThat(adapter.connectionState.value.isConnected).isFalse()
    }

    @Test
    fun `disconnect tells the radio and clears state`() = withAdapter { radio, adapter ->
        adapter.connect(device)
        runCurrent()

        adapter.disconnect()
        runCurrent()

        assertThat(radio.toRadioSent.last().disconnect).isTrue()
        assertThat(adapter.connectionState.value.isConnected).isFalse()
        assertThat(adapter.nodes.value).isEmpty()
        assertThat(radio.linkState.value).isEqualTo(com.unifiedmesh.protocol.api.LinkState.CLOSED)
    }

    @Test
    fun `a heartbeat keeps the phone API session alive`() = withAdapter { radio, adapter ->
        adapter.connect(device)
        runCurrent()
        radio.toRadioSent.clear()

        advanceTimeBy(6 * 60 * 1000L)
        runCurrent()

        assertThat(radio.toRadioSent.map { it.payloadVariantCase })
            .contains(MeshProtos.ToRadio.PayloadVariantCase.HEARTBEAT)
    }
}
