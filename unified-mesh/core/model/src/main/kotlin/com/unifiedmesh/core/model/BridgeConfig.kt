package com.unifiedmesh.core.model

/**
 * Bridge settings.
 *
 * The bridge is opt-in at three levels: a master switch, a per-direction switch,
 * and per-rule switches. All three must be on for a message to cross.
 */
data class BridgeConfig(
    val masterEnabled: Boolean = false,
    val meshtasticToMeshCore: Boolean = false,
    val meshCoreToMeshtastic: Boolean = false,
    /**
     * Maximum number of times a message may cross *this* bridge.
     *
     * Deliberately tiny. A value of 1 means: a message that arrived on network A
     * may be relayed onto network B once, and the copy that comes back (via
     * another operator's bridge, or an echo) is dropped.
     */
    val maxHops: Int = DEFAULT_MAX_HOPS,
    /** How long a fingerprint stays in the recently-seen cache. */
    val duplicateWindowMillis: Long = DEFAULT_DUPLICATE_WINDOW_MILLIS,
    /** Messages older than this when received are never bridged. */
    val maxMessageAgeMillis: Long = DEFAULT_MAX_MESSAGE_AGE_MILLIS,
    /** Prefix relayed text with `[MT: Bear]` / `[MC: Ridge Base]`. */
    val annotateRelayedText: Boolean = true,
    val rules: List<BridgeRule> = emptyList(),
) {
    fun directionEnabled(from: MeshProtocol): Boolean = masterEnabled && when (from) {
        MeshProtocol.MESHTASTIC -> meshtasticToMeshCore
        MeshProtocol.MESHCORE -> meshCoreToMeshtastic
    }

    companion object {
        const val DEFAULT_MAX_HOPS = 1
        const val DEFAULT_DUPLICATE_WINDOW_MILLIS = 10 * 60 * 1000L // 10 minutes
        const val DEFAULT_MAX_MESSAGE_AGE_MILLIS = 5 * 60 * 1000L // 5 minutes
    }
}

/**
 * One channel-to-channel mapping, e.g. `Meshtastic LongFast -> MeshCore Public`.
 *
 * Rules are directional. Bridging both ways between the same pair of channels
 * takes two rules, so an operator can open one direction only.
 */
data class BridgeRule(
    val id: String,
    val enabled: Boolean,
    val fromProtocol: MeshProtocol,
    /** Source channel id, or null to match any channel on [fromProtocol]. */
    val fromChannelId: String?,
    val toProtocol: MeshProtocol,
    /** Destination channel id on [toProtocol]. Required — the bridge never guesses. */
    val toChannelId: String,
    /** Human label shown in settings. */
    val label: String = "",
) {
    init {
        require(fromProtocol != toProtocol) { "A bridge rule must cross protocols" }
    }

    fun matches(protocol: MeshProtocol, channelId: String?): Boolean =
        enabled && protocol == fromProtocol && (fromChannelId == null || fromChannelId == channelId)
}
