package com.unifiedmesh.core.radio

import com.google.common.truth.Truth.assertThat
import com.unifiedmesh.core.bridge.BridgeEngine
import com.unifiedmesh.core.bridge.InMemoryBridgeSeenCache
import com.unifiedmesh.core.model.BridgeConfig
import com.unifiedmesh.core.model.BridgeRule
import com.unifiedmesh.core.model.Clock
import com.unifiedmesh.core.model.DeliveryState
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.SendResult
import com.unifiedmesh.core.model.SendTarget
import com.unifiedmesh.core.model.UnifiedMessage
import com.unifiedmesh.protocol.api.FakeIncoming
import com.unifiedmesh.protocol.api.FakeMeshCoreAdapter
import com.unifiedmesh.protocol.api.FakeMeshRadioAdapter
import com.unifiedmesh.protocol.api.FakeMeshtasticAdapter
import com.unifiedmesh.core.model.RadioDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Records everything the coordinator would have persisted. */
private class RecordingStore : MessageStore {
    val incoming = mutableListOf<UnifiedMessage>()
    val outgoing = mutableListOf<UnifiedMessage>()
    val deliveryUpdates = mutableListOf<Triple<String, DeliveryState, String?>>()

    override suspend fun saveIncoming(message: UnifiedMessage) {
        incoming += message
    }

    override suspend fun saveOutgoing(message: UnifiedMessage) {
        outgoing += message
    }

    override suspend fun updateDeliveryState(messageId: String, state: DeliveryState, detail: String?) {
        deliveryUpdates += Triple(messageId, state, detail)
    }
}

