package com.unifiedmesh.core.bridge

import com.unifiedmesh.core.model.BridgeConfig
import com.unifiedmesh.core.model.BridgeMetadata
import com.unifiedmesh.core.model.BridgeRule
import com.unifiedmesh.core.model.Clock
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.MessageDirection
import com.unifiedmesh.core.model.OutgoingMessage
import com.unifiedmesh.core.model.UnifiedMessage
import java.util.UUID

/** Why the bridge declined to relay a message. Surfaced verbatim on the Diagnostics screen. */
enum class BridgeSkipReason {
    /** Master switch off. */
    MASTER_DISABLED,

    /** This direction (MT to MC, or MC to MT) is switched off. */
    DIRECTION_DISABLED,

    /** Outbound messages are never bridged — only traffic heard from the mesh. */
    NOT_INBOUND,

    /** The message came from the radio this phone is attached to. */
    SELF_ORIGINATED,

    /** Direct messages are not bridged in v1: there is no cross-protocol identity mapping. */
    DIRECT_MESSAGE,

    /** No enabled rule maps this source channel to a destination. */
    NO_MATCHING_RULE,

    /** Nothing to relay. */
    EMPTY_TEXT,

    /** The text is longer than the destination network can carry, even after trimming. */
    TEXT_TOO_LONG,

    /** Already crossed the configured maximum number of bridges. */
    HOP_LIMIT_REACHED,

    /** Content fingerprint is in the recently-seen cache. */
    DUPLICATE,

    /** This exact bridge transaction has already been performed. */
    ALREADY_BRIDGED,

    /** Older than [BridgeConfig.maxMessageAgeMillis] — likely a replayed backlog. */
    TOO_OLD,
}

/** The engine's verdict for one inbound message. */
sealed interface BridgeDecision {

    /** Relay this. [outgoing] is ready to hand to the destination adapter. */
    data class Relay(
        val outgoing: OutgoingMessage,
        val rule: BridgeRule,
        val bridgeId: String,
        val hopCount: Int,
    ) : BridgeDecision

    data class Skip(val reason: BridgeSkipReason, val detail: String? = null) : BridgeDecision
}

/**
 * Decides whether an inbound message crosses to the other network, and builds
 * the relayed transmission when it does.
 *
 * ### The loop the design has to survive
 *
 *     Meshtastic -> phone bridge -> MeshCore -> another operator's bridge
 *                -> Meshtastic -> this phone -> MeshCore -> ...
 *
 * Six independent checks stop that, and a message must pass all of them:
 *
 * 1. **Direction gates.** Master switch, per-direction switch, per-rule switch.
 * 2. **Inbound only.** Anything this phone transmitted — including its own
 *    relays — is refused outright, so a relay can never feed itself.
 * 3. **Self-origination.** Traffic whose sender is the attached radio is refused.
 * 4. **On-air hop counter.** [BridgeTextCodec] carries a hop count in the marker,
 *    so a *different* phone's bridge output is recognised as already-bridged.
 *    With the default `maxHops = 1`, any marked message is dropped.
 * 5. **Content fingerprint cache.** Every message the engine sees, and every
 *    relay it performs, is fingerprinted and remembered for
 *    [BridgeConfig.duplicateWindowMillis]. A message that comes back around by
 *    any path at all matches its own fingerprint and is dropped — this is the
 *    check that catches loops the marker cannot see.
 * 6. **Age limit.** A radio that dumps a stored backlog on reconnect cannot
 *    trigger a burst of stale relays.
 *
 * Only text ever reaches this class: [com.unifiedmesh.protocol.api.MeshRadioAdapter.incomingMessages]
 * is text-only by contract, so telemetry, position, routing, ACK and admin
 * traffic are structurally incapable of being bridged.
 *
 * The engine is stateless apart from [seenCache]; it is safe to call
 * [evaluate] concurrently from both radio sessions.
 */
