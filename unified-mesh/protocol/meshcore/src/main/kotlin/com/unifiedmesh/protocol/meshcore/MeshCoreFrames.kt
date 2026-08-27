package com.unifiedmesh.protocol.meshcore

/**
 * Decoded inbound frames.
 *
 * Field order and widths mirror the frame builders in upstream
 * `examples/companion_radio/MyMesh.cpp`; each subclass names the function it
 * came from so the two can be diffed when the firmware moves.
 */
sealed interface MeshCoreFrame {

    /** `RESP_CODE_OK`, optionally carrying a 32-bit LE value. */
    data class Ok(val value: Long?) : MeshCoreFrame

    /** `RESP_CODE_ERR` — `writeErrFrame`. */
    data class Error(val code: Int) : MeshCoreFrame {
        val description: String get() = MeshCoreProtocol.errorMessage(code)
    }

    /** `RESP_CODE_DISABLED` — the firmware was built without that feature. */
    data object Disabled : MeshCoreFrame

    /** `RESP_CODE_SELF_INFO` — reply to `CMD_APP_START`. */
    data class SelfInfo(
        val advertType: Int,
        val txPowerDbm: Int,
        val maxTxPowerDbm: Int,
        val publicKey: ByteArray,
        val latitude: Double?,
        val longitude: Double?,
        val multiAcks: Int,
        val advertLocPolicy: Int,
        val telemetryModeBits: Int,
        val manualAddContacts: Int,
        val frequencyKhz: Long,
        val bandwidthKhz: Long,
        val spreadingFactor: Int,
        val codingRate: Int,
        val nodeName: String,
    ) : MeshCoreFrame {
        /** The 6-byte public-key prefix, lowercase hex — this radio's identity. */
        val nodeId: String get() = publicKey.toHex(MeshCoreProtocol.PUB_KEY_PREFIX_SIZE)

        override fun equals(other: Any?): Boolean =
            this === other || (other is SelfInfo && nodeId == other.nodeId && nodeName == other.nodeName)

        override fun hashCode(): Int = 31 * nodeId.hashCode() + nodeName.hashCode()
    }

    /** `RESP_CODE_DEVICE_INFO` — reply to `CMD_DEVICE_QUERY`. */
    data class DeviceInfo(
        val firmwareVersionCode: Int,
        val maxContacts: Int,
        val maxChannels: Int,
        val blePin: Long,
        val firmwareBuildDate: String,
        val manufacturerModel: String,
        val firmwareVersion: String,
        val clientRepeatEnabled: Boolean?,
        val pathHashMode: Int?,
    ) : MeshCoreFrame

    /** `RESP_CODE_CONTACTS_START` — total contact count, not the filtered count. */
    data class ContactsStart(val totalContacts: Long) : MeshCoreFrame

    /** `RESP_CODE_CONTACT` — `writeContactRespFrame`. */
    data class Contact(
        val publicKey: ByteArray,
        val type: Int,
        val flags: Int,
        val outPathLen: Int,
        val name: String,
        val lastAdvertTimestamp: Long,
        val latitude: Double?,
        val longitude: Double?,
        val lastMod: Long,
    ) : MeshCoreFrame {
        val id: String get() = publicKey.toHex(MeshCoreProtocol.PUB_KEY_PREFIX_SIZE)

        override fun equals(other: Any?): Boolean =
            this === other || (other is Contact && id == other.id && lastMod == other.lastMod)

        override fun hashCode(): Int = 31 * id.hashCode() + lastMod.hashCode()
    }

    /** `RESP_CODE_END_OF_CONTACTS` — carries the newest `lastmod` seen, for incremental sync. */
    data class EndOfContacts(val mostRecentLastMod: Long) : MeshCoreFrame

    /** `RESP_CODE_SENT` — the radio put the message on the air. */
    data class Sent(val routeFlood: Boolean, val expectedAck: Long, val estimatedTimeoutMillis: Long) : MeshCoreFrame

    /** `RESP_CODE_CONTACT_MSG_RECV` / `_V3` — a direct message. */
    data class ContactMessage(
        val senderPrefix: String,
        val pathLen: Int,
        val textType: Int,
        val senderTimestamp: Long,
        val text: String,
        val snr: Float?,
        /** Present for `TXT_TYPE_SIGNED_PLAIN`: the 4-byte sender signature prefix. */
        val signature: String?,
    ) : MeshCoreFrame {
        /** 0xFF on receive means the packet arrived by a direct (non-flood) route. */
        val wasDirect: Boolean get() = pathLen == MeshCoreProtocol.PATH_UNKNOWN
    }

    /** `RESP_CODE_CHANNEL_MSG_RECV` / `_V3` — a group-channel message. */
    data class ChannelMessage(
        val channelIndex: Int,
        val pathLen: Int,
        val textType: Int,
        val senderTimestamp: Long,
        val text: String,
        val snr: Float?,
    ) : MeshCoreFrame

    /** `RESP_CODE_NO_MORE_MESSAGES` — the offline queue is drained. */
    data object NoMoreMessages : MeshCoreFrame

    /** `RESP_CODE_BATT_AND_STORAGE`. */
    data class BatteryAndStorage(
        val batteryMilliVolts: Int,
        val storageUsedKb: Long,
        val storageTotalKb: Long,
    ) : MeshCoreFrame

    /** `RESP_CODE_CURR_TIME` — reply to `CMD_GET_DEVICE_TIME`, epoch seconds. */
    data class CurrentTime(val epochSeconds: Long) : MeshCoreFrame

    /** `RESP_CODE_CHANNEL_INFO` — reply to `CMD_GET_CHANNEL`. The secret is not exposed. */
    data class ChannelInfo(val index: Int, val name: String) : MeshCoreFrame

    /** `PUSH_CODE_MSG_WAITING` — poll `CMD_SYNC_NEXT_MESSAGE` until [NoMoreMessages]. */
    data object MessagesWaiting : MeshCoreFrame

    /** `PUSH_CODE_SEND_CONFIRMED` — a previously sent message was acknowledged. */
    data class SendConfirmed(val ackCode: Long, val roundTripMillis: Long?) : MeshCoreFrame

    /**
     * `PUSH_CODE_ADVERT` — a known contact re-advertised.
     *
     * The firmware sends the advertiser's full 32-byte public key here; only the
     * prefix is kept, because that is what identifies a contact everywhere else.
     */
    data class AdvertReceived(val nodeId: String) : MeshCoreFrame

    /**
     * `PUSH_CODE_NEW_ADVERT` — a contact the radio had not seen before.
     *
     * The firmware builds this with `writeContactRespFrame`, so the body is byte
     * for byte a [Contact] frame and is decoded as one.
     */
    data class NewContact(val contact: Contact) : MeshCoreFrame

    /** `PUSH_CODE_PATH_UPDATED`. */
    data object PathUpdated : MeshCoreFrame

    /**
     * A frame this client does not model.
     *
     * Kept rather than dropped so the diagnostics screen can show that something
     * arrived, without this client having to understand it.
     */
    data class Unhandled(val code: Int, val length: Int) : MeshCoreFrame
}

internal fun ByteArray.toHex(length: Int = size): String {
    val n = minOf(length, size)
    val out = StringBuilder(n * 2)
    for (i in 0 until n) {
        val v = this[i].toInt() and 0xFF
        out.append(HEX[v shr 4]).append(HEX[v and 0x0F])
    }
    return out.toString()
}

private val HEX = "0123456789abcdef".toCharArray()
