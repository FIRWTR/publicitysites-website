package com.unifiedmesh.protocol.api

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.OutgoingMessage
import com.unifiedmesh.core.model.RadioConnectionState
import com.unifiedmesh.core.model.RadioDevice
import com.unifiedmesh.core.model.SendResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The core promise of the app: two radios, one interface, and **no coupling
 * whatsoever between the two connections**.
 *
 * These tests drive both adapters concurrently and assert that every observable
 * of one is untouched by anything that happens to the other.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioIsolationTest {

    private val meshtasticDevice = RadioDevice(address = "AA:BB:CC:DD:EE:01", name = "T-Deck")
    private val meshCoreDevice = RadioDevice(address = "AA:BB:CC:DD:EE:02", name = "T1000-E")

    @Test
    fun `both radios connect and stay connected at the same time`() = runTest {
        val mt = FakeMeshtasticAdapter(parentDispatcherOverride = StandardTestDispatcher(testScheduler))
        val mc = FakeMeshCoreAdapter(parentDispatcherOverride = StandardTestDispatcher(testScheduler))

        mt.connect(meshtasticDevice)
        mc.connect(meshCoreDevice)
        advanceUntilIdle()

        assertThat(mt.connectionState.value.isConnected).isTrue()
        assertThat(mc.connectionState.value.isConnected).isTrue()
        assertThat(mt.protocol).isEqualTo(MeshProtocol.MESHTASTIC)
        assertThat(mc.protocol).isEqualTo(MeshProtocol.MESHCORE)
    }

    @Test
    fun `disconnecting Meshtastic leaves MeshCore connected`() = runTest {
        val mt = FakeMeshtasticAdapter(parentDispatcherOverride = StandardTestDispatcher(testScheduler))
        val mc = FakeMeshCoreAdapter(parentDispatcherOverride = StandardTestDispatcher(testScheduler))

        mt.connect(meshtasticDevice)
        mc.connect(meshCoreDevice)
        advanceUntilIdle()

        mt.disconnect()
        advanceUntilIdle()

        assertThat(mt.connectionState.value).isEqualTo(RadioConnectionState.Disconnected)
        assertThat(mc.connectionState.value.isConnected).isTrue()
        assertThat(mc.deviceInfo.value).isNotNull()
        assertThat(mc.nodes.value).isNotEmpty()
    }

    @Test
    fun `disconnecting MeshCore leaves Meshtastic connected`() = runTest {
        val mt = FakeMeshtasticAdapter(parentDispatcherOverride = StandardTestDispatcher(testScheduler))
        val mc = FakeMeshCoreAdapter(parentDispatcherOverride = StandardTestDispatcher(testScheduler))

        mt.connect(meshtasticDevice)
        mc.connect(meshCoreDevice)
        advanceUntilIdle()

        mc.disconnect()
        advanceUntilIdle()

        assertThat(mc.connectionState.value).isEqualTo(RadioConnectionState.Disconnected)
        assertThat(mt.connectionState.value.isConnected).isTrue()
        assertThat(mt.deviceInfo.value).isNotNull()
        assertThat(mt.nodes.value).isNotEmpty()
    }

    @Test
    fun `a disconnected Meshtastic radio does not stop MeshCore receiving`() = runTest {
        val mt = FakeMeshtasticAdapter(parentDispatcherOverride = StandardTestDispatcher(testScheduler))
        val mc = FakeMeshCoreAdapter(parentDispatcherOverride = StandardTestDispatcher(testScheduler))

        mt.connect(meshtasticDevice)
        mc.connect(meshCoreDevice)
        advanceUntilIdle()
        mt.disconnect()
        advanceUntilIdle()

        mc.incomingMessages.test {
            mc.emitIncoming(FakeIncoming("1a2b3c4d5e6f", "Elliott", "Still here."))
            val received = awaitItem()

            assertThat(received.protocol).isEqualTo(MeshProtocol.MESHCORE)
            assertThat(received.text).isEqualTo("Still here.")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a disconnected MeshCore radio does not stop Meshtastic receiving`() = runTest {
        val mt = FakeMeshtasticAdapter(parentDispatcherOverride = StandardTestDispatcher(testScheduler))
        val mc = FakeMeshCoreAdapter(parentDispatcherOverride = StandardTestDispatcher(testScheduler))

        mt.connect(meshtasticDevice)
        mc.connect(meshCoreDevice)
        advanceUntilIdle()
        mc.disconnect()
        advanceUntilIdle()

        mt.incomingMessages.test {
            mt.emitIncoming(FakeIncoming("!a1b2c3d4", "Bear", "Still here too."))
            val received = awaitItem()

            assertThat(received.protocol).isEqualTo(MeshProtocol.MESHTASTIC)
            assertThat(received.text).isEqualTo("Still here too.")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a send failure on one radio does not affect the other`() = runTest {
        val mt = FakeMeshtasticAdapter(parentDispatcherOverride = StandardTestDispatcher(testScheduler))
        val mc = FakeMeshCoreAdapter(parentDispatcherOverride = StandardTestDispatcher(testScheduler))

        mt.connect(meshtasticDevice)
        mc.connect(meshCoreDevice)
        advanceUntilIdle()
        mt.failSends = true

        val mtResult = mt.sendMessage(outgoing(MeshProtocol.MESHTASTIC, "mt-1"))
        val mcResult = mc.sendMessage(outgoing(MeshProtocol.MESHCORE, "mc-1"))
        advanceUntilIdle()

        assertThat(mtResult).isInstanceOf(SendResult.Failed::class.java)
        assertThat(mcResult).isInstanceOf(SendResult.Accepted::class.java)
        assertThat(mc.connectionState.value.isConnected).isTrue()
    }

    @Test
    fun `both radios transmit independently for Send Via Both`() = runTest {
        val mt = FakeMeshtasticAdapter(parentDispatcherOverride = StandardTestDispatcher(testScheduler))
        val mc = FakeMeshCoreAdapter(parentDispatcherOverride = StandardTestDispatcher(testScheduler))

        mt.connect(meshtasticDevice)
        mc.connect(meshCoreDevice)
        advanceUntilIdle()

        mt.sendMessage(outgoing(MeshProtocol.MESHTASTIC, "both-mt"))
        mc.sendMessage(outgoing(MeshProtocol.MESHCORE, "both-mc"))
        advanceUntilIdle()

        // Each radio saw exactly its own copy. Neither relayed for the other.
        assertThat(mt.sentMessages.map { it.id }).containsExactly("both-mt")
        assertThat(mc.sentMessages.map { it.id }).containsExactly("both-mc")
    }

    @Test
    fun `reconnecting one radio does not disturb the other`() = runTest {
        val mt = FakeMeshtasticAdapter(parentDispatcherOverride = StandardTestDispatcher(testScheduler))
        val mc = FakeMeshCoreAdapter(parentDispatcherOverride = StandardTestDispatcher(testScheduler))

        mt.connect(meshtasticDevice)
        mc.connect(meshCoreDevice)
        advanceUntilIdle()

        val mcInfoBefore = mc.deviceInfo.value

        mt.disconnect()
        advanceUntilIdle()
        mt.connect(meshtasticDevice)
        advanceUntilIdle()

        assertThat(mt.connectionState.value.isConnected).isTrue()
        assertThat(mc.connectionState.value.isConnected).isTrue()
        assertThat(mc.deviceInfo.value).isEqualTo(mcInfoBefore)
    }

    @Test
    fun `tearing one adapter's scope down entirely leaves the other usable`() = runTest {
        val mt = FakeMeshtasticAdapter(parentDispatcherOverride = StandardTestDispatcher(testScheduler))
        val mc = FakeMeshCoreAdapter(parentDispatcherOverride = StandardTestDispatcher(testScheduler))

        mt.connect(meshtasticDevice)
        mc.connect(meshCoreDevice)
        advanceUntilIdle()

        // The hard case: not a graceful disconnect but the whole coroutine scope
        // being cancelled, as happens when a radio slot is torn down.
        mt.shutdown()
        advanceUntilIdle()

        val result = mc.sendMessage(outgoing(MeshProtocol.MESHCORE, "after-teardown"))
        advanceUntilIdle()

        assertThat(result).isInstanceOf(SendResult.Accepted::class.java)
        assertThat(mc.connectionState.value.isConnected).isTrue()
    }

    private fun outgoing(protocol: MeshProtocol, id: String) = OutgoingMessage(
        id = id,
        protocol = protocol,
        channelId = "0",
        text = "hello",
        timestamp = 1_700_000_000_000L,
    )
}
