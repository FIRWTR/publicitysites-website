package com.unifiedmesh.core.model

/**
 * A node (Meshtastic) or contact (MeshCore), normalised.
 *
 * Identities are **never** merged across protocols, even when the display names
 * match — `id` is only unique within [protocol]. Manual identity linking is a
 * planned follow-up; see docs/ARCHITECTURE.md.
 */
data class MeshNode(
    val protocol: MeshProtocol,
    /**
     * Protocol-scoped identity.
     * - Meshtastic: the node number formatted as `!` + 8 lowercase hex digits,
     *   matching the firmware's `User.id` convention.
     * - MeshCore: the first 6 bytes of the contact's public key, lowercase hex.
     */
    val id: String,
    val longName: String?,
    val shortName: String?,
    val lastHeard: Long?,
    val position: NodePosition? = null,
    val batteryLevel: Int? = null,
    val snr: Float? = null,
    /** Radio hops away, when the protocol reports it. */
    val hopsAway: Int? = null,
    /** True for the radio this phone is directly connected to. */
    val isSelf: Boolean = false,
) {
    /** Best available human-readable label. */
    val displayName: String
        get() = longName?.takeIf { it.isNotBlank() }
            ?: shortName?.takeIf { it.isNotBlank() }
            ?: id

    /** Composite key: protocol + protocol-scoped id. */
    val key: String get() = "${protocol.name}:$id"
}

/**
 * A node position.
 *
 * Both protocols transmit fixed-point coordinates and both can report a node
 * with no position at all, so this is nullable everywhere it appears.
 */
data class NodePosition(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Int? = null,
    val timestamp: Long? = null,
) {
    /**
     * Rejects the "no fix" sentinel both firmwares emit (0/0) and out-of-range
     * values, so the map never plots a node in the Gulf of Guinea.
     */
    val isValid: Boolean
        get() = latitude in -90.0..90.0 &&
            longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0)
}
