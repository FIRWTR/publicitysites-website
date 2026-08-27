package com.unifiedmesh.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.unifiedmesh.protocol.api.RadioLinkException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * A single GATT connection, wrapped so callers can `suspend` instead of
 * implementing [BluetoothGattCallback].
 *
 * Android's GATT stack allows exactly one outstanding operation per connection:
 * issuing a second write before the first `onCharacteristicWrite` arrives makes
 * the stack drop it silently. Every operation here therefore goes through
 * [operationMutex], which is what makes concurrent use from a protocol session
 * safe.
 *
 * One instance owns one radio. Two radios means two instances, with no shared
 * state — which is what lets one link drop without disturbing the other. The
 * Android BLE stack has a hard limit on simultaneous GATT clients (7 on most
 * devices), so two is comfortably within budget.
 */
@SuppressLint("MissingPermission") // Callers gate on BluetoothPermissions; see connect().
internal class GattConnection(
    private val context: Context,
    private val address: String,
    private val onDisconnected: (status: Int) -> Unit,
    private val onNotification: (UUID, ByteArray) -> Unit,
    private val diagnostics: (String) -> Unit,
) {

    private val operationMutex = Mutex()

    private var gatt: BluetoothGatt? = null

    private val connected = CompletableDeferred<Unit>()
    private var servicesDiscovered: CompletableDeferred<Unit>? = null
    private var pendingRead: CompletableDeferred<ByteArray>? = null
    private var pendingWrite: CompletableDeferred<Unit>? = null
    private var pendingDescriptorWrite: CompletableDeferred<Unit>? = null
    private var pendingMtu: CompletableDeferred<Int>? = null
    private var pendingRssi: CompletableDeferred<Int>? = null

    @Volatile
    private var closed = false

    /** Negotiated ATT MTU. 23 is the BLE default until the radio agrees to more. */
    @Volatile
    var mtu: Int = DEFAULT_MTU
        private set

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        connected.complete(Unit)
                    } else {
                        connected.completeExceptionally(
                            RadioLinkException("GATT connect failed with status $status"),
                        )
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    diagnostics("GATT disconnected, status=$status")
                    if (!connected.isCompleted) {
                        connected.completeExceptionally(
                            RadioLinkException(describeConnectFailure(status)),
                        )
                    }
                    failPending(RadioLinkException("Link dropped (status $status)"))
                    if (!closed) onDisconnected(status)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                servicesDiscovered?.complete(Unit)
            } else {
                servicesDiscovered?.completeExceptionally(
                    RadioLinkException("Service discovery failed with status $status"),
                )
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            this@GattConnection.mtu = mtu
            pendingMtu?.complete(mtu)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingRssi?.complete(rssi)
            } else {
                pendingRssi?.completeExceptionally(RadioLinkException("RSSI read failed"))
            }
        }

        // --- API 33+ callbacks (value delivered explicitly) ---

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            completeRead(status, value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onNotification(characteristic.uuid, value)
        }

        // --- Pre-33 callbacks (value read off the characteristic) ---

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                completeRead(status, characteristic.value ?: ByteArray(0))
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                onNotification(characteristic.uuid, characteristic.value ?: ByteArray(0))
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingWrite?.complete(Unit)
            } else {
                pendingWrite?.completeExceptionally(RadioLinkException("Write failed with status $status"))
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingDescriptorWrite?.complete(Unit)
            } else {
                pendingDescriptorWrite?.completeExceptionally(
                    RadioLinkException("Enabling notifications failed with status $status"),
                )
            }
        }

        private fun completeRead(status: Int, value: ByteArray) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingRead?.complete(value)
            } else {
                pendingRead?.completeExceptionally(RadioLinkException("Read failed with status $status"))
            }
        }
    }

    /**
     * Connects, discovers services and negotiates the MTU.
     *
     * The caller must already hold `BLUETOOTH_CONNECT` (API 31+) — see
     * [BluetoothPermissions]. That is why the class carries a file-level
     * `MissingPermission` suppression rather than sprinkling checks that would
     * have to be re-checked after every await anyway.
     */
    suspend fun connect(requestedMtu: Int) {
        val manager = context.getSystemService(BluetoothManager::class.java)
            ?: throw RadioLinkException("This device has no Bluetooth adapter")
        val adapter = manager.adapter ?: throw RadioLinkException("Bluetooth is unavailable")
        if (!adapter.isEnabled) throw RadioLinkException("Bluetooth is turned off")

        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
            ?: throw RadioLinkException("Not a Bluetooth address: $address")

        // autoConnect = false gives a fast, direct connection attempt. Reconnection
        // is handled by RadioSession's backoff rather than by the GATT stack, so
        // that a failing radio cannot hold a connection slot indefinitely.
        gatt = device.connectGatt(context, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
            ?: throw RadioLinkException("Could not open a GATT connection")

        withTimeoutOrThrow(CONNECT_TIMEOUT_MILLIS, "connect") { connected.await() }

        val discovery = CompletableDeferred<Unit>()
        servicesDiscovered = discovery
        if (requireGatt().discoverServices() != true) {
            throw RadioLinkException("Could not start service discovery")
        }
        withTimeoutOrThrow(DISCOVERY_TIMEOUT_MILLIS, "service discovery") { discovery.await() }

        // A larger MTU is a strong preference, not a requirement: both protocols
        // work at the 23-byte default, just with more round trips.
        runCatching {
            val mtuResult = CompletableDeferred<Int>()
            pendingMtu = mtuResult
            if (requireGatt().requestMtu(requestedMtu)) {
                withTimeoutOrThrow(MTU_TIMEOUT_MILLIS, "MTU negotiation") { mtuResult.await() }
            }
        }.onFailure { diagnostics("MTU negotiation declined, continuing at $mtu") }
        pendingMtu = null

        diagnostics("connected, mtu=$mtu")
    }

    fun service(uuid: UUID) = gatt?.getService(uuid)

    fun characteristic(serviceUuid: UUID, characteristicUuid: UUID): BluetoothGattCharacteristic? =
        service(serviceUuid)?.getCharacteristic(characteristicUuid)

    /** Subscribes to notifications on [characteristic] and writes its CCCD. */
    suspend fun enableNotifications(characteristic: BluetoothGattCharacteristic) = operationMutex.withLock {
        val gatt = requireGatt()
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            throw RadioLinkException("Could not enable notifications on ${characteristic.uuid}")
        }
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            ?: throw RadioLinkException("Characteristic ${characteristic.uuid} has no CCCD")

        val done = CompletableDeferred<Unit>()
        pendingDescriptorWrite = done
        try {
            @Suppress("DEPRECATION")
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
            if (!started) throw RadioLinkException("Could not write the CCCD for ${characteristic.uuid}")
            withTimeoutOrThrow(OPERATION_TIMEOUT_MILLIS, "enable notifications") { done.await() }
        } finally {
            pendingDescriptorWrite = null
        }
    }

    /** Reads [characteristic] and returns its value. */
    suspend fun read(characteristic: BluetoothGattCharacteristic): ByteArray = operationMutex.withLock {
        val done = CompletableDeferred<ByteArray>()
        pendingRead = done
        try {
            if (!requireGatt().readCharacteristic(characteristic)) {
                throw RadioLinkException("Could not start a read of ${characteristic.uuid}")
            }
            withTimeoutOrThrow(OPERATION_TIMEOUT_MILLIS, "read") { done.await() }
        } finally {
            pendingRead = null
        }
    }

    /** Writes [value] to [characteristic] and waits for the stack to confirm. */
    suspend fun write(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
    ) = operationMutex.withLock {
        val done = CompletableDeferred<Unit>()
        pendingWrite = done
        try {
            val gatt = requireGatt()
            @Suppress("DEPRECATION")
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = writeType
                characteristic.value = value
                gatt.writeCharacteristic(characteristic)
            }
            if (!started) throw RadioLinkException("Could not start a write to ${characteristic.uuid}")
            withTimeoutOrThrow(OPERATION_TIMEOUT_MILLIS, "write") { done.await() }
        } finally {
            pendingWrite = null
        }
    }

    /** Current link RSSI in dBm, or null when the stack refuses the read. */
    suspend fun readRssi(): Int? = runCatching {
        operationMutex.withLock {
            val done = CompletableDeferred<Int>()
            pendingRssi = done
            try {
                if (requireGatt().readRemoteRssi() != true) return@withLock null
                withTimeoutOrThrow(OPERATION_TIMEOUT_MILLIS, "RSSI read") { done.await() }
            } finally {
                pendingRssi = null
            }
        }
    }.getOrNull()

    /** Closes the connection and releases the GATT client slot. */
    fun close() {
        closed = true
        failPending(RadioLinkException("Connection closed"))
        runCatching { gatt?.disconnect() }
        // close() must always run: without it the client slot leaks and the
        // device eventually refuses new connections with status 133.
        runCatching { gatt?.close() }
        gatt = null
    }

    private fun requireGatt(): BluetoothGatt = gatt ?: throw RadioLinkException("Not connected")

    private fun failPending(cause: Throwable) {
        pendingRead?.completeExceptionally(cause)
        pendingWrite?.completeExceptionally(cause)
        pendingDescriptorWrite?.completeExceptionally(cause)
        pendingMtu?.completeExceptionally(cause)
        pendingRssi?.completeExceptionally(cause)
        servicesDiscovered?.completeExceptionally(cause)
    }

    private suspend fun <T> withTimeoutOrThrow(millis: Long, what: String, block: suspend () -> T): T = try {
        withTimeout(millis) { block() }
    } catch (e: TimeoutCancellationException) {
        throw RadioLinkException("Timed out waiting for $what")
    }

    /**
     * Turns a GATT connect status into something a field operator can act on.
     *
     * 133 is the notorious catch-all the stack returns for everything from a
     * radio that is out of range to a client slot that was never released.
     */
    private fun describeConnectFailure(status: Int): String = when (status) {
        GATT_ERROR -> "Could not reach the radio. Move closer, or power-cycle it, then try again."
        BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION,
        BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION,
        -> "The radio requires pairing. Pair it in Android's Bluetooth settings, then try again."

        else -> "Disconnected (status $status)"
    }

    private object BluetoothStatusCodes {
        /** `android.bluetooth.BluetoothStatusCodes.SUCCESS`, which is API 33+ only. */
        const val SUCCESS = 0
    }

    companion object {
        val CLIENT_CHARACTERISTIC_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val GATT_ERROR = 133
        private const val DEFAULT_MTU = 23
        private const val CONNECT_TIMEOUT_MILLIS = 30_000L
        private const val DISCOVERY_TIMEOUT_MILLIS = 20_000L
        private const val MTU_TIMEOUT_MILLIS = 5_000L
        private const val OPERATION_TIMEOUT_MILLIS = 10_000L
    }
}

/**
 * Frame queue shared by the BLE transports.
 *
 * [com.unifiedmesh.protocol.api.RadioLinkTransport] requires a buffered,
 * single-consumer inbound flow: a radio can answer a command before the reading
 * coroutine is scheduled, and a subscriber-less hot flow would drop the reply.
 */
internal class FrameQueue {
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)

    val frames: Flow<ByteArray> = channel.receiveAsFlow()

    fun offer(frame: ByteArray) {
        channel.trySend(frame)
    }

    fun close(cause: Throwable? = null) {
        channel.close(cause)
    }
}

/** Link state shared by the BLE transports. */
internal class LinkStateHolder {
    private val state = MutableStateFlow(com.unifiedmesh.protocol.api.LinkState.CLOSED)
    val flow = state.asStateFlow()

    fun set(value: com.unifiedmesh.protocol.api.LinkState) {
        state.value = value
    }

    val current get() = state.value
}
