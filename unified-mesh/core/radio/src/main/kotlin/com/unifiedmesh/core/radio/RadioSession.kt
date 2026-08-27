package com.unifiedmesh.core.radio

import com.unifiedmesh.core.model.Clock
import com.unifiedmesh.core.model.MeshChannel
import com.unifiedmesh.core.model.MeshNode
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.OutgoingMessage
import com.unifiedmesh.core.model.RadioConnectionState
import com.unifiedmesh.core.model.RadioDevice
import com.unifiedmesh.core.model.RadioDeviceInfo
import com.unifiedmesh.core.model.SendResult
import com.unifiedmesh.core.model.UnifiedMessage
import com.unifiedmesh.protocol.api.DeliveryUpdate
import com.unifiedmesh.protocol.api.MeshRadioAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.math.min
import kotlin.random.Random

/** How long to wait before retry number [attempt] (1-based). */
fun interface ReconnectPolicy {
    fun delayMillisFor(attempt: Int): Long

    companion object {
        /**
         * Exponential backoff with jitter, capped at [maxDelayMillis].
         *
         * The cap matters as much as the growth: a radio that has been off for an
         * hour must still be picked up within a minute of being switched back on,
         * so the delay stops doubling at [maxDelayMillis] rather than growing
         * without bound. The jitter keeps the two radios' retries from lining up
         * and fighting over the Bluetooth radio.
         */
        fun exponential(
            baseDelayMillis: Long = 1_000,
            maxDelayMillis: Long = 60_000,
            jitterFraction: Double = 0.25,
            random: Random = Random.Default,
        ) = ReconnectPolicy { attempt ->
            val exponent = (attempt - 1).coerceIn(0, MAX_EXPONENT)
            val base = min(baseDelayMillis shl exponent, maxDelayMillis)
            val jitter = (base * jitterFraction * random.nextDouble()).toLong()
            base - (jitter / 2) + jitter
        }

        /** 2^20 * 1s already exceeds any sane cap; this only stops the shift overflowing. */
        private const val MAX_EXPONENT = 20
    }
}

/**
 * One radio slot: the Meshtastic radio, or the MeshCore radio.
 *
 * A session owns exactly one adapter and one coroutine scope, and knows nothing
 * about the other session. That is the structural reason a drop on one radio
 * cannot disturb the other — there is no shared object between them to disturb.
 *
 * The session adds what an adapter deliberately does not have: a remembered
 * device, and automatic reconnection with backoff.
 */
