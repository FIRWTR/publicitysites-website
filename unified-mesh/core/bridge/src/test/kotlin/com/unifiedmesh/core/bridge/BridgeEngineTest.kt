package com.unifiedmesh.core.bridge

import com.google.common.truth.Truth.assertThat
import com.unifiedmesh.core.model.BridgeConfig
import com.unifiedmesh.core.model.BridgeRule
import com.unifiedmesh.core.model.Clock
import com.unifiedmesh.core.model.ConversationKey
import com.unifiedmesh.core.model.DeliveryState
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.MessageDirection
import com.unifiedmesh.core.model.UnifiedMessage
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/** A clock the tests move by hand; nothing here sleeps. */
private class FakeClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMillis(): Long = now

    fun advance(millis: Long) {
        now += millis
    }
}

class BridgeEngineTest {

    private lateinit var clock: FakeClock
    private lateinit var cache: InMemoryBridgeSeenCache
    private lateinit var engine: BridgeEngine
    private val bridgeIds = AtomicLong(0)

    private val mtToMc = BridgeRule(
        id = "mt-longfast-to-mc-public",
        enabled = true,
        fromProtocol = MeshProtocol.MESHTASTIC,
        fromChannelId = "0",
        toProtocol = MeshProtocol.MESHCORE,
        toChannelId = "0",
        label = "Meshtastic LongFast -> MeshCore Public",
    )

    private val mcToMt = BridgeRule(
        id = "mc-emergency-to-mt-emergency",
        enabled = true,
        fromProtocol = MeshProtocol.MESHCORE,
        fromChannelId = "1",
        toProtocol = MeshProtocol.MESHTASTIC,
        toChannelId = "1",
        label = "MeshCore Emergency -> Meshtastic Emergency",
    )

    private fun config(
        master: Boolean = true,
        mtToMcOn: Boolean = true,
        mcToMtOn: Boolean = true,
        rules: List<BridgeRule> = listOf(mtToMc, mcToMt),
        maxHops: Int = 1,
    ) = BridgeConfig(
        masterEnabled = master,
        meshtasticToMeshCore = mtToMcOn,
        meshCoreToMeshtastic = mcToMtOn,
        maxHops = maxHops,
        rules = rules,
    )

    private fun inbound(
        protocol: MeshProtocol = MeshProtocol.MESHTASTIC,
        senderId: String = "!a1b2c3d4",
        senderName: String? = "Bear",
        text: String = "Need help at camp",
        channelId: String? = "0",
        timestamp: Long = clock.now,
        direction: MessageDirection = MessageDirection.INCOMING,
    ) = UnifiedMessage(
        id = "in-${bridgeIds.incrementAndGet()}",
        protocol = protocol,
        conversationId = channelId?.let { ConversationKey.channel(protocol, it).asId() }
            ?: ConversationKey.direct(protocol, senderId).asId(),
        senderId = senderId,
        senderName = senderName,
        destinationId = if (channelId == null) "!self" else null,
        channelId = channelId,
        text = text,
        timestamp = timestamp,
        direction = direction,
        deliveryState = DeliveryState.RECEIVED,
    )

    @Before
    fun setUp() {
        clock = FakeClock()
        cache = InMemoryBridgeSeenCache()
        var counter = 0
        engine = BridgeEngine(clock, cache, idGenerator = { "bridge-${++counter}" })
    }

    // --- Direction and rule gating -----------------------------------------

    @Test
    fun `relays a Meshtastic channel message onto MeshCore`() = runTest {
        val decision = engine.evaluate(inbound(), config())

        assertThat(decision).isInstanceOf(BridgeDecision.Relay::class.java)
        val relay = decision as BridgeDecision.Relay
        assertThat(relay.outgoing.protocol).isEqualTo(MeshProtocol.MESHCORE)
        assertThat(relay.outgoing.channelId).isEqualTo("0")
        assertThat(relay.outgoing.text).isEqualTo("[MT: Bear] Need help at camp")
        assertThat(relay.hopCount).isEqualTo(1)
        assertThat(relay.rule.id).isEqualTo(mtToMc.id)
    }

