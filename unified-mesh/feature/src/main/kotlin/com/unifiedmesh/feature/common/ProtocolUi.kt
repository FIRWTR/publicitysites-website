package com.unifiedmesh.feature.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.RadioConnectionState

/**
 * Colours for the connection indicators.
 *
 * Fixed values rather than theme colours: green-means-up is the whole point, and
 * it has to read the same in light and dark. They are chosen to stay legible on
 * both surfaces, and every use is paired with text so the state is never carried
 * by colour alone.
 */
object ConnectionColors {
    val Connected = Color(0xFF2E9E5B)
    val Disconnected = Color(0xFF8A8F98)
    val Reconnecting = Color(0xFFD79A28)
    val Error = Color(0xFFCF3A3A)

    fun of(state: RadioConnectionState?): Color = when (state) {
        is RadioConnectionState.Connected -> Connected
        is RadioConnectionState.Reconnecting -> Reconnecting
        RadioConnectionState.Connecting, RadioConnectionState.Handshaking -> Reconnecting
        is RadioConnectionState.Error -> Error
        null, RadioConnectionState.Disconnected -> Disconnected
    }
}

/** Plain-language description of a connection state. */
fun RadioConnectionState?.describe(): String = when (this) {
    null, RadioConnectionState.Disconnected -> "Disconnected"
    RadioConnectionState.Connecting -> "Connecting…"
    RadioConnectionState.Handshaking -> "Starting up…"
    is RadioConnectionState.Connected -> "Connected"
    is RadioConnectionState.Reconnecting -> "Reconnecting… (attempt $attempt)"
    is RadioConnectionState.Error -> reason
}

/**
 * The `[MT]` / `[MC]` tag that identifies a message's network.
 *
 * Every message in the unified inbox carries one. Without it the inbox is a pile
 * of text with no way to tell which radio can actually reach the sender.
 */
@Composable
fun ProtocolTag(
    protocol: MeshProtocol,
    modifier: Modifier = Modifier,
) {
    val background = when (protocol) {
        MeshProtocol.MESHTASTIC -> MaterialTheme.colorScheme.primaryContainer
        MeshProtocol.MESHCORE -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val foreground = when (protocol) {
        MeshProtocol.MESHTASTIC -> MaterialTheme.colorScheme.onPrimaryContainer
        MeshProtocol.MESHCORE -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .padding(horizontal = 5.dp, vertical = 1.dp)
            .semantics { contentDescription = protocol.displayName },
    ) {
        Text(
            text = protocol.shortLabel,
            color = foreground,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/** A single status dot with its protocol label, for the app bar. */
@Composable
fun ConnectionDot(
    protocol: MeshProtocol,
    state: RadioConnectionState?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.semantics {
            contentDescription = "${protocol.displayName}: ${state.describe()}"
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(ConnectionColors.of(state)),
        )
        Text(
            text = protocol.shortLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The pair of indicators shown at the top of every screen. */
@Composable
fun ConnectionIndicators(
    states: Map<MeshProtocol, RadioConnectionState>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MeshProtocol.entries.forEach { protocol ->
            ConnectionDot(protocol, states[protocol])
        }
    }
}
