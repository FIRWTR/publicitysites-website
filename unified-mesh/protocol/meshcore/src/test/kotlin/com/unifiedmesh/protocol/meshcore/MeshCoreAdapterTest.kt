package com.unifiedmesh.protocol.meshcore

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.unifiedmesh.core.model.Clock
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.OutgoingMessage
import com.unifiedmesh.core.model.RadioDevice
import com.unifiedmesh.core.model.SendResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Drives [MeshCoreAdapter] against a simulated radio that speaks the real frame
 * format, so the handshake sequence and the frames on the wire are both checked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshCoreAdapterTest {

    private val device = RadioDevice(address = "AA:BB:CC:DD:EE:02", name = "T1000-E")
    private val clock = Clock { 1_700_000_000_000L }

    private fun adapterFor(radio: FakeMeshCoreRadio, scheduler: kotlinx.coroutines.test.TestCoroutineScheduler) =
        MeshCoreAdapter(transport = radio, clock = clock, dispatcher = StandardTestDispatcher(scheduler))

    @Test
    fun `handshake issues device query then app start before anything else`() = runTest {
        val radio = FakeMeshCoreRadio()
        val adapter = adapterFor(radio, testScheduler)

        adapter.connect(device)
        advanceUntilIdle()

        val codes = radio.commandsReceived.map { it[0].toInt() and 0xFF }
        assertThat(codes.first()).isEqualTo(MeshCoreProtocol.CMD_DEVICE_QUERY)
        assertThat(codes[1]).isEqualTo(MeshCoreProtocol.CMD_APP_START)
        // The version byte is what unlocks the SNR-carrying V3 message frames.
        assertThat(radio.commandsReceived[0][1].toInt()).isAtLeast(3)
    }

    @Test
    fun `connect reaches Connected and publishes device info`() = runTest {
        val radio = FakeMeshCoreRadio()
        val adapter = adapterFor(radio, testScheduler)

        adapter.connect(device)
        advanceUntilIdle()

        assertThat(adapter.connectionState.value.isConnected).isTrue()
        val info = adapter.deviceInfo.value!!
        assertThat(info.protocol).isEqualTo(MeshProtocol.MESHCORE)
        assertThat(info.hardwareModel).isEqualTo("T1000-E")
        assertThat(info.firmwareVersion).isEqualTo("v1.9.0")
        assertThat(info.batteryMilliVolts).isEqualTo(3920)
        assertThat(info.nodeName).isEqualTo("Phone Companion")
        // MeshCore gives millivolts, not a percentage; the app must not invent one.
        assertThat(info.batteryLevel).isNull()
    }

    @Test
    fun `contacts become nodes`() = runTest {
        val radio = FakeMeshCoreRadio()
        val adapter = adapterFor(radio, testScheduler)

        adapter.connect(device)
        advanceUntilIdle()

        val names = adapter.nodes.value.map { it.displayName }
        assertThat(names).containsExactly("Elliott", "North Ridge")
        val ridge = adapter.nodes.value.first { it.displayName == "North Ridge" }
        assertThat(ridge.position!!.latitude).isWithin(1e-6).of(44.5019)
        // A contact with no coordinates must not be plotted at 0,0.
        assertThat(adapter.nodes.value.first { it.displayName == "Elliott" }.position).isNull()
    }

    @Test
    fun `channels are read from the radio`() = runTest {
        val radio = FakeMeshCoreRadio()
        val adapter = adapterFor(radio, testScheduler)

        adapter.connect(device)
        advanceUntilIdle()

        assertThat(adapter.channels.value.map { it.name }).containsExactly("Public", "Emergency").inOrder()
        assertThat(adapter.channels.value.first().isPrimary).isTrue()
    }

    @Test
    fun `a queued message is drained on connect`() = runTest {
        val radio = FakeMeshCoreRadio()
        val adapter = adapterFor(radio, testScheduler)

        radio.deliverChannelMessage(0, "Elliott: Copy. See you soon.", 1_700_000_100)

        adapter.incomingMessages.test {
            adapter.connect(device)
            advanceUntilIdle()

            val message = awaitItem()
            assertThat(message.protocol).isEqualTo(MeshProtocol.MESHCORE)
            assertThat(message.channelId).isEqualTo("0")
            // The sender's name travels inside the payload for channel traffic.
            assertThat(message.senderName).isEqualTo("Elliott")
            assertThat(message.text).isEqualTo("Copy. See you soon.")
            assertThat(message.snr).isEqualTo(5.0f)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a message-waiting push triggers a drain`() = runTest {
        val radio = FakeMeshCoreRadio()
        val adapter = adapterFor(radio, testScheduler)
        adapter.connect(device)
        advanceUntilIdle()

        adapter.incomingMessages.test {
            radio.deliverDirectMessage("1a1b1c1d1e1f", "Ridge is clear.", 1_700_000_200)
            advanceUntilIdle()

            val message = awaitItem()
            assertThat(message.senderId).isEqualTo("1a1b1c1d1e1f")
            assertThat(message.channelId).isNull()
            assertThat(message.text).isEqualTo("Ridge is clear.")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `CLI data frames never reach the inbox`() = runTest {
        val radio = FakeMeshCoreRadio()
        val adapter = adapterFor(radio, testScheduler)
        adapter.connect(device)
        advanceUntilIdle()

        adapter.incomingMessages.test {
            // A TXT_TYPE_CLI_DATA reply: machine traffic, not chat. If this ever
            // reached incomingMessages the bridge could relay a CLI response.
            radio.deliverCliData("1a1b1c1d1e1f", "ver: v1.9.0", 1_700_000_300)
            radio.deliverDirectMessage("1a1b1c1d1e1f", "and this is chat", 1_700_000_400)
            advanceUntilIdle()

            assertThat(awaitItem().text).isEqualTo("and this is chat")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sending a channel message writes the documented frame`() = runTest {
        val radio = FakeMeshCoreRadio()
        val adapter = adapterFor(radio, testScheduler)
        adapter.connect(device)
        advanceUntilIdle()
        radio.commandsReceived.clear()

        val result = adapter.sendMessage(
            OutgoingMessage(
                id = "m1",
                protocol = MeshProtocol.MESHCORE,
                channelId = "0",
                text = "on my way",
                timestamp = 1_700_000_500_000L,
            ),
        )
        advanceUntilIdle()

        assertThat(result).isInstanceOf(SendResult.Accepted::class.java)
        val sent = radio.commandsReceived.first { (it[0].toInt() and 0xFF) == MeshCoreProtocol.CMD_SEND_CHANNEL_TXT_MSG }
        assertThat(sent[1].toInt()).isEqualTo(MeshCoreProtocol.TXT_TYPE_PLAIN)
        assertThat(sent[2].toInt()).isEqualTo(0)
        // Timestamp goes on the wire in seconds, not milliseconds.
        val timestamp = (0..3).sumOf { (sent[3 + it].toLong() and 0xFF) shl (it * 8) }
        assertThat(timestamp).isEqualTo(1_700_000_500L)
        assertThat(String(sent, 7, sent.size - 7)).isEqualTo("on my way")
    }

    @Test
    fun `a direct send records the ack code and resolves on confirmation`() = runTest {
        val radio = FakeMeshCoreRadio()
        val adapter = adapterFor(radio, testScheduler)
        adapter.connect(device)
        advanceUntilIdle()

        adapter.deliveryUpdates.test {
            val result = adapter.sendMessage(
                OutgoingMessage(
                    id = "dm-1",
                    protocol = MeshProtocol.MESHCORE,
                    destinationId = "1a1b1c1d1e1f",
                    text = "meet at the truck",
                    timestamp = 1_700_000_600_000L,
                ),
            )
            advanceUntilIdle()
            assertThat(result).isInstanceOf(SendResult.Accepted::class.java)

            radio.confirmSend(FakeMeshCoreRadio.LAST_ACK_CODE)
            advanceUntilIdle()

            val update = awaitItem()
            assertThat(update.messageId).isEqualTo("dm-1")
            assertThat(update.state).isEqualTo(com.unifiedmesh.core.model.DeliveryState.DELIVERED)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a table-full rejection is reported as retryable`() = runTest {
        val radio = FakeMeshCoreRadio()
        val adapter = adapterFor(radio, testScheduler)
        adapter.connect(device)
        advanceUntilIdle()
        radio.failNextSend = true
        radio.sendErrorCode = MeshCoreProtocol.ERR_CODE_TABLE_FULL

        val result = adapter.sendMessage(
            OutgoingMessage("m2", MeshProtocol.MESHCORE, channelId = "0", text = "retry me", timestamp = 0),
        )
        advanceUntilIdle()

        val failure = result as SendResult.Failed
        assertThat(failure.retryable).isTrue()
        assertThat(failure.reason).contains("table full")
    }

    @Test
    fun `an unknown recipient is reported as not retryable`() = runTest {
        val radio = FakeMeshCoreRadio()
        val adapter = adapterFor(radio, testScheduler)
        adapter.connect(device)
        advanceUntilIdle()
        radio.failNextSend = true
        radio.sendErrorCode = MeshCoreProtocol.ERR_CODE_NOT_FOUND

        val result = adapter.sendMessage(
            OutgoingMessage("m3", MeshProtocol.MESHCORE, destinationId = "aabbccddeeff", text = "hello", timestamp = 0),
        )
        advanceUntilIdle()

        assertThat((result as SendResult.Failed).retryable).isFalse()
    }

    @Test
    fun `text longer than the protocol limit is refused before it hits the radio`() = runTest {
        val radio = FakeMeshCoreRadio()
        val adapter = adapterFor(radio, testScheduler)
        adapter.connect(device)
        advanceUntilIdle()
        radio.commandsReceived.clear()

        val result = adapter.sendMessage(
            OutgoingMessage(
                id = "long",
                protocol = MeshProtocol.MESHCORE,
                channelId = "0",
                text = "z".repeat(MeshCoreProtocol.MAX_TEXT_LENGTH + 1),
                timestamp = 0,
            ),
        )

        assertThat(result).isInstanceOf(SendResult.Failed::class.java)
        assertThat(radio.commandsReceived).isEmpty()
    }

    @Test
    fun `sending while disconnected fails instead of throwing`() = runTest {
        val radio = FakeMeshCoreRadio()
        val adapter = adapterFor(radio, testScheduler)

        val result = adapter.sendMessage(
            OutgoingMessage("m4", MeshProtocol.MESHCORE, channelId = "0", text = "nobody home", timestamp = 0),
        )

        assertThat((result as SendResult.Failed).retryable).isTrue()
    }

    @Test
    fun `disconnect clears state and closes the link`() = runTest {
        val radio = FakeMeshCoreRadio()
        val adapter = adapterFor(radio, testScheduler)
        adapter.connect(device)
        advanceUntilIdle()

        adapter.disconnect()
        advanceUntilIdle()

        assertThat(adapter.connectionState.value.isConnected).isFalse()
        assertThat(adapter.nodes.value).isEmpty()
        assertThat(adapter.deviceInfo.value).isNull()
        assertThat(radio.linkState.value).isEqualTo(com.unifiedmesh.protocol.api.LinkState.CLOSED)
    }

    @Test
    fun `a new advert push adds the contact without a full resync`() = runTest {
        val radio = FakeMeshCoreRadio()
        val adapter = adapterFor(radio, testScheduler)
        adapter.connect(device)
        advanceUntilIdle()
        val before = adapter.nodes.value.size

        radio.deliverNewAdvert(keyByte = 0x77, name = "Truck")
        advanceUntilIdle()

        assertThat(adapter.nodes.value).hasSize(before + 1)
        assertThat(adapter.nodes.value.map { it.displayName }).contains("Truck")
    }
}