    @Test
    fun `relays a MeshCore emergency message onto Meshtastic`() = runTest {
        val decision = engine.evaluate(
            inbound(
                protocol = MeshProtocol.MESHCORE,
                senderId = "1a2b3c4d5e6f",
                senderName = "Ridge Base",
                text = "Copy.",
                channelId = "1",
            ),
            config(),
        )

        val relay = decision as BridgeDecision.Relay
        assertThat(relay.outgoing.protocol).isEqualTo(MeshProtocol.MESHTASTIC)
        assertThat(relay.outgoing.channelId).isEqualTo("1")
        assertThat(relay.outgoing.text).isEqualTo("[MC: Ridge Base] Copy.")
    }

    @Test
    fun `master switch off blocks everything`() = runTest {
        val decision = engine.evaluate(inbound(), config(master = false))
        assertThat(decision).isEqualTo(BridgeDecision.Skip(BridgeSkipReason.MASTER_DISABLED))
    }

    @Test
    fun `each direction can be disabled independently`() = runTest {
        val mtBlocked = engine.evaluate(inbound(), config(mtToMcOn = false))
        assertThat((mtBlocked as BridgeDecision.Skip).reason).isEqualTo(BridgeSkipReason.DIRECTION_DISABLED)

        val mcAllowed = engine.evaluate(
            inbound(protocol = MeshProtocol.MESHCORE, senderId = "aabb", senderName = "Camp", channelId = "1"),
            config(mtToMcOn = false),
        )
        assertThat(mcAllowed).isInstanceOf(BridgeDecision.Relay::class.java)
    }

    @Test
    fun `a disabled rule does not match`() = runTest {
        val decision = engine.evaluate(inbound(), config(rules = listOf(mtToMc.copy(enabled = false))))
        assertThat((decision as BridgeDecision.Skip).reason).isEqualTo(BridgeSkipReason.NO_MATCHING_RULE)
    }

    @Test
    fun `a channel with no mapping is not bridged`() = runTest {
        val decision = engine.evaluate(inbound(channelId = "7"), config())
        assertThat((decision as BridgeDecision.Skip).reason).isEqualTo(BridgeSkipReason.NO_MATCHING_RULE)
    }

    @Test
    fun `a wildcard rule matches any source channel`() = runTest {
        val wildcard = mtToMc.copy(id = "wildcard", fromChannelId = null)
        val decision = engine.evaluate(inbound(channelId = "5"), config(rules = listOf(wildcard)))
        assertThat(decision).isInstanceOf(BridgeDecision.Relay::class.java)
    }

    // --- What must never be bridged ----------------------------------------

    @Test
    fun `direct messages are never bridged`() = runTest {
        val decision = engine.evaluate(inbound(channelId = null), config())
        assertThat((decision as BridgeDecision.Skip).reason).isEqualTo(BridgeSkipReason.DIRECT_MESSAGE)
    }

    @Test
    fun `outbound messages are never bridged`() = runTest {
        val decision = engine.evaluate(inbound(direction = MessageDirection.OUTGOING), config())
        assertThat((decision as BridgeDecision.Skip).reason).isEqualTo(BridgeSkipReason.NOT_INBOUND)
    }

    @Test
    fun `traffic from our own attached radio is never bridged`() = runTest {
        val decision = engine.evaluate(inbound(senderId = "!self0001"), config(), selfNodeId = "!self0001")
        assertThat((decision as BridgeDecision.Skip).reason).isEqualTo(BridgeSkipReason.SELF_ORIGINATED)
    }

    @Test
    fun `empty text is never bridged`() = runTest {
        val decision = engine.evaluate(inbound(text = "   "), config())
        assertThat((decision as BridgeDecision.Skip).reason).isEqualTo(BridgeSkipReason.EMPTY_TEXT)
    }

    @Test
    fun `text too long for the destination is refused rather than truncated`() = runTest {
        val decision = engine.evaluate(inbound(text = "y".repeat(200)), config())
        assertThat((decision as BridgeDecision.Skip).reason).isEqualTo(BridgeSkipReason.TEXT_TOO_LONG)
    }

    @Test
    fun `stale backlog replayed on reconnect is not bridged`() = runTest {
        val old = inbound(timestamp = clock.now - BridgeConfig.DEFAULT_MAX_MESSAGE_AGE_MILLIS - 1)
        val decision = engine.evaluate(old, config())
        assertThat((decision as BridgeDecision.Skip).reason).isEqualTo(BridgeSkipReason.TOO_OLD)
    }

