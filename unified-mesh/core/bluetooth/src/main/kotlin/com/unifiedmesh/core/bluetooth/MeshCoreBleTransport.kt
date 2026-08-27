package com.unifiedmesh.core.bluetooth

import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import com.unifiedmesh.core.model.RadioDevice
import com.unifiedmesh.core.model.RadioTransport
import com.unifiedmesh.protocol.api.LinkState
import com.unifiedmesh.protocol.api.RadioLinkException
import com.unifiedmesh.protocol.api.RadioLinkTransport
import com.unifiedmesh.protocol.meshcore.MeshCoreProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Carries the MeshCore companion protocol over BLE.
 *
 * Framing is simpler than Meshtastic's: the companion protocol documents that
 * "for BLE, a frame is simply a single characteristic value", so one notification
 * on the TX characteristic is exactly one frame and one write to RX is exactly
 * one command. There is no drain loop and no reassembly.
 *
 * The MTU matters more here than it does for Meshtastic. A frame can be up to
 * `MAX_FRAME_SIZE` (176) bytes, and anything the negotiated MTU cannot carry is
 * silently truncated by the stack rather than split, so [open] refuses to
 * proceed on a link too small to carry a full frame.
 */
class MeshCoreBleTransport(
    private val context: Context,
    private val diagnostics: (String) -> Unit = {},
) : RadioLinkTransport {

    private val queue = FrameQueue()
    private val linkStateHolder = LinkStateHolder()

    override val linkState: StateFlow<LinkState> = linkStateHolder.flow
    override val incoming: Flow<ByteArray> = queue.frames

    override val maxFrameSize: Int
        get() = minOf(MeshCoreProtocol.MAX_FRAME_SIZE, (connection?.mtu ?: 23) - ATT_HEADER_BYTES)

    private var connection: GattConnection? = null

    override suspend fun open(device: RadioDevice) {
        require(device.transport == RadioTransport.BLE) {
            "MeshCoreBleTransport cannot open a ${device.transport} device"
        }
        linkStateHolder.set(LinkState.OPENING)

        val gatt = GattConnection(
            context = context,
            address = device.address,
            onDisconnected = { status ->
                linkStateHolder.set(LinkState.FAILED)
                queue.close(RadioLinkException("MeshCore link dropped (status $status)"))
            },
            onNotification = { uuid, value ->
                // One notification is one complete frame.
                if (uuid == TX_UUID && value.isNotEmpty()) queue.offer(value)
            },
            diagnostics = { diagnostics("meshcore-ble: $it") },
        )
        connection = gatt

        try {
            gatt.connect(MeshCoreProtocol.REQUESTED_MTU)
            val tx = gatt.characteristic(SERVICE_UUID, TX_UUID)
                ?: throw RadioLinkException(
                    "This device does not expose the MeshCore companion service. " +
                        "Check that it is the radio running MeshCore companion firmware.",
                )
            gatt.enableNotifications(tx)

            val usable = gatt.mtu - ATT_HEADER_BYTES
            if (usable < MeshCoreProtocol.MAX_FRAME_SIZE) {
                // Carrying on would mean silently losing the tail of long frames —
                // contact records in particular are 148 bytes.
                throw RadioLinkException(
                    "This phone negotiated a $usable-byte Bluetooth payload, but MeshCore frames " +
                        "need ${MeshCoreProtocol.MAX_FRAME_SIZE}. Try forgetting the device in " +
                        "Android's Bluetooth settings and pairing again.",
                )
            }
        } catch (e: Throwable) {
            gatt.close()
            connection = null
            linkStateHolder.set(LinkState.FAILED)
            throw e
        }

        linkStateHolder.set(LinkState.OPEN)
    }

    override suspend fun send(frame: ByteArray) {
        val gatt = connection ?: throw RadioLinkException("MeshCore link is not open")
        if (frame.size > MeshCoreProtocol.MAX_FRAME_SIZE) {
            throw RadioLinkException("Frame is ${frame.size} bytes, over the ${MeshCoreProtocol.MAX_FRAME_SIZE}-byte limit")
        }
        val rx = gatt.characteristic(SERVICE_UUID, RX_UUID)
            ?: throw RadioLinkException("The radio does not expose the companion RX characteristic")
        // The companion protocol documentation calls for write-with-response.
        gatt.write(rx, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    override suspend fun readLinkRssi(): Int? = connection?.readRssi()

    override suspend fun close() {
        connection?.close()
        connection = null
        linkStateHolder.set(LinkState.CLOSED)
        queue.close()
    }

    private companion object {
        val SERVICE_UUID: UUID = UUID.fromString(MeshCoreProtocol.SERVICE_UUID)
        val RX_UUID: UUID = UUID.fromString(MeshCoreProtocol.RX_CHARACTERISTIC_UUID)
        val TX_UUID: UUID = UUID.fromString(MeshCoreProtocol.TX_CHARACTERISTIC_UUID)

        const val ATT_HEADER_BYTES = 3
    }
}
