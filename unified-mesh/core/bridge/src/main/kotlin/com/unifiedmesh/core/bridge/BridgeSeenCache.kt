package com.unifiedmesh.core.bridge

import com.unifiedmesh.core.model.MeshProtocol
import java.security.MessageDigest
import java.util.Locale

/**
 * One record of something the bridge has already handled.
 *
 * @param fingerprint content fingerprint; see [BridgeFingerprint].
 * @param bridgeId the bridge transaction id, when this entry came from a relay
 *   this app performed. Null for entries recorded purely to suppress duplicates.
 * @param protocol the network the entry was observed on.
 * @param seenAtMillis when it was recorded.
 */
data class BridgeSeenEntry(
    val fingerprint: String,
    val bridgeId: String?,
    val protocol: MeshProtocol,
    val seenAtMillis: Long,
)

/**
 * The bridge's recently-seen store.
 *
 * Two implementations exist: an in-memory one (fast path, and what the tests
 * use) and a Room-backed one in `:core:database` so the suppression window
 * survives a process death mid-conversation.
 */
interface BridgeSeenCache {
    suspend fun hasFingerprint(fingerprint: String): Boolean

    suspend fun hasBridgeId(bridgeId: String): Boolean

    suspend fun record(entry: BridgeSeenEntry)

    /** Drops entries older than [cutoffMillis]. Called on every evaluation. */
    suspend fun purgeOlderThan(cutoffMillis: Long)
}

/**
 * Content fingerprinting.
 *
 * The fingerprint deliberately ignores the bridge marker, whitespace and case,
 * so that "Need help at camp", "[MT: Bear] Need help at camp" and
 * "[MT x2: Bear]  need help at camp" all collapse onto one value.
 *
 * ### Why the network is not part of the hash
 *
 * The fingerprint's job is to recognise *the same human sentence coming back
 * around*, and by definition an echo arrives on a different network from the one
 * it started on. Mixing the observing protocol into the hash would give the
 * outbound relay and its returning echo two different fingerprints and defeat the
 * check entirely — which is exactly what happens when the operator turns
 * [com.unifiedmesh.core.model.BridgeConfig.annotateRelayedText] off and there is
 * no on-air marker to fall back on.
 *
 * The sender name *is* included, so two different people saying "ok" are not
 * confused with each other. The residual collision — two people with the same
 * display name on the two networks saying the same words inside the duplicate
 * window — costs one un-relayed message, which is the safe direction to fail in.
 */
object BridgeFingerprint {

    fun of(originSender: String?, text: String): String {
        val body = BridgeTextCodec.stripMarker(text)
        val normalised = body.lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()
        val sender = originSender?.lowercase(Locale.ROOT)?.trim().orEmpty()
        return sha256Hex(sender + " " + normalised)
    }

    private val WHITESPACE = Regex("\\s+")

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        // 16 hex chars (64 bits) is ample for a cache that holds minutes of traffic
        // and keeps the Room rows small.
        return buildString(FINGERPRINT_HEX_LENGTH) {
            for (i in 0 until FINGERPRINT_HEX_LENGTH / 2) {
                append(HEX[(digest[i].toInt() shr 4) and 0xF])
                append(HEX[digest[i].toInt() and 0xF])
            }
        }
    }

    private const val FINGERPRINT_HEX_LENGTH = 16
    private val HEX = "0123456789abcdef".toCharArray()
}

/**
 * Non-persistent [BridgeSeenCache].
 *
 * Access is guarded by an intrinsic lock rather than a mutex: every operation is
 * an O(1) map touch, so the critical sections are far shorter than the cost of
 * suspending.
 */
class InMemoryBridgeSeenCache : BridgeSeenCache {

    private val lock = Any()
    private val byFingerprint = LinkedHashMap<String, BridgeSeenEntry>()
    private val bridgeIds = LinkedHashMap<String, Long>()

    override suspend fun hasFingerprint(fingerprint: String): Boolean =
        synchronized(lock) { byFingerprint.containsKey(fingerprint) }

    override suspend fun hasBridgeId(bridgeId: String): Boolean =
        synchronized(lock) { bridgeIds.containsKey(bridgeId) }

    override suspend fun record(entry: BridgeSeenEntry) {
        synchronized(lock) {
            byFingerprint[entry.fingerprint] = entry
            entry.bridgeId?.let { bridgeIds[it] = entry.seenAtMillis }
        }
    }

    override suspend fun purgeOlderThan(cutoffMillis: Long) {
        synchronized(lock) {
            byFingerprint.entries.removeAll { it.value.seenAtMillis < cutoffMillis }
            bridgeIds.entries.removeAll { it.value < cutoffMillis }
        }
    }

    /** Test/diagnostics helper. */
    val size: Int get() = synchronized(lock) { byFingerprint.size }
}
