package com.unifiedmesh.core.bluetooth

import android.content.Context
import com.unifiedmesh.core.model.RadioDevice
import com.unifiedmesh.core.model.RadioTransport
import com.unifiedmesh.protocol.api.LinkState
import com.unifiedmesh.protocol.api.RadioLinkException
import com.unifiedmesh.protocol.api.RadioLinkTransport
import com.unifiedmesh.protocol.meshtastic.MeshtasticProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/**
 * Carries the Meshtastic client API over BLE.
 *
 * ### The drain loop
 *
 * The firmware does not push packets. It notifies `FROMNUM` to say "there is
 * something for you", and the client then reads `FROMRADIO` repeatedly until a
 * read comes back empty. That loop lives here, so the adapter above only ever
 * sees whole `FromRadio` protobufs.
 *
 * A drain is also triggered after every write, because a command's reply is
 * queued for reading rather than notified, and once at subscription time to
 * pick up anything already waiting — the firmware gates `FROMNUM` notifications
 * behind its send-packets state, so during the config handshake the notification
 * may never come.
 */
class MeshtasticBleTransport(
    private val context: Context,
    dispatcher: CoroutineContext = kotlinx.coroutines.Dispatchers.IO,
    private val diagnostics: (String) -> Unit = {},
) : RadioLinkTransport {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val queue = FrameQueue()
    private val linkStateHolder = LinkStateHolder()

    override val linkState: StateFlow<LinkState> = linkStateHolder.flow
    override val incoming: Flow<ByteArray> = queue.frames

    override val maxFrameSize: Int
        get() = (connection?.mtu ?: 23) - ATT_HEADER_BYTES

    private var connection: GattConnection? = null
    private var drainJob: Job? = null

    /**
     * Collapses a burst of drain triggers into one pending drain.
     *
     * Capacity 1 with DROP_OLDEST means a notification storm cannot queue up a
     * hundred redundant read loops, and a trigger raised while a drain is already
     * running is not lost.
     */
    private val drainTrigger = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override suspend fun open(device: RadioDevice) {
        require(device.transport == RadioTransport.BLE) {
            "MeshtasticBleTransport cannot open a ${device.transport} device"
        }
        linkStateHolder.set(LinkState.OPENING)

        val gatt = GattConnection(
            context = context,
            address = device.address,
            onDisconnected = { status ->
                linkStateHolder.set(LinkState.FAILED)
                queue.close(RadioLinkException("Meshtastic link dropped (status $status)"))
            },
            onNotification = { uuid, _ ->
                // The FROMNUM value is a packet counter; the notification itself is
                // the signal, and the count is not needed to drain correctly.
                if (uuid == FROM_NUM_UUID) drainTrigger.tryEmit(Unit)
            },
            diagnostics = { diagnostics("meshtastic-ble: $it") },
        )
        connection = gatt

        try {
            gatt.connect(MeshtasticProtocol.REQUESTED_MTU)
            val fromNum = gatt.characteristic(SERVICE_UUID, FROM_NUM_UUID)
                ?: throw RadioLinkException(
                    "This device does not expose the Meshtastic service. " +
                        "Check that it is the radio running Meshtastic.",
                )
            gatt.enableNotifications(fromNum)
        } catch (e: Throwable) {
            gatt.close()
            connection = null
            linkStateHolder.set(LinkState.FAILED)
            throw e
        }

        drainJob = scope.launch { runDrainLoop() }
        // Seed a drain: anything already queued in the radio should arrive without
        // waiting for a notification that may be gated behind firmware state.
        drainTrigger.tryEmit(Unit)
        linkStateHolder.set(LinkState.OPEN)
    }

    private suspend fun runDrainLoop() {
        drainTrigger.collect {
            val gatt = connection ?: return@collect
            val fromRadio = gatt.characteristic(SERVICE_UUID, FROM_RADIO_UUID) ?: return@collect
            while (true) {
                val packet = try {
                    gatt.read(fromRadio)
                } catch (e: RadioLinkException) {
                    // A failed read ends this drain. If the link is genuinely gone
                    // the disconnect callback has already closed the queue; if it
                    // was transient the next notification will trigger another.
                    diagnostics("meshtastic-ble: drain read failed: ${e.message}")
                    return@collect
                }
                // An empty read is the firmware's end-of-queue marker.
                if (packet.isEmpty()) return@collect
                queue.offer(packet)
            }
        }
    }

    override suspend fun send(frame: ByteArray) {
        val gatt = connection ?: throw RadioLinkException("Meshtastic link is not open")
        val toRadio = gatt.characteristic(SERVICE_UUID, TO_RADIO_UUID)
            ?: throw RadioLinkException("The radio does not expose the toRadio characteristic")
        gatt.write(toRadio, frame)
        // The reply to a write is queued for reading, not notified.
        drainTrigger.tryEmit(Unit)
    }

    override suspend fun readLinkRssi(): Int? = connection?.readRssi()

    override suspend fun close() {
        drainJob?.cancel()
        drainJob = null
        connection?.close()
        connection = null
        linkStateHolder.set(LinkState.CLOSED)
        queue.close()
    }

    /** Releases the transport's scope. Only for a full teardown. */
    fun shutdown() {
        scope.cancel()
    }

    private companion object {
        val SERVICE_UUID: UUID = UUID.fromString(MeshtasticProtocol.SERVICE_UUID)
        val TO_RADIO_UUID: UUID = UUID.fromString(MeshtasticProtocol.TORADIO_CHARACTERISTIC_UUID)
        val FROM_RADIO_UUID: UUID = UUID.fromString(MeshtasticProtocol.FROMRADIO_CHARACTERISTIC_UUID)
        val FROM_NUM_UUID: UUID = UUID.fromString(MeshtasticProtocol.FROMNUM_CHARACTERISTIC_UUID)

        /** ATT opcode plus handle: the three bytes an MTU cannot carry as payload. */
        const val ATT_HEADER_BYTES = 3
    }
}
