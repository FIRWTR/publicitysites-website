package com.unifiedmesh.protocol.meshtastic

/**
 * Meshtastic client-API constants.
 *
 * The BLE UUIDs are transcribed from the official Android client
 * (`meshtastic/meshtastic-android`, `core/ble/.../MeshtasticBleConstants.kt`),
 * and the handshake nonces from the same repository's `HandshakeConstants.kt`.
 * See docs/PROTOCOL-NOTES.md for the exact commit.
 *
 * Do not add a value to this file without a matching line in upstream source.
 */
object MeshtasticProtocol {

    // --- BLE ---------------------------------------------------------------

    /** The Meshtastic GATT service. */
    const val SERVICE_UUID = "6ba1b218-15a8-461f-9fa8-5dcae273eafd"

    /** Client writes a serialised `ToRadio` here. */
    const val TORADIO_CHARACTERISTIC_UUID = "f75c76d2-129e-4dad-a1dd-7866124401e7"

    /** Client reads one serialised `FromRadio` per read; an empty read means "drained". */
    const val FROMRADIO_CHARACTERISTIC_UUID = "2c55e69e-4993-11ed-b878-0242ac120002"

    /** Notifies a packet counter; the value itself is a hint, the notification is the signal. */
    const val FROMNUM_CHARACTERISTIC_UUID = "ed9da18c-a800-4f66-a670-aa7547e34453"

    /** Optional device log stream. Diagnostics only. */
    const val LOGRADIO_CHARACTERISTIC_UUID = "5a3d6e49-06e6-4423-9944-e9de8cdf9547"

    /** Meshtastic devices advertise as `<name>_<last 4 hex of node num>`. */
    const val BLE_NAME_PATTERN = "^.*_([0-9a-fA-F]{4})$"

    /**
     * MTU to request.
     *
     * A `FromRadio` carrying a full `NodeInfo` comfortably exceeds the 23-byte
     * default, and every extra round trip costs a GATT transaction.
     */
    const val REQUESTED_MTU = 512

    // --- Handshake ---------------------------------------------------------

    /**
     * Nonce sent in `ToRadio.want_config_id` to request the config stream.
     *
     * The radio replies with `my_info`, the config and module-config blocks, the
     * channel list and the node database, and finishes with a `FromRadio` whose
     * `config_complete_id` echoes this value. Matching the echo is how the client
     * knows the stream is finished, so the nonce must be non-zero.
     *
     * The value matches the official Android client so that firmware which
     * special-cases it behaves identically for this app.
     */
    const val CONFIG_NONCE = 69420

    /** Nonce for the node-database-only stage of the handshake. */
    const val NODE_INFO_NONCE = 69421

    // --- Addressing --------------------------------------------------------

    /**
     * `NODENUM_BROADCAST` — the destination for channel/broadcast traffic.
     *
     * Held as a Long because node numbers are unsigned 32-bit and 0xFFFFFFFF
     * does not fit in a signed Int without wrapping negative.
     */
    const val BROADCAST_ADDRESS = 0xFFFFFFFFL

    /** Formats a node number the way the firmware's `User.id` does. */
    fun formatNodeId(nodeNum: Long): String = "!%08x".format(nodeNum and 0xFFFFFFFFL)

    /** Parses a `!hex` node id back to a node number, or null if it is not one. */
    fun parseNodeId(id: String): Long? {
        val hex = id.removePrefix("!")
        if (hex.length != 8) return null
        return hex.toLongOrNull(16)
    }

    /**
     * Maximum text payload, in bytes.
     *
     * `Constants.DATA_PAYLOAD_LEN` in the firmware is 237 bytes; this app keeps a
     * margin so a multi-byte UTF-8 character near the limit cannot push a packet
     * over it.
     */
    const val MAX_TEXT_BYTES = 200

    /**
     * How long to wait for a routing ACK before giving up on a message.
     *
     * A flood-routed packet plus its acknowledgement crossing several hops takes
     * tens of seconds on slow modem presets.
     */
    const val ACK_TIMEOUT_MILLIS = 90_000L
}
