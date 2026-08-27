package com.unifiedmesh.core.bridge

import com.unifiedmesh.core.model.MeshProtocol

/**
 * Provenance recovered from a relayed message's text.
 *
 * @param originProtocol the network the text was originally spoken on.
 * @param originSender the display name of the original sender.
 * @param body the text with the bridge marker removed.
 * @param hops how many bridges the text has already crossed (>= 1).
 */
data class BridgeMarker(
    val originProtocol: MeshProtocol,
    val originSender: String,
    val body: String,
    val hops: Int,
)

/**
 * Encodes and decodes the on-air bridge marker.
 *
 * A relayed message goes on the air as:
 *
 *     [MT: Bear] Need help at camp
 *
 * and, if some *other* operator's bridge relays it a second time:
 *
 *     [MT x2: Bear] Need help at camp
 *
 * ### Why the marker carries the hop count
 *
 * A bridge id is a UUID; putting one in the payload would cost ~36 bytes out of
 * a ~200-byte (Meshtastic) or 133-character (MeshCore) budget. The hop counter
 * costs 3 characters and is the only piece of loop state that *must* survive the
 * trip over the air, because a second phone running this app has no way to see
 * our local caches. Everything else — bridge ids, fingerprints — stays local.
 *
 * The marker is therefore the cross-device half of loop prevention and the
 * caches in [BridgeEngine] are the local half. Neither is sufficient alone.
 */
object BridgeTextCodec {

    /** `[MT: Bear] body` or `[MT x3: Bear] body`. */
    private val MARKER = Regex("""^\[(MT|MC)(?:\s*x(\d+))?:\s*([^\]]*)\]\s?(.*)$""", RegexOption.DOT_MATCHES_ALL)

    private const val UNKNOWN_SENDER = "?"

    fun encode(originProtocol: MeshProtocol, originSender: String?, body: String, hops: Int): String {
        require(hops >= 1) { "A relayed message has crossed at least one bridge" }
        val sender = sanitiseSender(originSender)
        val count = if (hops > 1) " x$hops" else ""
        return "[${originProtocol.shortLabel}$count: $sender] $body"
    }

    /** Returns null when [text] carries no bridge marker. */
    fun decode(text: String): BridgeMarker? {
        val match = MARKER.matchEntire(text.trimStart()) ?: return null
        val protocol = when (match.groupValues[1]) {
            MeshProtocol.MESHTASTIC.shortLabel -> MeshProtocol.MESHTASTIC
            MeshProtocol.MESHCORE.shortLabel -> MeshProtocol.MESHCORE
            else -> return null
        }
        val hops = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1
        return BridgeMarker(
            originProtocol = protocol,
            originSender = match.groupValues[3].trim().ifEmpty { UNKNOWN_SENDER },
            body = match.groupValues[4],
            hops = hops.coerceAtLeast(1),
        )
    }

    /** True when [text] already bears a bridge marker. */
    fun isMarked(text: String): Boolean = decode(text) != null

    /**
     * Strips a marker if present, returning the human-written body.
     *
     * Used for fingerprinting so that the same sentence produces the same
     * fingerprint no matter how many times it has been re-marked.
     */
    fun stripMarker(text: String): String = decode(text)?.body ?: text

    /**
     * Removes `]` and newlines from a sender name so a hostile or merely odd
     * display name cannot forge or truncate a marker.
     */
    private fun sanitiseSender(name: String?): String {
        val cleaned = name?.replace(Regex("""[\]\[\r\n]"""), "")?.trim().orEmpty()
        return cleaned.ifEmpty { UNKNOWN_SENDER }.take(MAX_SENDER_LENGTH)
    }

    private const val MAX_SENDER_LENGTH = 24
}
