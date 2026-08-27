package com.unifiedmesh.app.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.unifiedmesh.app.notification.MeshNotifier
import com.unifiedmesh.core.database.DiagnosticsRepository
import com.unifiedmesh.core.database.MessageRepository
import com.unifiedmesh.core.database.SettingsRepository
import com.unifiedmesh.core.model.DiagnosticCategory
import com.unifiedmesh.core.radio.RadioCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps both radios attached while the app is in the background.
 *
 * ### Why a foreground service
 *
 * The whole point of a mesh handheld is that messages arrive when the phone is
 * in a pocket. A bound service or a WorkManager job would be killed within
 * minutes; `connectedDevice` is the foreground service type Android provides for
 * exactly this, and the ongoing notification is the honest price of it.
 *
 * The service owns nothing itself — the coordinator and both sessions are
 * application-scoped singletons — so its own lifecycle is not what keeps the
 * radios alive. Its job is to hold the process up and keep the status
 * notification honest.
 */
@AndroidEntryPoint
class RadioService : LifecycleService() {

    @Inject lateinit var coordinator: RadioCoordinator

    @Inject lateinit var settings: SettingsRepository

    @Inject lateinit var messages: MessageRepository

    @Inject lateinit var notifier: MeshNotifier

    @Inject lateinit var diagnostics: DiagnosticsRepository

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        coordinator.start()

        // Start in the foreground immediately: Android gives a service a few
        // seconds to call startForeground before it kills the process.
        ServiceCompat.startForeground(
            this,
            MeshNotifier.STATUS_NOTIFICATION_ID,
            notifier.statusNotification(coordinator.connectionStates.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )

        observeConnectionStates()
        observeNodeUpdates()
        observeChannelNames()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CONNECT_ASSIGNED -> connectAssignedRadios()
            ACTION_DISCONNECT_ALL -> disconnectAll()
        }
        // START_STICKY: if Android reclaims the process under memory pressure,
        // bring the service back and let it reconnect. A field radio link should
        // not stay down because the phone was briefly busy.
        return Service.START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun connectAssignedRadios() {
        lifecycleScope.launch {
            settings.assignmentsToAutoConnect().forEach { (protocol, device) ->
                val session = coordinator.session(protocol)
                if (session.state.value.isConnected) return@forEach
                diagnostics.info(
                    DiagnosticCategory.CONNECTION,
                    "auto-connecting to ${device.displayName}",
                    protocol,
                )
                // Each slot connects in its own coroutine: a radio that is out of
                // range must not delay the other one attaching.
                launch { runCatching { session.connect(device) } }
            }
        }
    }

    private fun disconnectAll() {
        lifecycleScope.launch {
            listOf(coordinator.meshtastic, coordinator.meshCore).forEach { session ->
                launch { runCatching { session.disconnect() } }
            }
            stopSelf()
        }
    }

    private fun observeConnectionStates() {
        lifecycleScope.launch {
            coordinator.connectionStates.collectLatest { states ->
                notifier.updateStatus(states)
            }
        }
    }

    /** Mirrors each radio's node list into the database for the Nodes and Map screens. */
    private fun observeNodeUpdates() {
        listOf(coordinator.meshtastic, coordinator.meshCore).forEach { session ->
            lifecycleScope.launch {
                session.nodes.collectLatest { nodes -> messages.saveNodes(nodes) }
            }
        }
    }

    /** Keeps channel conversations named the way the radio names them. */
    private fun observeChannelNames() {
        listOf(coordinator.meshtastic, coordinator.meshCore).forEach { session ->
            lifecycleScope.launch {
                session.channels.collectLatest { channels ->
                    messages.applyChannelNames(session.protocol, channels)
                }
            }
        }
    }

    companion object {
        const val ACTION_CONNECT_ASSIGNED = "com.unifiedmesh.app.CONNECT_ASSIGNED"
        const val ACTION_DISCONNECT_ALL = "com.unifiedmesh.app.DISCONNECT_ALL"

        /** Starts the service and asks it to attach to whatever radios are assigned. */
        fun start(context: Context) {
            val intent = Intent(context, RadioService::class.java).setAction(ACTION_CONNECT_ASSIGNED)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RadioService::class.java).setAction(ACTION_DISCONNECT_ALL)
            context.startService(intent)
        }
    }
}

/**
 * Re-attaches to the radios after a reboot.
 *
 * Only when the operator has asked for background operation: a mesh app that
 * silently claims a Bluetooth connection after every reboot would be a poor
 * citizen on a phone that is not currently in the field.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    /**
     * Injected rather than fetched through an entry point: a receiver is
     * short-lived, and reading the setting has to finish before
     * [BroadcastReceiver.PendingResult.finish].
     */
    @Inject lateinit var settings: SettingsRepository

    @Inject
    @com.unifiedmesh.app.di.ApplicationScope
    lateinit var scope: kotlinx.coroutines.CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        scope.launch {
            try {
                if (settings.general.first().backgroundOperationEnabled) {
                    RadioService.start(appContext)
                }
            } finally {
                // Always release the wake lock the system took for this
                // broadcast, even if reading settings failed.
                pendingResult.finish()
            }
        }
    }
}