class RadioSession(
    val protocol: MeshProtocol,
    private val adapterFactory: (RadioDevice) -> MeshRadioAdapter,
    private val clock: Clock = Clock.System,
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy.exponential(),
    dispatcher: CoroutineContext = kotlinx.coroutines.Dispatchers.Default,
    private val diagnostics: (String) -> Unit = {},
    /**
     * Whether a dropped link should be retried automatically.
     *
     * Read at the moment of each drop rather than captured at construction, so
     * turning "keep radios connected" off in Settings takes effect on the next
     * drop instead of on the next app start. Turning it off never disconnects a
     * healthy link — it only stops this session chasing one that has gone.
     */
    private val autoReconnectEnabled: () -> Boolean = { true },
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow<RadioConnectionState>(RadioConnectionState.Disconnected)

    /**
     * The slot's connection state.
     *
     * This is the session's own view, not the adapter's: it stays in
     * [RadioConnectionState.Reconnecting] across the gap between a drop and the
     * next attempt, which is the state the UI needs to show.
     */
    val state: StateFlow<RadioConnectionState> = _state.asStateFlow()

    private val _incoming = MutableSharedFlow<UnifiedMessage>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val incomingMessages: Flow<UnifiedMessage> = _incoming.asSharedFlow()

    private val _deliveryUpdates = MutableSharedFlow<DeliveryUpdate>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val deliveryUpdates: Flow<DeliveryUpdate> = _deliveryUpdates.asSharedFlow()

    private val _nodes = MutableStateFlow<List<MeshNode>>(emptyList())
    val nodes: StateFlow<List<MeshNode>> = _nodes.asStateFlow()

    private val _channels = MutableStateFlow<List<MeshChannel>>(emptyList())
    val channels: StateFlow<List<MeshChannel>> = _channels.asStateFlow()

    private val _deviceInfo = MutableStateFlow<RadioDeviceInfo?>(null)
    val deviceInfo: StateFlow<RadioDeviceInfo?> = _deviceInfo.asStateFlow()

    /** The device this slot is assigned to, or null when nothing is assigned. */
    @Volatile
    var assignedDevice: RadioDevice? = null
        private set

    private var adapter: MeshRadioAdapter? = null
    private var mirrorJob: Job? = null
    private var superviseJob: Job? = null

    /** Set while the operator has deliberately disconnected: suppresses auto-reconnect. */
    @Volatile
    private var stoppedByUser = false

    /** This radio's own node id on its mesh, once known. */
    val selfNodeId: String? get() = _deviceInfo.value?.nodeId

    /** Assigns [device] to this slot and connects. */
    suspend fun connect(device: RadioDevice) {
        stoppedByUser = false
        assignedDevice = device
        teardownAdapter()
        attemptConnect(device, attempt = 0)
    }

    /** Reconnects to the already-assigned device. */
    suspend fun reconnect() {
        val device = assignedDevice ?: return
        connect(device)
    }

    /** Disconnects and stops trying. */
    suspend fun disconnect() {
        stoppedByUser = true
        teardownAdapter()
        _state.value = RadioConnectionState.Disconnected
    }

    /** Forgets the assigned device entirely. */
    suspend fun clearAssignment() {
        disconnect()
        assignedDevice = null
    }

    private suspend fun attemptConnect(device: RadioDevice, attempt: Int) {
        val adapter = adapterFactory(device)
        this.adapter = adapter
        mirrorJob = scope.launch { mirror(adapter) }

        _state.value = RadioConnectionState.Connecting
        adapter.connect(device)

        when (val adapterState = adapter.connectionState.value) {
            is RadioConnectionState.Connected -> {
                diagnostics("${protocol.displayName}: connected to ${device.displayName}")
                _state.value = adapterState
                superviseJob?.cancel()
                superviseJob = scope.launch { supervise(adapter, device) }
            }

            else -> {
                val reason = (adapterState as? RadioConnectionState.Error)?.reason ?: "Connection failed"
                diagnostics("${protocol.displayName}: $reason")
                scheduleReconnect(device, attempt + 1, reason)
            }
        }
    }

    /**
     * Watches the adapter for a drop and starts the reconnect cycle.
     *
     * Only the *session* decides to retry: an adapter reports what happened and
     * stops there, so that a slot the operator has disconnected never quietly
     * reconnects itself.
     */
    private suspend fun supervise(adapter: MeshRadioAdapter, device: RadioDevice) {
        adapter.connectionState.collect { adapterState ->
            when (adapterState) {
                is RadioConnectionState.Connected -> _state.value = adapterState

                is RadioConnectionState.Error -> {
                    if (stoppedByUser) return@collect
                    if (!adapterState.recoverable) {
                        _state.value = adapterState
                        return@collect
                    }
                    scheduleReconnect(device, attempt = 1, reason = adapterState.reason)
                }

                RadioConnectionState.Disconnected -> {
                    if (stoppedByUser) {
                        _state.value = RadioConnectionState.Disconnected
                    } else {
                        scheduleReconnect(device, attempt = 1, reason = "Link dropped")
                    }
                }

                else -> Unit
            }
        }
    }

    private fun scheduleReconnect(device: RadioDevice, attempt: Int, reason: String) {
        if (stoppedByUser) return
        if (!autoReconnectEnabled()) {
            // The operator has asked us not to chase dropped links. Report the
            // drop and stop, rather than silently retrying in the background.
            diagnostics("${protocol.displayName}: $reason; automatic reconnect is off")
            _state.value = RadioConnectionState.Disconnected
            return
        }
        superviseJob?.cancel()
        superviseJob = scope.launch {
            var currentAttempt = attempt
            while (isActive && !stoppedByUser) {
                val wait = reconnectPolicy.delayMillisFor(currentAttempt)
                _state.value = RadioConnectionState.Reconnecting(
                    attempt = currentAttempt,
                    nextAttemptAtMillis = clock.nowMillis() + wait,
                )
                diagnostics("${protocol.displayName}: $reason; retry $currentAttempt in ${wait}ms")
                delay(wait)
                if (stoppedByUser) return@launch

                teardownAdapter(resetState = false)
                try {
                    attemptConnect(device, attempt = currentAttempt)
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A failed attempt is ordinary on a mesh radio that has been
                    // switched off. Keep the slot in Reconnecting and try again.
                    diagnostics("${protocol.displayName}: retry $currentAttempt failed: ${e.message}")
                }
                currentAttempt++
            }
        }
    }

    /** Republishes the adapter's flows as the session's own. */
    private suspend fun mirror(adapter: MeshRadioAdapter) {
        scope.launch { adapter.incomingMessages.collect { _incoming.emit(it) } }
        scope.launch { adapter.deliveryUpdates.collect { _deliveryUpdates.emit(it) } }
        scope.launch { adapter.nodes.collect { _nodes.value = it } }
        scope.launch { adapter.channels.collect { _channels.value = it } }
        scope.launch { adapter.deviceInfo.collect { _deviceInfo.value = it } }
    }

    /** Sends through this slot's adapter. */
    suspend fun send(message: OutgoingMessage): SendResult {
        val adapter = adapter
            ?: return SendResult.Failed("No ${protocol.displayName} radio is connected", retryable = true)
        return try {
            adapter.sendMessage(message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failure here belongs to this slot alone; it must never escape into
            // the caller's scope, which is shared with the other radio.
            diagnostics("${protocol.displayName}: send failed: ${e.message}")
            SendResult.Failed("${protocol.displayName} send failed: ${e.message}", retryable = true)
        }
    }

    /** Refreshes device information from the radio. */
    suspend fun refreshDeviceInfo(): RadioDeviceInfo? = runCatching { adapter?.getDeviceInfo() }.getOrNull()

    private suspend fun teardownAdapter(resetState: Boolean = true) {
        mirrorJob?.cancel()
        mirrorJob = null
        adapter?.let { runCatching { it.disconnect() } }
        adapter = null
        if (resetState) {
            superviseJob?.cancel()
            superviseJob = null
            _nodes.value = emptyList()
            _channels.value = emptyList()
            _deviceInfo.value = null
        }
    }

    /** Releases the session's scope. Only for a full teardown of the app's radio stack. */
    fun shutdown() {
        scope.cancel()
    }
}
