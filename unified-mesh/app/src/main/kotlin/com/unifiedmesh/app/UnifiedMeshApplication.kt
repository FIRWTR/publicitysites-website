package com.unifiedmesh.app

import android.app.Application
import com.unifiedmesh.app.notification.MeshNotifier
import com.unifiedmesh.app.service.RadioPipeline
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Creates the notification channels and starts the radio pipeline.
 *
 * Note the distinction. Starting the *pipeline* means being ready to record
 * whatever the radios produce; it opens no connection, needs no permission, and
 * costs nothing when both slots are idle. Starting the *radios* is a separate
 * act, and is still left to the operator — either by opening the app or by
 * having enabled background operation — because claiming two Bluetooth devices
 * on every process spawn would be rude.
 */
@HiltAndroidApp
class UnifiedMeshApplication : Application() {

    @Inject lateinit var notifier: MeshNotifier

    @Inject lateinit var pipeline: RadioPipeline

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
        // Before anything can connect, so no traffic is missed.
        pipeline.start()
    }
}
