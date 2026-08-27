package com.unifiedmesh.core.radio

import com.google.common.truth.Truth.assertThat
import com.unifiedmesh.core.model.Clock
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.RadioConnectionState
import com.unifiedmesh.core.model.RadioDevice
import com.unifiedmesh.protocol.api.FakeMeshtasticAdapter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Reconnect behaviour for one radio slot. */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioSessionTest {

    private val device = RadioDevice(address = "AA:01", name = "T-Deck")
    private val clock = Clock { 1_700_000_000_000L }

    private fun TestScope.session(
        adapter: FakeMeshtasticAdapter,
        autoReconnect: () -> Boolean = { true },
    ) = RadioSession(
        protocol = MeshProtocol.MESHTASTIC,
        adapterFactory = { adapter },
        clock = clock,
        dispatcher = StandardTestDispatcher(testScheduler),
        autoReconnectEnabled = autoReconnect,
    )

    private fun withSession(
        autoReconnect: () -> Boolean = { true },
        block: suspend TestScope.(FakeMeshtasticAdapter, RadioSession) -> Unit,
    ) = runTest {
        val adapter = FakeMeshtasticAdapter(parentDispatcherOverride = StandardTestDispatcher(testScheduler))
        val session = session(adapter, autoReconnect)
        try {
            block(adapter, session)
        } finally {
            session.shutdown()
            adapter.shutdown()
        }
    }

    @Test
    fun `a dropped link is retried when automatic reconnect is on`() = withSession { adapter, session ->
        session.connect(device)
        runCurrent()
        assertThat(session.state.value.isConnected).isTrue()

        // The radio goes away on its own — not an operator disconnect.
        adapter.simulateLinkLoss("out of range")
        runCurrent()

        assertThat(session.state.value).isInstanceOf(RadioConnectionState.Reconnecting::class.java)
    }

    @Test
    fun `a dropped link is not chased when automatic reconnect is off`() = withSession(
        autoReconnect = { false },
    ) { adapter, session ->
        session.connect(device)
        runCurrent()
        assertThat(session.state.value.isConnected).isTrue()

        adapter.simulateLinkLoss("out of range")
        runCurrent()

        assertThat(session.state.value).isEqualTo(RadioConnectionState.Disconnected)
    }

    @Test
    fun `the setting is read at drop time, not captured at construction`() = runTest {
        // Starts on, and the operator turns it off while the radio is still up.
        val setting = MutableSetting(enabled = true)
        val adapter = FakeMeshtasticAdapter(parentDispatcherOverride = StandardTestDispatcher(testScheduler))
        val session = session(adapter, setting::value)

        try {
            session.connect(device)
            runCurrent()

            setting.enabled = false
            adapter.simulateLinkLoss("out of range")
            runCurrent()

            assertThat(session.state.value).isEqualTo(RadioConnectionState.Disconnected)
        } finally {
            session.shutdown()
            adapter.shutdown()
        }
    }

    private class MutableSetting(var enabled: Boolean) {
        fun value(): Boolean = enabled
    }

    @Test
    fun `an operator disconnect is never retried`() = withSession { adapter, session ->
        session.connect(device)
        runCurrent()

        session.disconnect()
        runCurrent()
        advanceTimeBy(120_000)
        runCurrent()

        assertThat(session.state.value).isEqualTo(RadioConnectionState.Disconnected)
    }

    @Test
    fun `backoff grows and is capped`() {
        val policy = ReconnectPolicy.exponential(
            baseDelayMillis = 1_000,
            maxDelayMillis = 60_000,
            jitterFraction = 0.0,
        )

        assertThat(policy.delayMillisFor(1)).isEqualTo(1_000)
        assertThat(policy.delayMillisFor(2)).isEqualTo(2_000)
        assertThat(policy.delayMillisFor(5)).isEqualTo(16_000)
        // A radio switched back on after an hour still gets picked up promptly.
        assertThat(policy.delayMillisFor(20)).isEqualTo(60_000)
        assertThat(policy.delayMillisFor(200)).isEqualTo(60_000)
    }

    @Test
    fun `jitter keeps the two radios from retrying in lockstep`() {
        val policy = ReconnectPolicy.exponential(baseDelayMillis = 10_000, jitterFraction = 0.25)
        val samples = (1..50).map { policy.delayMillisFor(1) }.toSet()

        assertThat(samples.size).isGreaterThan(1)
        samples.forEach { assertThat(it).isIn(8_500L..11_500L) }
    }
}
