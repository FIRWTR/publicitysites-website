package com.unifiedmesh.core.model

/**
 * A channel/group the radio can transmit on.
 *
 * Meshtastic exposes up to 8 indexed channels (`Channel.index`, `ChannelSettings.name`);
 * MeshCore exposes up to `MAX_GROUP_CHANNELS` indexed group channels with a name
 * and a 16-byte secret. Only the index and name cross into the app — **the PSK /
 * channel secret never leaves the protocol layer.**
 */
data class MeshChannel(
    val protocol: MeshProtocol,
    /** Protocol-scoped channel id. Both protocols use a small integer index, stringified. */
    val id: String,
    val name: String,
    val index: Int,
    /** True for the network's default/primary channel. */
    val isPrimary: Boolean = false,
) {
    val displayName: String get() = name.takeIf { it.isNotBlank() } ?: "Channel $index"
}