/**
 * The behaviour the whole app exists for: two radios, live at the same time,
 * with no coupling between them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioCoordinatorTest {

    private val mtDevice = RadioDevice(address = "AA:01", name = "T-Deck")
    private val mcDevice = RadioDevice(address = "AA:02", name = "T1000-E")
    private val clock = Clock { 1_700_000_000_000L }

    private val mtToMc = BridgeRule(
        id = "mt-to-mc",
        enabled = true,
        fromProtocol = MeshProtocol.MESHTASTIC,
        fromChannelId = "0",
        toProtocol = MeshProtocol.MESHCORE,
        toChannelId = "0",
    )
    private val mcToMt = BridgeRule(
        id = "mc-to-mt",
        enabled = true,
        fromProtocol = MeshProtocol.MESHCORE,
        fromChannelId = "0",
        toProtocol = MeshProtocol.MESHTASTIC,
        toChannelId = "0",
    )

    private class Harness(
        val mtAdapter: FakeMeshRadioAdapter,
        val mcAdapter: FakeMeshRadioAdapter,
        val coordinator: RadioCoordinator,
        val store: RecordingStore,
        val bridgeConfig: MutableStateFlow<BridgeConfig>,
    )

    private fun TestScope.harness(bridge: BridgeConfig = BridgeConfig()): Harness {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val mtAdapter = FakeMeshtasticAdapter(parentDispatcherOverride = dispatcher)
        val mcAdapter = FakeMeshCoreAdapter(parentDispatcherOverride = dispatcher)
        val store = RecordingStore()
        val config = MutableStateFlow(bridge)

        val coordinator = RadioCoordinator(
            meshtastic = RadioSession(
                protocol = MeshProtocol.MESHTASTIC,
                adapterFactory = { mtAdapter },
                clock = clock,
                dispatcher = dispatcher,
            ),
            meshCore = RadioSession(
                protocol = MeshProtocol.MESHCORE,
                adapterFactory = { mcAdapter },
                clock = clock,
                dispatcher = dispatcher,
            ),
            store = store,
            bridgeEngine = BridgeEngine(clock, InMemoryBridgeSeenCache()),
            bridgeConfig = config,
            clock = clock,
            dispatcher = dispatcher,
        )
        coordinator.start()
        return Harness(mtAdapter, mcAdapter, coordinator, store, config)
    }

    private fun withHarness(
        bridge: BridgeConfig = BridgeConfig(),
        block: suspend TestScope.(Harness) -> Unit,
    ) = runTest {
        val h = harness(bridge)
        try {
            block(h)
        } finally {
            h.coordinator.shutdown()
            h.mtAdapter.shutdown()
            h.mcAdapter.shutdown()
        }
    }

    // --- Both radios at once -----------------------------------------------

    @Test
    fun `both radios connect and report state independently`() = withHarness { h ->
        h.coordinator.meshtastic.connect(mtDevice)
        h.coordinator.meshCore.connect(mcDevice)
        advanceUntilIdle()

        val states = h.coordinator.connectionStates.value
        assertThat(states[MeshProtocol.MESHTASTIC]!!.isConnected).isTrue()
        assertThat(states[MeshProtocol.MESHCORE]!!.isConnected).isTrue()
    }

    @Test
    fun `both radios receive at the same time`() = withHarness { h ->
        h.coordinator.meshtastic.connect(mtDevice)
        h.coordinator.meshCore.connect(mcDevice)
        advanceUntilIdle()

        h.mtAdapter.emitIncoming(FakeIncoming("!a1b2c3d4", "Bear", "We are heading back to camp."))
        h.mcAdapter.emitIncoming(FakeIncoming("1a2b3c4d5e6f", "Ridge Base", "Copy. See you soon."))
        advanceUntilIdle()

        assertThat(h.store.incoming.map { it.protocol })
            .containsExactly(MeshProtocol.MESHTASTIC, MeshProtocol.MESHCORE)
        assertThat(h.store.incoming.map { it.text })
            .containsExactly("We are heading back to camp.", "Copy. See you soon.")
    }

    @Test
    fun `disconnecting Meshtastic leaves MeshCore receiving`() = withHarness { h ->
        h.coordinator.meshtastic.connect(mtDevice)
        h.coordinator.meshCore.connect(mcDevice)
        advanceUntilIdle()

        h.coordinator.meshtastic.disconnect()
        advanceUntilIdle()

        h.mcAdapter.emitIncoming(FakeIncoming("1a2b3c4d5e6f", "Ridge Base", "Still here."))
        advanceUntilIdle()

        assertThat(h.coordinator.meshCore.state.value.isConnected).isTrue()
        assertThat(h.store.incoming.map { it.text }).containsExactly("Still here.")
    }

    @Test
    fun `disconnecting MeshCore leaves Meshtastic receiving`() = withHarness { h ->
        h.coordinator.meshtastic.connect(mtDevice)
        h.coordinator.meshCore.connect(mcDevice)
        advanceUntilIdle()

        h.coordinator.meshCore.disconnect()
        advanceUntilIdle()

        h.mtAdapter.emitIncoming(FakeIncoming("!a1b2c3d4", "Bear", "Still here too."))
        advanceUntilIdle()

        assertThat(h.coordinator.meshtastic.state.value.isConnected).isTrue()
        assertThat(h.store.incoming.map { it.text }).containsExactly("Still here too.")
    }

    @Test
    fun `a disconnected slot does not stop the other from sending`() = withHarness { h ->
        h.coordinator.meshtastic.connect(mtDevice)
        h.coordinator.meshCore.connect(mcDevice)
        advanceUntilIdle()
        h.coordinator.meshtastic.disconnect()
        advanceUntilIdle()

        val attempts = h.coordinator.send(
            target = SendTarget.BOTH,
            text = "anyone on?",
            destinations = mapOf(
                MeshProtocol.MESHTASTIC to SendDestination(channelId = "0"),
                MeshProtocol.MESHCORE to SendDestination(channelId = "0"),
            ),
        )
        advanceUntilIdle()

        val byProtocol = attempts.associateBy { it.protocol }
        assertThat(byProtocol[MeshProtocol.MESHTASTIC]!!.result).isInstanceOf(SendResult.Failed::class.java)
        assertThat(byProtocol[MeshProtocol.MESHCORE]!!.result).isInstanceOf(SendResult.Accepted::class.java)
    }

    // --- Send Via Both ------------------------------------------------------

    @Test
    fun `Send Via Both transmits independently on each radio`() = withHarness { h ->
        h.coordinator.meshtastic.connect(mtDevice)
        h.coordinator.meshCore.connect(mcDevice)
        advanceUntilIdle()

        val attempts = h.coordinator.send(
            target = SendTarget.BOTH,
            text = "moving out",
            destinations = mapOf(
                MeshProtocol.MESHTASTIC to SendDestination(channelId = "0"),
                MeshProtocol.MESHCORE to SendDestination(channelId = "0"),
            ),
        )
        advanceUntilIdle()

        assertThat(attempts).hasSize(2)
        assertThat(attempts.all { it.result is SendResult.Accepted }).isTrue()

        // Each radio got its own copy straight from the phone; neither relayed.
        assertThat(h.mtAdapter.sentMessages.map { it.text }).containsExactly("moving out")
        assertThat(h.mcAdapter.sentMessages.map { it.text }).containsExactly("moving out")
        // And the text went out unannotated: this is not a bridge.
        assertThat(h.mcAdapter.sentMessages.single().text).doesNotContain("[MT")
    }

    @Test
    fun `Send Via Both reports per-radio outcomes separately`() = withHarness { h ->
        h.coordinator.meshtastic.connect(mtDevice)
        h.coordinator.meshCore.connect(mcDevice)
        advanceUntilIdle()
        h.mtAdapter.failSends = true

        val attempts = h.coordinator.send(
            target = SendTarget.BOTH,
            text = "half of this works",
            destinations = mapOf(
                MeshProtocol.MESHTASTIC to SendDestination(channelId = "0"),
                MeshProtocol.MESHCORE to SendDestination(channelId = "0"),
            ),
        )
        advanceUntilIdle()

        val byProtocol = attempts.associateBy { it.protocol }
        assertThat(byProtocol[MeshProtocol.MESHTASTIC]!!.result).isInstanceOf(SendResult.Failed::class.java)
        assertThat(byProtocol[MeshProtocol.MESHCORE]!!.result).isInstanceOf(SendResult.Accepted::class.java)

        // Both stored records exist, with the failure recorded against the right one.
        assertThat(h.store.outgoing).hasSize(2)
        val failedId = byProtocol[MeshProtocol.MESHTASTIC]!!.messageId
        assertThat(h.store.deliveryUpdates.first { it.first == failedId }.second)
            .isEqualTo(DeliveryState.FAILED)
    }

    @Test
    fun `sending to one protocol only touches that radio`() = withHarness { h ->
        h.coordinator.meshtastic.connect(mtDevice)
        h.coordinator.meshCore.connect(mcDevice)
        advanceUntilIdle()

        h.coordinator.send(
            target = SendTarget.MESHCORE,
            text = "MeshCore only",
            destinations = mapOf(
                MeshProtocol.MESHTASTIC to SendDestination(channelId = "0"),
                MeshProtocol.MESHCORE to SendDestination(channelId = "0"),
            ),
        )
        advanceUntilIdle()

        assertThat(h.mtAdapter.sentMessages).isEmpty()
        assertThat(h.mcAdapter.sentMessages).hasSize(1)
    }

    // --- Bridge integration -------------------------------------------------

    @Test
    fun `with the bridge off nothing crosses`() = withHarness { h ->
        h.coordinator.meshtastic.connect(mtDevice)
        h.coordinator.meshCore.connect(mcDevice)
        advanceUntilIdle()

        h.mtAdapter.emitIncoming(FakeIncoming("!a1b2c3d4", "Bear", "Need help at camp"))
        advanceUntilIdle()

        assertThat(h.mcAdapter.sentMessages).isEmpty()
    }

    @Test
    fun `with the bridge on a Meshtastic message is relayed onto MeshCore`() = withHarness(
        bridge = BridgeConfig(
            masterEnabled = true,
            meshtasticToMeshCore = true,
            meshCoreToMeshtastic = true,
            rules = listOf(mtToMc, mcToMt),
        ),
    ) { h ->
        h.coordinator.meshtastic.connect(mtDevice)
        h.coordinator.meshCore.connect(mcDevice)
        advanceUntilIdle()

        h.mtAdapter.emitIncoming(FakeIncoming("!a1b2c3d4", "Bear", "Need help at camp"))
        advanceUntilIdle()

        assertThat(h.mcAdapter.sentMessages.map { it.text })
            .containsExactly("[MT: Bear] Need help at camp")
        // The relay is stored as an outbound message so it appears in the thread.
        val relayed = h.store.outgoing.single()
        assertThat(relayed.bridged).isTrue()
        assertThat(relayed.originalProtocol).isEqualTo(MeshProtocol.MESHTASTIC)
        assertThat(relayed.hopCount).isEqualTo(1)
    }

    @Test
    fun `a relayed message coming back does not bounce again`() = withHarness(
        bridge = BridgeConfig(
            masterEnabled = true,
            meshtasticToMeshCore = true,
            meshCoreToMeshtastic = true,
            rules = listOf(mtToMc, mcToMt),
        ),
    ) { h ->
        h.coordinator.meshtastic.connect(mtDevice)
        h.coordinator.meshCore.connect(mcDevice)
        advanceUntilIdle()

        h.mtAdapter.emitIncoming(FakeIncoming("!a1b2c3d4", "Bear", "Need help at camp"))
        advanceUntilIdle()
        val relayText = h.mcAdapter.sentMessages.single().text

        // Another operator's bridge puts our own relay back on Meshtastic.
        h.mtAdapter.emitIncoming(FakeIncoming("!deadbeef", "Other Bridge", relayText))
        advanceUntilIdle()

        // Still exactly one transmission for that sentence.
        assertThat(h.mcAdapter.sentMessages).hasSize(1)
        assertThat(h.mtAdapter.sentMessages).isEmpty()
    }

    @Test
    fun `the bridge does not relay when the destination radio is disconnected`() = withHarness(
        bridge = BridgeConfig(
            masterEnabled = true,
            meshtasticToMeshCore = true,
            rules = listOf(mtToMc),
        ),
    ) { h ->
        h.coordinator.meshtastic.connect(mtDevice)
        advanceUntilIdle()

        h.mtAdapter.emitIncoming(FakeIncoming("!a1b2c3d4", "Bear", "Need help at camp"))
        advanceUntilIdle()

        assertThat(h.mcAdapter.sentMessages).isEmpty()
        // And the source message is still stored and shown.
        assertThat(h.store.incoming).hasSize(1)
    }

    @Test
    fun `enabling only one direction relays only that way`() = withHarness(
        bridge = BridgeConfig(
            masterEnabled = true,
            meshtasticToMeshCore = false,
            meshCoreToMeshtastic = true,
            rules = listOf(mtToMc, mcToMt),
        ),
    ) { h ->
        h.coordinator.meshtastic.connect(mtDevice)
        h.coordinator.meshCore.connect(mcDevice)
        advanceUntilIdle()

        h.mtAdapter.emitIncoming(FakeIncoming("!a1b2c3d4", "Bear", "should not cross"))
        h.mcAdapter.emitIncoming(FakeIncoming("1a2b3c4d5e6f", "Elliott", "should cross"))
        advanceUntilIdle()

        assertThat(h.mcAdapter.sentMessages).isEmpty()
        assertThat(h.mtAdapter.sentMessages.map { it.text }).containsExactly("[MC: Elliott] should cross")
    }

    @Test
    fun `delivery updates are recorded against the right message`() = withHarness { h ->
        h.coordinator.meshCore.connect(mcDevice)
        advanceUntilIdle()

        val attempts = h.coordinator.send(
            target = SendTarget.MESHCORE,
            text = "ack me",
            destinations = mapOf(MeshProtocol.MESHCORE to SendDestination(channelId = "0")),
        )
        advanceUntilIdle()

        val id = attempts.single().messageId
        assertThat(h.store.deliveryUpdates.filter { it.first == id }.map { it.second })
            .containsAtLeast(DeliveryState.SENT, DeliveryState.DELIVERED)
            .inOrder()
    }
}
