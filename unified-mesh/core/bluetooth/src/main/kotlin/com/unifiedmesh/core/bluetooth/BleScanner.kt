package com.unifiedmesh.core.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.RadioDevice
import com.unifiedmesh.protocol.meshcore.MeshCoreProtocol
import com.unifiedmesh.protocol.meshtastic.MeshtasticProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A discovered device, with the app's *guess* at what it is running.
 *
 * The guess is a sorting hint for the scan list and nothing more: the operator
 * always picks which device goes in which slot. Guessing wrong and connecting
 * anyway would mean writing MeshCore commands into a Meshtastic radio.
 */
data class ScannedDevice(
    val device: RadioDevice,
    /** Protocol suggested by the advertisement, or null when nothing matched. */
    val likelyProtocol: MeshProtocol?,
    /** Why the app thinks so, shown under the device name. */
    val hint: String,
)

/**
 * Bluetooth LE scanning.
 *
 * Both radios advertise a service UUID this app knows, so the scan filters on
 * them and offers everything else under "other devices" — some builds advertise
 * neither, and a filtered-only list would make those radios unusable.
 */
@Singleton
@SuppressLint("MissingPermission") // Callers gate on BluetoothPermissions.allGranted().
class BleScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Scans until the collector stops.
     *
     * Emits the accumulated result list on every change, so a UI can render it
     * directly. Devices are keyed by address, and a later sighting replaces an
     * earlier one so RSSI stays current.
     */
    fun scan(includeUnknownDevices: Boolean = true): Flow<List<ScannedDevice>> = callbackFlow {
        val manager = context.getSystemService(BluetoothManager::class.java)
        val scanner = manager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            // No adapter or Bluetooth is off. An empty list is the honest answer;
            // the UI shows its own "turn Bluetooth on" state.
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val found = LinkedHashMap<String, ScannedDevice>()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val scanned = result.toScannedDevice() ?: return
                if (scanned.likelyProtocol == null && !includeUnknownDevices) return
                found[scanned.device.address] = scanned
                trySend(found.values.sortedWith(SCAN_ORDER))
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                var changed = false
                results.forEach { result ->
                    val scanned = result.toScannedDevice() ?: return@forEach
                    if (scanned.likelyProtocol == null && !includeUnknownDevices) return@forEach
                    found[scanned.device.address] = scanned
                    changed = true
                }
                if (changed) trySend(found.values.sortedWith(SCAN_ORDER))
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleScanException(errorCode))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        // An empty filter list means "everything"; a non-empty one means only
        // matching advertisements, which would hide radios that advertise no
        // service UUID at all.
        val filters = if (includeUnknownDevices) emptyList() else KNOWN_SERVICE_FILTERS

        scanner.startScan(filters, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    private fun ScanResult.toScannedDevice(): ScannedDevice? {
        val address = device?.address ?: return null
        val name = runCatching { device.name }.getOrNull() ?: scanRecord?.deviceName
        val uuids = scanRecord?.serviceUuids?.map { it.uuid.toString().lowercase() }.orEmpty()

        val (protocol, hint) = when {
            uuids.contains(MeshtasticProtocol.SERVICE_UUID) ->
                MeshProtocol.MESHTASTIC to "Advertises the Meshtastic service"

            uuids.contains(MeshCoreProtocol.SERVICE_UUID) ->
                // Nordic UART is a generic service; plenty of non-MeshCore devices
                // advertise it, so this is explicitly a maybe.
                MeshProtocol.MESHCORE to "Advertises a Nordic UART service, which MeshCore uses"

            name != null && MESHTASTIC_NAME.matches(name) ->
                MeshProtocol.MESHTASTIC to "Name looks like a Meshtastic device"

            else -> null to "Unknown device"
        }

        return ScannedDevice(
            device = RadioDevice(
                address = address,
                name = name,
                advertisedServiceUuids = uuids,
                rssi = rssi,
            ),
            likelyProtocol = protocol,
            hint = hint,
        )
    }

    private companion object {
        val MESHTASTIC_NAME = Regex(MeshtasticProtocol.BLE_NAME_PATTERN)

        val KNOWN_SERVICE_FILTERS = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(UUID.fromString(MeshtasticProtocol.SERVICE_UUID)))
                .build(),
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(UUID.fromString(MeshCoreProtocol.SERVICE_UUID)))
                .build(),
        )

        /** Recognised devices first, then by signal strength: closest radios on top. */
        val SCAN_ORDER = compareBy<ScannedDevice>(
            { it.likelyProtocol == null },
            { -(it.device.rssi ?: Int.MIN_VALUE) },
        )
    }
}

/** Thrown when the platform refuses to start a scan. */
class BleScanException(val errorCode: Int) : Exception(describe(errorCode)) {
    companion object {
        fun describe(errorCode: Int): String = when (errorCode) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "A scan is already running"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "Android refused the scan registration"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "This device does not support this kind of scan"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "Bluetooth internal error"
            // Android throttles apps that start scans too often; the limit resets
            // after about 30 seconds.
            SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "Scanning was throttled. Wait about 30 seconds and try again."
            else -> "Scan failed (code $errorCode)"
        }

        private const val SCAN_FAILED_SCANNING_TOO_FREQUENTLY = 6
    }
}
