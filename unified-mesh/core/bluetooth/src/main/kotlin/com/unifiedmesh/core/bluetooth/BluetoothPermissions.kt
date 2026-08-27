package com.unifiedmesh.core.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One Bluetooth permission the app needs, with the sentence the UI shows to
 * explain it.
 *
 * The rationale strings live here rather than in the UI so that the reason a
 * permission is requested stays next to the decision to request it.
 */
data class BluetoothPermission(
    val manifestPermission: String,
    val title: String,
    val rationale: String,
)

/**
 * Works out which Bluetooth permissions this device actually needs.
 *
 * Android 12 (API 31) replaced the old "Bluetooth implies location" model with
 * `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`. The app declares `neverForLocation`
 * on the scan permission — it looks for known mesh radios and never derives the
 * user's position from scan results — so on API 31+ no location permission is
 * requested at all. On API 29–30 the platform has no such split and gates scan
 * results behind fine location, so it is requested there and only there.
 */
@Singleton
class BluetoothPermissions @Inject constructor() {

    /** The permissions this device needs, in the order they should be requested. */
    fun required(): List<BluetoothPermission> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            BluetoothPermission(
                Manifest.permission.BLUETOOTH_SCAN,
                title = "Find nearby devices",
                rationale = "Unified Mesh needs to scan for Bluetooth devices so you can pick which " +
                    "radio is your Meshtastic node and which is your MeshCore node. " +
                    "Scan results are not used to work out your location.",
            ),
            BluetoothPermission(
                Manifest.permission.BLUETOOTH_CONNECT,
                title = "Connect to your radios",
                rationale = "Unified Mesh needs to connect to your radios over Bluetooth to send and " +
                    "receive messages.",
            ),
        )
    } else {
        listOf(
            BluetoothPermission(
                Manifest.permission.ACCESS_FINE_LOCATION,
                title = "Find nearby devices",
                rationale = "On this version of Android, scanning for Bluetooth devices requires the " +
                    "location permission. Unified Mesh only uses it to discover your radios and never " +
                    "records where you are.",
            ),
        )
    }

    /** True when every required permission has been granted. */
    fun allGranted(context: Context): Boolean = missing(context).isEmpty()

    /** The subset of [required] that has not been granted yet. */
    fun missing(context: Context): List<BluetoothPermission> = required().filterNot {
        ContextCompat.checkSelfPermission(context, it.manifestPermission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Permission needed to post the incoming-message notifications.
     *
     * Separate from the Bluetooth set: the radios work without it, the user just
     * does not get told about new messages. Asking for it alongside the Bluetooth
     * permissions would make the first-run flow look far more demanding than it is.
     */
    fun notificationPermission(): BluetoothPermission? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            BluetoothPermission(
                Manifest.permission.POST_NOTIFICATIONS,
                title = "Show new messages",
                rationale = "Allow notifications so Unified Mesh can tell you when a message arrives " +
                    "while the app is in the background.",
            )
        } else {
            null
        }
}
