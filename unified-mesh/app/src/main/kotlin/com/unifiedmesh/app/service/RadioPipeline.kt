package com.unifiedmesh.app.service

import com.unifiedmesh.app.di.ApplicationScope
import com.unifiedmesh.core.database.MessageRepository
import com.unifiedmesh.core.radio.RadioCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Consumes both radios into the database.
 *
 * ### Why this is not in [RadioService]
 *
 * It used to be, and that was wrong. The foreground service exists for exactly
 * one reason — to stop Android killing the process while the screen is off — and
 * the platform refuses to start it without `BLUETOOTH_CONNECT`. Hanging the
 * *data pipeline* off that same lifecycle meant that whenever the service could
 * not start, the radios still connected and still emitted traffic, but nothing
 * was listening: no messages saved, no nodes recorded, no channel names applied.
 * The app looked connected and behaved as if nothing was on the mesh.
 *
 * Demo mode made that plain. It needs no Bluetooth permission at all, so the
 * service never started, so the Messages, Nodes and Map screens were all empty
 * against simulated radios that were plainly connected.
 *
 * Consuming radio traffic needs no permission and no service — it is in-process
 * wiring between singletons. So it starts with the application and stays up for
 * the life of the process, and the service goes back to its one job.
 */
@Singleton
class RadioPipeline @Inject constructor(
    private val coordinator: RadioCoordinator,
    private val messages: MessageRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private var started = false

    /** Idempotent: safe to call from every process entry point. */
    fun start() {
        if (started) return
        started = true

        // Message persistence, delivery updates and the bridge.
        coordinator.start()

        listOf(coordinator.meshtastic, coordinator.meshCore).forEach { session ->
            // Mirrors each radio's node list for the Nodes and Map screens.
            scope.launch {
                session.nodes.collectLatest { nodes -> messages.saveNodes(nodes) }
            }
            // Keeps channel conversations named the way the radio names them.
            scope.launch {
                session.channels.collectLatest { channels ->
                    messages.applyChannelNames(session.protocol, channels)
                }
            }
        }
    }
}