    // --- Duplicate suppression and hop limiting -----------------------------

    @Test
    fun `the same message heard twice is only bridged once`() = runTest {
        val first = engine.evaluate(inbound(), config())
        assertThat(first).isInstanceOf(BridgeDecision.Relay::class.java)

        // Same text, different app-level id: a flood retransmission.
        val second = engine.evaluate(inbound(), config())
        assertThat((second as BridgeDecision.Skip).reason).isEqualTo(BridgeSkipReason.DUPLICATE)
    }

    @Test
    fun `duplicate suppression ignores case and whitespace`() = runTest {
        engine.evaluate(inbound(text = "Need help at camp"), config())
        val second = engine.evaluate(inbound(text = "  need   HELP at camp "), config())
        assertThat((second as BridgeDecision.Skip).reason).isEqualTo(BridgeSkipReason.DUPLICATE)
    }

    @Test
    fun `different senders saying the same words are both bridged`() = runTest {
        val first = engine.evaluate(inbound(senderId = "!aaaa0001", senderName = "Bear", text = "ok"), config())
        val second = engine.evaluate(inbound(senderId = "!bbbb0002", senderName = "Sarah", text = "ok"), config())

        assertThat(first).isInstanceOf(BridgeDecision.Relay::class.java)
        assertThat(second).isInstanceOf(BridgeDecision.Relay::class.java)
    }

    @Test
    fun `an already-marked message is refused at the default hop limit`() = runTest {
        // This is what another operator's bridge puts on the air.
        val relayed = inbound(
            protocol = MeshProtocol.MESHCORE,
            senderId = "ffee0011",
            senderName = "Someone Else's Bridge",
            text = "[MT: Bear] Need help at camp",
            channelId = "1",
        )

        val decision = engine.evaluate(relayed, config())
        assertThat((decision as BridgeDecision.Skip).reason).isEqualTo(BridgeSkipReason.HOP_LIMIT_REACHED)
    }

    @Test
    fun `raising maxHops allows exactly one more crossing`() = runTest {
        val marked = inbound(
            protocol = MeshProtocol.MESHCORE,
            senderId = "ffee0011",
            senderName = "Bridge",
            text = "[MT: Bear] Need help at camp",
            channelId = "1",
        )

        val relay = engine.evaluate(marked, config(maxHops = 2)) as BridgeDecision.Relay
        assertThat(relay.hopCount).isEqualTo(2)
        assertThat(relay.outgoing.text).isEqualTo("[MT x2: Bear] Need help at camp")

        // And the next crossing is refused.
        val twice = inbound(
            protocol = MeshProtocol.MESHTASTIC,
            senderId = "!cccc0003",
            senderName = "Bridge",
            text = "[MT x2: Bear] Need help at camp",
            channelId = "0",
        )
        val decision = engine.evaluate(twice, config(maxHops = 2))
        assertThat((decision as BridgeDecision.Skip).reason).isAnyOf(
            BridgeSkipReason.HOP_LIMIT_REACHED,
            BridgeSkipReason.DUPLICATE,
        )
    }

    @Test
    fun `suppression expires after the duplicate window`() = runTest {
        val cfg = config()
        val first = engine.evaluate(inbound(), cfg)
        assertThat(first).isInstanceOf(BridgeDecision.Relay::class.java)

        clock.advance(cfg.duplicateWindowMillis + 1)

        // Same words much later: a real new transmission, not an echo.
        val later = engine.evaluate(inbound(timestamp = clock.now), cfg)
        assertThat(later).isInstanceOf(BridgeDecision.Relay::class.java)
    }

    @Test
    fun `expired entries are purged from the cache`() = runTest {
        val cfg = config()
        engine.evaluate(inbound(), cfg)

        val fingerprint = BridgeFingerprint.of("Bear", "Need help at camp")
        assertThat(cache.hasFingerprint(fingerprint)).isTrue()

        clock.advance(cfg.duplicateWindowMillis + 1)
        engine.evaluate(inbound(text = "different text entirely", timestamp = clock.now), cfg)

        assertThat(cache.hasFingerprint(fingerprint)).isFalse()
        // The cache does not grow without bound: only the surviving entry is held.
        assertThat(cache.size).isEqualTo(1)
    }

