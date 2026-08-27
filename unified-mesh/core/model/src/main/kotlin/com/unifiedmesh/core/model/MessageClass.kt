package com.unifiedmesh.core.model

/**
 * What kind of traffic an inbound packet carries.
 *
 * Adapters classify every packet before it reaches the app. Version 1 only ever
 * bridges [TEXT]; everything else is recorded for diagnostics and dropped by the
 * bridge. Keeping the classification in the model (rather than as a boolean on
 * the message) means adding, say, waypoint bridging later is a change to the
 * bridge policy, not to every adapter.
 */
enum class MessageClass {
    /** Human-readable chat text. The only class the v1 bridge relays. */
    TEXT,

    /** Node/contact announcements, telemetry, position reports. */
    TELEMETRY,
    POSITION,
    NODE_INFO,

    /** Routing, ACKs, admin/config, and anything else protocol-internal. */
    ROUTING,
    ACK,
    ADMIN,
    OTHER,
    ;

    /** Whether the v1 bridge is permitted to consider this class at all. */
    val isBridgeable: Boolean get() = this == TEXT
}
