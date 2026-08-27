package com.unifiedmesh.core.model

/**
 * The mesh networks Unified Mesh can talk to.
 *
 * The two radios stay completely independent: nothing in the app ever asks a
 * Meshtastic radio to speak MeshCore or vice versa. This enum only tags which
 * side of the app a piece of data came from or is going to.
 */
enum class MeshProtocol {
    MESHTASTIC,
    MESHCORE,
    ;

    /** Two-letter tag shown in the UI ("MT" / "MC"). */
    val shortLabel: String
        get() = when (this) {
            MESHTASTIC -> "MT"
            MESHCORE -> "MC"
        }

    val displayName: String
        get() = when (this) {
            MESHTASTIC -> "Meshtastic"
            MESHCORE -> "MeshCore"
        }

    /** The other protocol. Used by the bridge to pick a relay destination. */
    val other: MeshProtocol
        get() = when (this) {
            MESHTASTIC -> MESHCORE
            MESHCORE -> MESHTASTIC
        }
}
