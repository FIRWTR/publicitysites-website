package com.unifiedmesh.app

import android.app.Application
import com.unifiedmesh.app.notification.MeshNotifier
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point.
 *
 * Deliberately thin: it creates the notification channels and nothing else. The
 * radios are not started here — attaching to Bluetooth devices is something the
 * operator asks for, either by opening the app or by having enabled background
 * operation, and doing it from [onCreate] would claim the radios every time the
 * process is spawned for any reason.
 */
@HiltAndroidApp
class UnifiedMeshApplication : Application() {

    @Inject lateinit var notifier: MeshNotifier

    override fun onCreate() {
        super.onCreate()
        notifier.createChannels()
    }
}
