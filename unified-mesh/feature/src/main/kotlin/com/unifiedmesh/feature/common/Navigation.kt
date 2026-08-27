package com.unifiedmesh.feature.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The app's destinations.
 *
 * Five bottom-bar destinations, with diagnostics reached from Settings rather
 * than given a sixth tab — an operator in the field needs Messages, Nodes, Map,
 * Radios and Settings, and a developer log competing with them would be clutter.
 */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    MESSAGES("messages", "Messages", Icons.Filled.Chat),
    NODES("nodes", "Nodes", Icons.Filled.People),
    MAP("map", "Map", Icons.Filled.Map),
    RADIOS("radios", "Radios", Icons.Filled.SettingsInputAntenna),
    SETTINGS("settings", "Settings", Icons.Filled.Settings),
}

object Routes {
    const val CONVERSATION = "conversation/{conversationId}"
    const val DIAGNOSTICS = "diagnostics"
    const val BRIDGE = "bridge"
    const val SCAN = "scan/{protocol}"

    /**
     * Conversation ids carry a protocol prefix and, for channels, an operator
     * chosen name — either can contain a character that would otherwise end the
     * path segment, so the id is percent-encoded.
     *
     * `Uri.encode` rather than `URLEncoder`: Navigation decodes path arguments
     * with `Uri.decode`, which does not treat `+` as a space. Pairing it with
     * form encoding would turn a literal `+` in a channel name into a space and
     * open the wrong thread.
     */
    fun conversation(conversationId: String): String =
        "conversation/${android.net.Uri.encode(conversationId)}"

    fun scan(protocol: com.unifiedmesh.core.model.MeshProtocol): String = "scan/${protocol.name}"

    const val ARG_CONVERSATION_ID = "conversationId"
    const val ARG_PROTOCOL = "protocol"
}
