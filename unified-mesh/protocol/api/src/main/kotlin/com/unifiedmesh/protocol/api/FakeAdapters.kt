package com.unifiedmesh.protocol.api

import com.unifiedmesh.core.model.Clock
import com.unifiedmesh.core.model.MeshChannel
import com.unifiedmesh.core.model.MeshNode
import com.unifiedmesh.core.model.MeshProtocol
import com.unifiedmesh.core.model.NodePosition
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext

/**
 * Fake Meshtastic radio.
 *
 * Node ids follow the real firmware's `User.id` convention (`!` + 8 hex digits)
 * so that anything downstream which parses ids sees realistic input.
 */
class FakeMeshtasticAdapter(
    clock: Clock = Clock.System,
    chatterIntervalMillis: Long? = null,
    /** Test seam: lets a test drive the adapter on its own scheduler. */
    parentDispatcherOverride: CoroutineContext = Dispatchers.Default,
) : FakeMeshRadioAdapter(
    parentDispatcher = parentDispatcherOverride,
    protocol = MeshProtocol.MESHTASTIC,
    clock = clock,
    deviceModel = "T-Deck",
    firmwareVersion = "2.7.4",
    seedNodes = listOf(
        MeshNode(
            protocol = MeshProtocol.MESHTASTIC,
            id = "!7c3f11a2",
            longName = "Phone Node",
            shortName = "PHON",
            lastHeard = null,
            isSelf = true,
        ),
        MeshNode(
            protocol = MeshProtocol.MESHTASTIC,
            id = "!a1b2c3d4",
            longName = "Bear",
            shortName = "BEAR",
            lastHeard = null,
            position = NodePosition(44.4280, -110.5885, altitudeMeters = 2360),
            batteryLevel = 78,
            snr = 6.25f,
            hopsAway = 1,
        ),
        MeshNode(
            protocol = MeshProtocol.MESHTASTIC,
            id = "!55aa77bb",
            longName = "Sarah",
            shortName = "SRAH",
            lastHeard = null,
            batteryLevel = 64,
            snr = 2.0f,
            hopsAway = 2,
        ),
        MeshNode(
            protocol = MeshProtocol.MESHTASTIC,
            id = "!0badf00d",
            longName = "Base Camp",
            shortName = "BASE",
            lastHeard = null,
            position = NodePosition(44.4605, -110.8281),
            hopsAway = 1,
        ),
        MeshNode(
            protocol = MeshProtocol.MESHTASTIC,
            id = "!feed1234",
            longName = "Ridge Repeater",
            shortName = "RIDG",
            lastHeard = null,
            hopsAway = 3,
        ),
    ),
    seedChannels = listOf(
        MeshChannel(MeshProtocol.MESHTASTIC, id = "0", name = "LongFast", index = 0, isPrimary = true),
        MeshChannel(MeshProtocol.MESHTASTIC, id = "1", name = "Emergency", index = 1),
    ),
    chatterIntervalMillis = chatterIntervalMillis,
    chatterScript = listOf(
        FakeIncoming("!a1b2c3d4", "Bear", "We are heading back to camp.", channelId = "0", snr = 6.25f, rssi = -84),
        FakeIncoming("!0badf00d", "Base Camp", "Copy that, kettle is on.", channelId = "0", snr = 4.0f, rssi = -91),
        FakeIncoming("!55aa77bb", "Sarah", "Anyone have eyes on the ridge trail?", channelId = "0", snr = 1.5f),
    ),
)

/**
 * Fake MeshCore radio.
 *
 * Contact ids are 6-byte public-key prefixes in lowercase hex, matching the
 * companion protocol's `RESP_CODE_CONTACT_MSG_RECV_V3` frame.
 */
class FakeMeshCoreAdapter(
    clock: Clock = Clock.System,
    chatterIntervalMillis: Long? = null,
    /** Test seam: lets a test drive the adapter on its own scheduler. */
    parentDispatcherOverride: CoroutineContext = Dispatchers.Default,
) : FakeMeshRadioAdapter(
    parentDispatcher = parentDispatcherOverride,
    protocol = MeshProtocol.MESHCORE,
    clock = clock,
    deviceModel = "T1000-E",
    firmwareVersion = "v1.9.0",
    seedNodes = listOf(
        MeshNode(
            protocol = MeshProtocol.MESHCORE,
            id = "9f21c40ab7d3",
            longName = "Phone Companion",
            shortName = null,
            lastHeard = null,
            isSelf = true,
        ),
        MeshNode(
            protocol = MeshProtocol.MESHCORE,
            id = "1a2b3c4d5e6f",
            longName = "Elliott",
            shortName = null,
            lastHeard = null,
            snr = 5.5f,
        ),
        MeshNode(
            protocol = MeshProtocol.MESHCORE,
            id = "aabbccddeeff",
            longName = "North Ridge",
            shortName = null,
            lastHeard = null,
            position = NodePosition(44.5019, -110.7961),
        ),
        MeshNode(
            protocol = MeshProtocol.MESHCORE,
            id = "0011223344ff",
            longName = "Camp",
            shortName = null,
            lastHeard = null,
            position = NodePosition(44.4270, -110.5880),
        ),
        MeshNode(
            protocol = MeshProtocol.MESHCORE,
            id = "778899aabbcc",
            longName = "Truck",
            shortName = null,
            lastHeard = null,
        ),
    ),
    seedChannels = listOf(
        MeshChannel(MeshProtocol.MESHCORE, id = "0", name = "Public", index = 0, isPrimary = true),
        MeshChannel(MeshProtocol.MESHCORE, id = "1", name = "Emergency", index = 1),
    ),
    chatterIntervalMillis = chatterIntervalMillis,
    chatterScript = listOf(
        FakeIncoming("1a2b3c4d5e6f", "Elliott", "Copy. See you soon.", channelId = "0", snr = 5.5f),
        FakeIncoming("aabbccddeeff", "North Ridge", "Wind picking up on the ridge.", channelId = "0", snr = 3.25f),
        FakeIncoming("778899aabbcc", "Truck", "Fuel stop, back in 20.", channelId = "0"),
    ),
)
