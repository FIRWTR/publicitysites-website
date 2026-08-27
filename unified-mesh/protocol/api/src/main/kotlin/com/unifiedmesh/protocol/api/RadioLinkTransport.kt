package com.unifiedmesh.protocol.api

import com.unifiedmesh.core.model.RadioDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Link-level state of a transport, below the protocol handshake. */
enum class LinkState { CLOSED, OPENING, OPEN, FAILED }

/** Thrown when a transport cannot be opened or has died mid-session. */
class RadioLinkException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A byte-frame pipe to one radio.
 *
 * This is the boundary that keeps the protocol implementations free of Android:
 * `:protocol:meshcore` and `:protocol:meshtastic` are plain Kotlin modules that
 * speak their wire format through this interface, and `:core:bluetooth` supplies
 * the GATT-backed implementation. Adding USB or Wi-Fi later means writing another
 * implementation of this interface and nothing else.
 *
 * ### Framing contract
 *
 * Each emission on [incoming] is exactly one protocol frame:
 *
 * - **MeshCore** — one BLE notification on the companion TX characteristic is one
 *   frame, by design ("for BLE, a frame is simply a single characteristic value").
 * - **Meshtastic** — one successful read of the `FROMRADIO` characteristic is one
 *   serialised `FromRadio` protobuf. The transport owns the notify-then-drain loop
 *   (a `FROMNUM` notification means "read `FROMRADIO` until it returns empty").
 *
 * Implementations must never emit a partial or concatenated frame; a transport
 * that cannot honour that must fail instead.
 */
interface RadioLinkTransport {

    val linkState: StateFlow<LinkState>

    /**
     * Inbound frames.
     *
     * **Buffered and single-consumer.** Exactly one collector — the protocol
     * session — reads this flow, and the transport must buffer frames that
     * arrive before collection starts or between collectors. A radio can answer
     * a command in under a millisecond, well before the reading coroutine is
     * scheduled, so a conflated or subscriber-less hot flow here silently loses
     * handshake replies. Back the implementation with a channel, not a bare
     * `MutableSharedFlow`.
     *
     * The flow completes when the link closes normally and throws
     * [RadioLinkException] when it dies; adapters translate that into a
     * reconnect rather than letting it escape.
     */
    val incoming: Flow<ByteArray>

    /** Opens the link. Suspends until [linkState] reaches [LinkState.OPEN] or fails. */
    suspend fun open(device: RadioDevice)

    /** Closes the link. Idempotent. */
    suspend fun close()

    /** Writes one frame. Throws [RadioLinkException] if the link is not open. */
    suspend fun send(frame: ByteArray)

    /**
     * Current phone-to-radio signal strength in dBm, or null when the transport
     * cannot measure it (USB, and BLE stacks that refuse the read).
     */
    suspend fun readLinkRssi(): Int? = null

    /**
     * Largest frame the link can carry in one write.
     *
     * BLE implementations return the negotiated ATT MTU minus header overhead.
     */
    val maxFrameSize: Int
}

/** Creates transports for a given [com.unifiedmesh.core.model.RadioTransport]. */
fun interface RadioLinkTransportFactory {
    fun create(device: RadioDevice): RadioLinkTransport
}
