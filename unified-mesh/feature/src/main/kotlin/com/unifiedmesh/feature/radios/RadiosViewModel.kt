package com.unifiedmesh.feature.radios

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unifiedmesh.core.bluetooth.BleScanner
import com.unifiedmesh.core.bluetooth.BluetoothPermission
import com.unifiedmesh.core.bluetooth.BluetoothPermissions
import com.unifiedmesh.core.bluetooth.ScannedDevice
import com.unifiedmesh.core.database.SettingsRepository
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.RadioConnectionState
import com.unifiedmesh.core.model.RadioDevice
import com.unifiedmesh.core.model.RadioDeviceInfo
import com.unifiedmesh.core.radio.RadioCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One radio slot as the Radios screen sees it. */
data class RadioSlotUi(
    val protocol: MeshProtocol,
    val assignedDevice: RadioDevice?,
    val state: RadioConnectionState,
    val deviceInfo: RadioDeviceInfo?,
) {
    val hasDevice: Boolean get() = assignedDevice != null
}

data class RadiosUiState(
    val slots: List<RadioSlotUi> = emptyList(),
    val missingPermissions: List<BluetoothPermission> = emptyList(),
    val scanning: Boolean = false,
    val scanResults: List<ScannedDevice> = emptyList(),
    val scanError: String? = null,
    val demoMode: Boolean = false,
)

@HiltViewModel
class RadiosViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coordinator: RadioCoordinator,
    private val settings: SettingsRepository,
    private val scanner: BleScanner,
    private val permissions: BluetoothPermissions,
) : ViewModel() {

    private val scanning = MutableStateFlow(false)
    private val scanResults = MutableStateFlow<List<ScannedDevice>>(emptyList())
    private val scanError = MutableStateFlow<String?>(null)
    private val permissionState = MutableStateFlow(permissions.missing(context))

    private var scanJob: kotlinx.coroutines.Job? = null

    val uiState: StateFlow<RadiosUiState> = combine(
        coordinator.meshtastic.state,
        coordinator.meshCore.state,
        combine(coordinator.meshtastic.deviceInfo, coordinator.meshCore.deviceInfo) { a, b -> a to b },
        settings.assignedRadios(),
        combine(
            scanning,
            scanResults,
            scanError,
            permissionState,
            settings.general,
        ) { isScanning, results, error, missing, general ->
            ScanState(
                scanning = isScanning,
                results = results,
                error = error,
                // Demo mode never touches Bluetooth, so asking for scan and
                // connect permissions would be dishonest.
                missingPermissions = if (general.demoMode) emptyList() else missing,
                demoMode = general.demoMode,
            )
        },
    ) { mtState, mcState, (mtInfo, mcInfo), assigned, scan ->
        RadiosUiState(
            slots = listOf(
                RadioSlotUi(MeshProtocol.MESHTASTIC, assigned[MeshProtocol.MESHTASTIC], mtState, mtInfo),
                RadioSlotUi(MeshProtocol.MESHCORE, assigned[MeshProtocol.MESHCORE], mcState, mcInfo),
            ),
            missingPermissions = scan.missingPermissions,
            scanning = scan.scanning,
            scanResults = scan.results,
            scanError = scan.error,
            demoMode = scan.demoMode,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RadiosUiState(),
    )

    private data class ScanState(
        val scanning: Boolean,
        val results: List<ScannedDevice>,
        val error: String?,
        val missingPermissions: List<BluetoothPermission>,
        val demoMode: Boolean,
    )

    /** Re-reads permission state; call after returning from a permission prompt. */
    fun refreshPermissions() {
        permissionState.value = permissions.missing(context)
    }

    fun startScan() {
        if (uiState.value.demoMode) {
            // Simulated radios still go through assign-then-connect, so demo mode
            // exercises the real screen rather than a special-cased shortcut.
            scanResults.value = DEMO_DEVICES
            scanError.value = null
            scanning.value = false
            return
        }
        if (permissions.missing(context).isNotEmpty()) {
            refreshPermissions()
            return
        }
        scanJob?.cancel()
        scanResults.value = emptyList()
        scanError.value = null
        scanning.value = true
        scanJob = viewModelScope.launch {
            scanner.scan()
                .catch { e -> scanError.value = e.message ?: "Scan failed" }
                .collect { results -> scanResults.value = results }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        scanning.value = false
    }

    /**
     * Assigns a discovered device to a slot and connects.
     *
     * The protocol comes from the operator's choice, never from the scan hint:
     * connecting a MeshCore radio as Meshtastic would write one protocol's
     * commands into the other's characteristics.
     */
    fun assign(protocol: MeshProtocol, device: RadioDevice) {
        viewModelScope.launch {
            stopScan()
            settings.assignRadio(protocol, device.copy(assignedProtocol = protocol))
            coordinator.session(protocol).connect(device.copy(assignedProtocol = protocol))
        }
    }

    fun connect(protocol: MeshProtocol) {
        viewModelScope.launch {
            val device = settings.assignmentsToAutoConnect().firstOrNull { it.first == protocol }?.second
                ?: coordinator.session(protocol).assignedDevice
                ?: return@launch
            coordinator.session(protocol).connect(device)
        }
    }

    fun disconnect(protocol: MeshProtocol) {
        viewModelScope.launch { coordinator.session(protocol).disconnect() }
    }

    fun reconnect(protocol: MeshProtocol) {
        viewModelScope.launch { coordinator.session(protocol).reconnect() }
    }

    /** Forgets the device in a slot, leaving the other slot untouched. */
    fun clearAssignment(protocol: MeshProtocol) {
        viewModelScope.launch {
            coordinator.session(protocol).clearAssignment()
            settings.clearAssignment(protocol)
        }
    }

    fun refreshDeviceInfo(protocol: MeshProtocol) {
        viewModelScope.launch { coordinator.session(protocol).refreshDeviceInfo() }
    }

    override fun onCleared() {
        scanJob?.cancel()
        super.onCleared()
    }

    private companion object {
        /**
         * The two devices offered in demo mode.
         *
         * Addresses are in the documentation-reserved range so they cannot
         * collide with a real adapter's MAC.
         */
        val DEMO_DEVICES = listOf(
            ScannedDevice(
                device = RadioDevice(address = "00:00:5E:00:53:01", name = "Demo T-Deck"),
                likelyProtocol = MeshProtocol.MESHTASTIC,
                hint = "Simulated Meshtastic radio",
            ),
            ScannedDevice(
                device = RadioDevice(address = "00:00:5E:00:53:02", name = "Demo T1000-E"),
                likelyProtocol = MeshProtocol.MESHCORE,
                hint = "Simulated MeshCore radio",
            ),
        )
    }
}