class BridgeEngine(
    private val clock: Clock,
    private val seenCache: BridgeSeenCache,
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {

    /**
     * Records an inbound message as seen without relaying it.
     *
     * Callers handling live radio traffic should call [evaluate] and nothing
     * else — it records skipped messages itself. Calling [observe] first would
     * put the message's own fingerprint in the cache and make the following
     * [evaluate] return [BridgeSkipReason.DUPLICATE] for it.
     *
     * This entry point exists for backfilling the cache from stored history, so
     * that switching the bridge on mid-conversation does not immediately relay a
     * backlog the operator has already read.
     */
    suspend fun observe(message: UnifiedMessage, config: BridgeConfig) {
        val fingerprint = fingerprintOf(message)
        seenCache.record(
            BridgeSeenEntry(
                fingerprint = fingerprint,
                bridgeId = message.bridgeId,
                protocol = message.protocol,
                seenAtMillis = clock.nowMillis(),
            ),
        )
        seenCache.purgeOlderThan(clock.nowMillis() - config.duplicateWindowMillis)
    }

    /**
     * Decides what to do with [message].
     *
     * On [BridgeDecision.Relay] the engine has already recorded the relay in the
     * seen cache, so calling [evaluate] twice with the same message returns
     * [BridgeSkipReason.DUPLICATE] the second time.
     *
     * @param selfNodeId this phone's own node id on [message]'s network, when known.
     */
    suspend fun evaluate(
        message: UnifiedMessage,
        config: BridgeConfig,
        selfNodeId: String? = null,
    ): BridgeDecision {
        val now = clock.nowMillis()
        seenCache.purgeOlderThan(now - config.duplicateWindowMillis)

        // (2) Inbound only. Outbound traffic — ours, and our own relays — never bridges.
        // Returned directly rather than via skip(): recording our own transmissions
        // in the seen cache would suppress a genuine reply that happens to repeat them.
        if (message.direction != MessageDirection.INCOMING) {
            return BridgeDecision.Skip(BridgeSkipReason.NOT_INBOUND)
        }

        // (1a) Master switch.
        if (!config.masterEnabled) return skip(message, BridgeSkipReason.MASTER_DISABLED, config)

        // (1b) Direction switch.
        if (!config.directionEnabled(message.protocol)) {
            return skip(message, BridgeSkipReason.DIRECTION_DISABLED, config)
        }

        // (3) Never relay what our own attached radio originated.
        if (selfNodeId != null && message.senderId == selfNodeId) {
            return skip(message, BridgeSkipReason.SELF_ORIGINATED, config)
        }

        val body = message.text.trim()
        if (body.isEmpty()) return skip(message, BridgeSkipReason.EMPTY_TEXT, config)

        // (6) Age limit.
        if (now - message.timestamp > config.maxMessageAgeMillis) {
            return skip(message, BridgeSkipReason.TOO_OLD, config)
        }

        // v1 bridges channel traffic only. Direct messages would need a
        // cross-protocol identity mapping, which the app deliberately does not do.
        val sourceChannelId = message.channelId
            ?: return skip(message, BridgeSkipReason.DIRECT_MESSAGE, config)

        // (1c) Rule switch.
        val rule = config.rules.firstOrNull { it.matches(message.protocol, sourceChannelId) }
            ?: return skip(message, BridgeSkipReason.NO_MATCHING_RULE, config)

        // (4) On-air hop counter, plus any hop count we already know locally.
        val marker = BridgeTextCodec.decode(message.text)
        val priorHops = maxOf(marker?.hops ?: 0, message.hopCount)
        val nextHopCount = priorHops + 1
        if (nextHopCount > config.maxHops) {
            return skip(
                message,
                BridgeSkipReason.HOP_LIMIT_REACHED,
                config,
                detail = "priorHops=$priorHops maxHops=${config.maxHops}",
            )
        }

        val originProtocol = marker?.originProtocol ?: message.originalProtocol ?: message.protocol
        val originSender = marker?.originSender ?: message.senderName ?: message.senderId
        val originBody = marker?.body?.trim()?.ifEmpty { body } ?: body

        // (5) Duplicate suppression. Checked against the *origin* fingerprint so a
        // message recognises itself no matter which network it arrives on or how
        // many markers have been layered onto it.
        val fingerprint = BridgeFingerprint.of(originSender, originBody)
        if (seenCache.hasFingerprint(fingerprint)) {
            return skip(message, BridgeSkipReason.DUPLICATE, config, detail = "fp=$fingerprint")
        }
        message.bridgeId?.let { existing ->
            if (seenCache.hasBridgeId(existing)) {
                return skip(message, BridgeSkipReason.ALREADY_BRIDGED, config, detail = "bridgeId=$existing")
            }
        }

        val relayText = if (config.annotateRelayedText) {
            BridgeTextCodec.encode(originProtocol, originSender, originBody, nextHopCount)
        } else {
            originBody
        }
        val limit = maxTextLengthFor(rule.toProtocol)
        if (relayText.length > limit) {
            // Trimming a distress message mid-word is worse than not relaying a
            // long one, so the engine refuses instead of silently truncating.
            return skip(
                message,
                BridgeSkipReason.TEXT_TOO_LONG,
                config,
                detail = "len=${relayText.length} limit=$limit",
            )
        }

        val bridgeId = idGenerator()
        val outgoing = OutgoingMessage(
            id = "bridge-$bridgeId",
            protocol = rule.toProtocol,
            channelId = rule.toChannelId,
            text = relayText,
            timestamp = now,
            // Bridged traffic never asks for an ACK: the operator on the far side
            // did not choose to receive it, and ACK storms cost airtime.
            wantAck = false,
            bridgeMetadata = BridgeMetadata(
                bridgeId = bridgeId,
                originProtocol = originProtocol,
                originNodeId = message.senderId,
                originSenderName = originSender,
                hopCount = nextHopCount,
            ),
        )

        // Record both halves before returning: the inbound message's fingerprint
        // and the relay's bridge id. Recording *before* the caller transmits means
        // a crash between decision and transmission can only ever under-relay.
        seenCache.record(
            BridgeSeenEntry(
                fingerprint = fingerprint,
                bridgeId = bridgeId,
                protocol = message.protocol,
                seenAtMillis = now,
            ),
        )
        seenCache.record(
            BridgeSeenEntry(
                fingerprint = BridgeFingerprint.of(originSender, relayText),
                bridgeId = bridgeId,
                protocol = rule.toProtocol,
                seenAtMillis = now,
            ),
        )

        return BridgeDecision.Relay(outgoing, rule, bridgeId, nextHopCount)
    }

    /**
     * Records a skipped message so that a later copy of it is also suppressed,
     * then returns the skip decision.
     */
    private suspend fun skip(
        message: UnifiedMessage,
        reason: BridgeSkipReason,
        config: BridgeConfig,
        detail: String? = null,
    ): BridgeDecision.Skip {
        // Do not poison the cache with our own outbound traffic; everything else
        // is worth remembering.
        if (reason != BridgeSkipReason.NOT_INBOUND) {
            observe(message, config)
        }
        return BridgeDecision.Skip(reason, detail)
    }

    private fun fingerprintOf(message: UnifiedMessage): String {
        val marker = BridgeTextCodec.decode(message.text)
        val originSender = marker?.originSender ?: message.senderName ?: message.senderId
        val body = marker?.body ?: message.text
        return BridgeFingerprint.of(originSender, body)
    }

    private companion object {
        /**
         * Conservative on-air text budgets.
         *
         * MeshCore's companion firmware documents a 133-character message limit.
         * Meshtastic's `Data.payload` fits within a 237-byte packet payload; 200
         * leaves headroom for the marker and for multi-byte UTF-8.
         */
        fun maxTextLengthFor(protocol: MeshProtocol): Int = when (protocol) {
            MeshProtocol.MESHTASTIC -> 200
            MeshProtocol.MESHCORE -> 133
        }
    }
}
