package com.unifiedmesh.core.model

enum class DiagnosticLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Categories shown as filter chips on the Diagnostics screen.
 */
enum class DiagnosticCategory {
    BLE,
    CONNECTION,
    HANDSHAKE,
    RX,
    TX,
    BRIDGE,
    DATABASE,
    PERMISSION,
    GENERAL,
}

/**
 * One developer diagnostics entry.
 *
 * **Never** put message bodies, PSKs, private keys, or public-key material in
 * [message] or [detail]. The sanitiser in `core:bridge` and the adapters record
 * metadata only (lengths, ids, hashes, port numbers, response codes). This is
 * enforced by convention plus the `DiagnosticsSanitiser` helper.
 */
data class DiagnosticEvent(
    val id: Long,
    val timestamp: Long,
    val level: DiagnosticLevel,
    val category: DiagnosticCategory,
    val protocol: MeshProtocol?,
    val message: String,
    val detail: String? = null,
)
