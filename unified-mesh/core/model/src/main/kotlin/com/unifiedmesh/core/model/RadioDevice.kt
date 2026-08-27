package com.unifiedmesh.core.model

/**
 * Physical transports a radio can be reached over.
 *
 * Version 1 ships BLE only. USB and Wi-Fi (TCP) are declared here so that the
 * adapter and persistence layers already carry the discriminator; adding them
 * later is a new [com.unifiedmesh.protocol.api.RadioTransportFactory]
 * implementation, not a model change.
 */
enum class RadioTransport { BLE, USB, TCP }

/**
 * A radio the app can connect to.
 *
 * @param address transport-scoped address. For [RadioTransport.BLE] this is the
 *   Bluetooth MAC as reported by the platform scanner.
 * @param assignedProtocol which protocol slot the operator assigned this device
 *   to, or null when it is an unassigned scan result. Assignment is always
 *   explicit — the app never guesses which stack a device is running purely
 *   from its advertisement.
 */
data class RadioDevice(
    val address: String,
    val name: String?,
    val transport: RadioTransport = RadioTransport.BLE,
    val assignedProtocol: MeshProtocol? = null,
    /** Advertised service UUIDs, lowercase. Used only as a scanning *hint*. */
    val advertisedServiceUuids: List<String> = emptyList(),
    /** Scan RSSI in dBm, when available. */
    val rssi: Int? = null,
) {
    val displayName: String get() = name?.takeIf { it.isNotBlank() } ?: address
}

/** Live connection state for one radio slot. */
sealed interface RadioConnectionState {
    data object Disconnected : RadioConnectionState

    /** Scanning/attaching to the GATT server. */
    data object Connecting : RadioConnectionState

    /** Link is up but the protocol handshake has not completed yet. */
    data object Handshaking : RadioConnectionState

    data class Connected(val deviceInfo: RadioDeviceInfo) : RadioConnectionState

    /**
     * Link dropped and an automatic retry is scheduled.
     *
     * @param attempt 1-based retry counter.
     * @param nextAttemptAtMillis wall-clock time of the next attempt, for the UI countdown.
     */
    data class Reconnecting(val attempt: Int, val nextAttemptAtMillis: Long) : RadioConnectionState

    /** Terminal failure that needs operator action (e.g. permission revoked, pairing rejected). */
    data class Error(val reason: String, val recoverable: Boolean) : RadioConnectionState

    val isConnected: Boolean get() = this is Connected
}

/**
 * Everything the Radios screen shows about an attached device.
 *
 * All fields except [protocol] are nullable: a radio may not have reported its
 * battery, and MeshCore reports battery as millivolts with no percentage, so
 * the adapter fills in whatever the firmware actually gave us.
 */
data class RadioDeviceInfo(
    val protocol: MeshProtocol,
    val deviceName: String? = null,
    val hardwareModel: String? = null,
    val firmwareVersion: String? = null,
    /** Battery charge 0..100, when the firmware reports a percentage. */
    val batteryLevel: Int? = null,
    /** Battery voltage in millivolts, when the firmware reports raw voltage. */
    val batteryMilliVolts: Int? = null,
    /** RSSI of the *phone-to-radio* BLE link, in dBm. */
    val linkRssi: Int? = null,
    /** SNR of the last received LoRa packet, in dB. */
    val lastPacketSnr: Float? = null,
    /** The radio's own node/contact identity on its mesh. */
    val nodeId: String? = null,
    val nodeName: String? = null,
)