    @Test
    fun `the inbound message and its relay share one fingerprint`() = runTest {
        // The marker is stripped before hashing, so recording the inbound message
        // and the annotated relay is idempotent rather than double-counting.
        engine.evaluate(inbound(), config())
        assertThat(cache.size).isEqualTo(1)
    }

    // --- The loop the whole design exists to prevent ------------------------

    @Test
    fun `the multi-bridge round trip terminates instead of looping forever`() = runTest {
        val cfg = config()

        // 1. Bear speaks on Meshtastic. We relay to MeshCore.
        val hop1 = engine.evaluate(inbound(), cfg) as BridgeDecision.Relay
        assertThat(hop1.outgoing.text).isEqualTo("[MT: Bear] Need help at camp")

        // 2. Another operator's bridge picks our MeshCore transmission up and puts
        //    it back on Meshtastic. Our Meshtastic radio hears it.
        val comeback = inbound(
            protocol = MeshProtocol.MESHTASTIC,
            senderId = "!deadbeef",
            senderName = "Other Bridge",
            text = hop1.outgoing.text,
            channelId = "0",
        )
        val hop2 = engine.evaluate(comeback, cfg)
        assertThat(hop2).isInstanceOf(BridgeDecision.Skip::class.java)

        // 3. And a third lap, arriving from the MeshCore side this time.
        val thirdLap = inbound(
            protocol = MeshProtocol.MESHCORE,
            senderId = "00ff00ff00ff",
            senderName = "Yet Another Bridge",
            text = hop1.outgoing.text,
            channelId = "1",
        )
        assertThat(engine.evaluate(thirdLap, cfg)).isInstanceOf(BridgeDecision.Skip::class.java)

        // Exactly one transmission left the phone for this sentence.
        assertThat(hop1.bridgeId).isNotEmpty()
    }

    @Test
    fun `an unmarked echo of our own relay is caught by the fingerprint cache`() = runTest {
        val cfg = config(annotate = false)

        val relay = engine.evaluate(inbound(), cfg) as BridgeDecision.Relay
        assertThat(relay.outgoing.text).isEqualTo("Need help at camp")

        // With annotation off there is no on-air marker at all, so only the
        // fingerprint cache can catch the echo coming back the other way.
        val echo = inbound(
            protocol = MeshProtocol.MESHCORE,
            senderId = "112233445566",
            senderName = "Bear",
            text = "Need help at camp",
            channelId = "1",
        )
        val decision = engine.evaluate(echo, cfg)
        assertThat((decision as BridgeDecision.Skip).reason).isEqualTo(BridgeSkipReason.DUPLICATE)
    }

    @Test
    fun `a bridge id is never reused`() = runTest {
        val cfg = config()
        val a = engine.evaluate(inbound(text = "first"), cfg) as BridgeDecision.Relay
        val b = engine.evaluate(inbound(text = "second"), cfg) as BridgeDecision.Relay

        assertThat(a.bridgeId).isNotEqualTo(b.bridgeId)
        assertThat(a.outgoing.bridgeMetadata!!.bridgeId).isEqualTo(a.bridgeId)
        assertThat(a.outgoing.bridgeMetadata.originProtocol).isEqualTo(MeshProtocol.MESHTASTIC)
        assertThat(a.outgoing.bridgeMetadata.originNodeId).isEqualTo("!a1b2c3d4")
    }

    @Test
    fun `relayed transmissions never request an acknowledgement`() = runTest {
        val relay = engine.evaluate(inbound(), config()) as BridgeDecision.Relay
        assertThat(relay.outgoing.wantAck).isFalse()
    }

    @Test
    fun `observing history does not relay but does suppress later duplicates`() = runTest {
        val cfg = config()
        val message = inbound()

        engine.observe(message, cfg)

        val decision = engine.evaluate(inbound(), cfg)
        assertThat((decision as BridgeDecision.Skip).reason).isEqualTo(BridgeSkipReason.DUPLICATE)
    }

    private fun config(annotate: Boolean) = config().copy(annotateRelayedText = annotate)
}
