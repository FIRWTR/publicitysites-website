package com.unifiedmesh.protocol.api

import com.unifiedmesh.core.model.MeshChannel
import com.unifiedmesh.core.model.MeshNode
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.OutgoingMessage
import com.unifiedmesh.core.model.RadioConnectionState
import com.unifiedmesh.core.model.RadioDevice
import com.unifiedmesh.core.model.RadioDeviceInfo
import com.unifiedmesh.core.model.SendResult
import com.unifiedmesh.core.model.UnifiedMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single seam between the app and a mesh protocol.
 *
 * Everything above this interface — inbox, composer, bridge, notifications, map —
 * deals only in [com.unifiedmesh.core.model] types. Everything below it is free
 * to be as protocol-specific as it needs to be.
 *
 * ### Independence contract
 *
 * Each adapter instance owns exactly one radio and one connection. Implementations
 * **must not** share mutable state, coroutine scopes, or transport resources with
 * any other adapter instance. In particular:
 *
 *  - [disconnect] on one adapter must have no observable effect on another.
 *  - A transport failure must be contained: it may move *this* adapter to
 *    [RadioConnectionState.Reconnecting]/[RadioConnectionState.Error] and must not
 *    propagate an exception out of the adapter's own scope.
 *  - [incomingMessages] must not complete or throw because a *different* radio
 *    dropped.
 *
 * `RadioIsolationTest` in `:core:radio` asserts this behaviour against both the
 * fakes and the real adapters' shared session machinery.
 */
interface MeshRadioAdapter {

    /** Which mesh this adapter speaks. Fixed for the lifetime of the instance. */
    val protocol: MeshProtocol

    /** Current link state. Always has a value; starts at [RadioConnectionState.Disconnected]. */
    val connectionState: StateFlow<RadioConnectionState>

    /**
     * Inbound **text** messages only.
     *
     * Adapters classify each received packet and route non-text traffic to
     * [nodes], [deviceInfo], or the diagnostics log. Telemetry, position reports,
     * routing packets, ACKs and admin/config traffic never appear here — which is
     * what keeps the bridge from ever seeing them.
     *
     * This is a hot flow scoped to the adapter; collecting it does not start or
     * stop the radio connection.
     */
    val incomingMessages: Flow<UnifiedMessage>

    /**
     * Delivery-state transitions for previously sent messages, keyed by
     * [OutgoingMessage.id]. Emitted when the network acknowledges or the ACK
     * window expires.
     */
    val deliveryUpdates: Flow<DeliveryUpdate>

    /** Known nodes/contacts on this network. Empty until the handshake completes. */
    val nodes: StateFlow<List<MeshNode>>

    /** Channels/groups this radio can transmit on. */
    val channels: StateFlow<List<MeshChannel>>

    /** Latest device telemetry, or null while disconnected. */
    val deviceInfo: StateFlow<RadioDeviceInfo?>

    /**
     * Opens the link and runs the protocol handshake.
     *
     * Suspends until the adapter reaches [RadioConnectionState.Connected] or
     * fails. Failures are reported through [connectionState]; this function does
     * not throw for ordinary connection problems (radio out of range, GATT 133),
     * only for programmer errors such as a device with the wrong transport.
     */
    suspend fun connect(device: RadioDevice)

    /**
     * Closes the link and cancels any pending reconnect.
     *
     * Idempotent, and safe to call from any state.
     */
    suspend fun disconnect()

    /**
     * Queues [message] for transmission on this radio.
     *
     * Returns once the radio has accepted or rejected the packet. Later delivery
     * confirmation (where the protocol supports it) arrives on [deliveryUpdates].
     */
    suspend fun sendMessage(message: OutgoingMessage): SendResult

    /**
     * Current device information, refreshed from the radio where the protocol
     * supports an explicit query.
     */
    suspend fun getDeviceInfo(): RadioDeviceInfo
}

/** A delivery-state transition for one previously sent message. */
data class DeliveryUpdate(
    val messageId: String,
    val state: com.unifiedmesh.core.model.DeliveryState,
    val detail: String? = null,
)
