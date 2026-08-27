package com.unifiedmesh.feature.common

import androidx.lifecycle.ViewModel
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.RadioConnectionState
import com.unifiedmesh.core.radio.RadioCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * State for the app shell.
 *
 * Only the connection indicators: everything else belongs to a screen. Keeping
 * this minimal means the app bar never re-composes because a message arrived.
 */
@HiltViewModel
class AppShellViewModel @Inject constructor(
    coordinator: RadioCoordinator,
) : ViewModel() {

    val connectionStates: StateFlow<Map<MeshProtocol, RadioConnectionState>> =
        coordinator.connectionStates
}
