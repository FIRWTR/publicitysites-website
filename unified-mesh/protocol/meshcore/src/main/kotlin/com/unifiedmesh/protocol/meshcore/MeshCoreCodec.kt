package com.unifiedmesh.protocol.meshcore

import com.unifiedmesh.protocol.meshcore.MeshCoreProtocol.MAX_FRAME_SIZE
import com.unifiedmesh.protocol.meshcore.MeshCoreProtocol.MAX_PATH_SIZE
import com.unifiedmesh.protocol.meshcore.MeshCoreProtocol.PUB_KEY_PREFIX_SIZE
import com.unifiedmesh.protocol.meshcore.MeshCoreProtocol.PUB_KEY_SIZE

/** Raised when a frame is malformed. The session logs it and keeps the link up. */
class MeshCoreFrameException(message: String) : Exception(message)

/**
 * Encodes command frames and decodes response/push frames.
 *
 * Multi-byte integers are little-endian and strings are UTF-8, per the companion
 * protocol. Fixed-width name fields are null-padded, so they are read up to the
 * first NUL rather than by length.
 *
 * The codec is pure and stateless — the frame-by-frame conversation lives in
 * [MeshCoreSession].
 */
object MeshCoreCodec {

    // --- Encoding -----------------------------------------------------------

    /**
     * `CMD_DEVICE_QUERY`: `16 <app_target_ver>`.
     *
     * Must be the first command sent. The version byte decides whether the
     * firmware sends the SNR-carrying `*_V3` message frames.
     */
    fun deviceQuery(appTargetVersion: Int = MeshCoreProtocol.APP_TARGET_VERSION): ByteArray =
        byteArrayOf(MeshCoreProtocol.CMD_DEVICE_QUERY.toByte(), appTargetVersion.toByte())

    /**
     * `CMD_APP_START`: `01 <7 reserved bytes> <app name UTF-8>`.
     *
     * The firmware requires `len >= 8`, reads bytes 1..7 as reserved and treats
     * everything from byte 8 as the app name.
     */
    fun appStart(appName: String): ByteArray {
        val name = appName.toByteArray(Charsets.UTF_8)
        val out = ByteArray(8 + name.size)
        out[0] = MeshCoreProtocol.CMD_APP_START.toByte()
        // bytes 1..7 stay zero: reserved for future use
        name.copyInto(out, 8)
        return out
    }

    /**
     * `CMD_SEND_TXT_MSG`: `02 <txt_type> <attempt> <timestamp LE32> <6-byte pubkey prefix> <text>`.
     *
     * @param recipientPrefixHex 12 hex characters — the contact's 6-byte public-key prefix.
     * @param timestampSeconds the *sender's* clock, in epoch seconds.
     */
    fun sendTextMessage(
        recipientPrefixHex: String,
        text: String,
        timestampSeconds: Long,
        attempt: Int = 0,
        textType: Int = MeshCoreProtocol.TXT_TYPE_PLAIN,
    ): ByteArray {
        val prefix = hexToBytes(recipientPrefixHex)
        require(prefix.size == PUB_KEY_PREFIX_SIZE) {
            "recipient prefix must be $PUB_KEY_PREFIX_SIZE bytes, got ${prefix.size}"
        }
        val body = text.toByteArray(Charsets.UTF_8)
        val out = ByteArray(1 + 1 + 1 + 4 + PUB_KEY_PREFIX_SIZE + body.size)
        var i = 0
        out[i++] = MeshCoreProtocol.CMD_SEND_TXT_MSG.toByte()
        out[i++] = textType.toByte()
        out[i++] = attempt.toByte()
        writeLe32(out, i, timestampSeconds); i += 4
        prefix.copyInto(out, i); i += PUB_KEY_PREFIX_SIZE
        body.copyInto(out, i)
        return out
    }

    /**
     * `CMD_SEND_CHANNEL_TXT_MSG`: `03 <txt_type> <channel_idx> <timestamp LE32> <text>`.
     *
     * The firmware rejects any `txt_type` other than `TXT_TYPE_PLAIN` here.
     */
    fun sendChannelTextMessage(
        channelIndex: Int,
        text: String,
        timestampSeconds: Long,
    ): ByteArray {
        val body = text.toByteArray(Charsets.UTF_8)
        val out = ByteArray(1 + 1 + 1 + 4 + body.size)
        var i = 0
        out[i++] = MeshCoreProtocol.CMD_SEND_CHANNEL_TXT_MSG.toByte()
        out[i++] = MeshCoreProtocol.TXT_TYPE_PLAIN.toByte()
        out[i++] = channelIndex.toByte()
        writeLe32(out, i, timestampSeconds); i += 4
        body.copyInto(out, i)
        return out
    }

    /**
     * `CMD_GET_CONTACTS`: `04` or `04 <since LE32>`.
     *
     * Passing the `lastMod` from the previous [MeshCoreFrame.EndOfContacts] turns
     * a full contact dump into an incremental one.
     */
    fun getContacts(since: Long? = null): ByteArray = if (since == null) {
        byteArrayOf(MeshCoreProtocol.CMD_GET_CONTACTS.toByte())
    } else {
        ByteArray(5).also {
            it[0] = MeshCoreProtocol.CMD_GET_CONTACTS.toByte()
            writeLe32(it, 1, since)
        }
    }

    /** `CMD_SYNC_NEXT_MESSAGE`: `0A`. */
    fun syncNextMessage(): ByteArray = byteArrayOf(MeshCoreProtocol.CMD_SYNC_NEXT_MESSAGE.toByte())

    /** `CMD_GET_BATT_AND_STORAGE`: `14`. */
    fun getBatteryAndStorage(): ByteArray = byteArrayOf(MeshCoreProtocol.CMD_GET_BATT_AND_STORAGE.toByte())

    /** `CMD_GET_DEVICE_TIME`: `05`. */
    fun getDeviceTime(): ByteArray = byteArrayOf(MeshCoreProtocol.CMD_GET_DEVICE_TIME.toByte())

    /** `CMD_SET_DEVICE_TIME`: `06 <epoch seconds LE32>`. */
    fun setDeviceTime(epochSeconds: Long): ByteArray = ByteArray(5).also {
        it[0] = MeshCoreProtocol.CMD_SET_DEVICE_TIME.toByte()
        writeLe32(it, 1, epochSeconds)
    }

    /** `CMD_GET_CHANNEL`: `1F <channel_idx>`. */
    fun getChannel(index: Int): ByteArray =
        byteArrayOf(MeshCoreProtocol.CMD_GET_CHANNEL.toByte(), index.toByte())

    // --- Decoding -----------------------------------------------------------

    /**
     * Decodes one inbound frame.
     *
     * Never throws for an unrecognised code — that becomes
     * [MeshCoreFrame.Unhandled] so an unknown push cannot take the link down.
     * It does throw [MeshCoreFrameException] for a frame whose code is known but
     * whose length is impossible, because that means the two sides disagree about
     * the wire format and continuing would produce garbage.
     */
    fun decode(frame: ByteArray): MeshCoreFrame {
        if (frame.isEmpty()) throw MeshCoreFrameException("empty frame")
        return when (val code = frame[0].toInt() and 0xFF) {
            MeshCoreProtocol.RESP_CODE_OK ->
                MeshCoreFrame.Ok(if (frame.size >= 5) readLe32(frame, 1) else null)

            MeshCoreProtocol.RESP_CODE_ERR -> {
                require(frame, 2, "RESP_CODE_ERR")
                MeshCoreFrame.Error(frame[1].toInt() and 0xFF)
            }

            MeshCoreProtocol.RESP_CODE_DISABLED -> MeshCoreFrame.Disabled

            MeshCoreProtocol.RESP_CODE_SELF_INFO -> decodeSelfInfo(frame)
            MeshCoreProtocol.RESP_CODE_DEVICE_INFO -> decodeDeviceInfo(frame)

            MeshCoreProtocol.RESP_CODE_CONTACTS_START -> {
                require(frame, 5, "RESP_CODE_CONTACTS_START")
                MeshCoreFrame.ContactsStart(readLe32(frame, 1))
            }

            MeshCoreProtocol.RESP_CODE_CONTACT -> decodeContact(frame)

            MeshCoreProtocol.RESP_CODE_END_OF_CONTACTS -> {
                require(frame, 5, "RESP_CODE_END_OF_CONTACTS")
                MeshCoreFrame.EndOfContacts(readLe32(frame, 1))
            }

            MeshCoreProtocol.RESP_CODE_SENT -> {
                require(frame, 10, "RESP_CODE_SENT")
                MeshCoreFrame.Sent(
                    routeFlood = frame[1].toInt() == 1,
                    expectedAck = readLe32(frame, 2),
                    estimatedTimeoutMillis = readLe32(frame, 6),
                )
            }

            MeshCoreProtocol.RESP_CODE_CONTACT_MSG_RECV -> decodeContactMessage(frame, v3 = false)
            MeshCoreProtocol.RESP_CODE_CONTACT_MSG_RECV_V3 -> decodeContactMessage(frame, v3 = true)
            MeshCoreProtocol.RESP_CODE_CHANNEL_MSG_RECV -> decodeChannelMessage(frame, v3 = false)
            MeshCoreProtocol.RESP_CODE_CHANNEL_MSG_RECV_V3 -> decodeChannelMessage(frame, v3 = true)

            MeshCoreProtocol.RESP_CODE_NO_MORE_MESSAGES -> MeshCoreFrame.NoMoreMessages

            MeshCoreProtocol.RESP_CODE_BATT_AND_STORAGE -> {
                require(frame, 11, "RESP_CODE_BATT_AND_STORAGE")
                MeshCoreFrame.BatteryAndStorage(
                    batteryMilliVolts = readLe16(frame, 1),
                    storageUsedKb = readLe32(frame, 3),
                    storageTotalKb = readLe32(frame, 7),
                )
            }

            MeshCoreProtocol.RESP_CODE_CURR_TIME -> {
                require(frame, 5, "RESP_CODE_CURR_TIME")
                MeshCoreFrame.CurrentTime(readLe32(frame, 1))
            }

            MeshCoreProtocol.RESP_CODE_CHANNEL_INFO -> {
                // 12 <ch_idx> <32B name> <16B secret>. The secret is deliberately
                // not read: nothing above the protocol layer may see channel keys.
                require(frame, 2 + 32, "RESP_CODE_CHANNEL_INFO")
                MeshCoreFrame.ChannelInfo(
                    index = frame[1].toInt() and 0xFF,
                    name = readPaddedString(frame, 2, 32),
                )
            }

            MeshCoreProtocol.PUSH_CODE_MSG_WAITING -> MeshCoreFrame.MessagesWaiting

            MeshCoreProtocol.PUSH_CODE_SEND_CONFIRMED -> {
                // 82 <ack_code LE32> [<round trip LE32>]
                require(frame, 5, "PUSH_CODE_SEND_CONFIRMED")
                MeshCoreFrame.SendConfirmed(
                    ackCode = readLe32(frame, 1),
                    roundTripMillis = if (frame.size >= 9) readLe32(frame, 5) else null,
                )
            }

            MeshCoreProtocol.PUSH_CODE_ADVERT -> {
                // 80 <32B pubkey of the advertising contact>
                require(frame, 1 + PUB_KEY_SIZE, "PUSH_CODE_ADVERT")
                MeshCoreFrame.AdvertReceived(
                    frame.copyOfRange(1, 1 + PUB_KEY_PREFIX_SIZE).toHex(),
                )
            }

            // Built by writeContactRespFrame, so it shares RESP_CODE_CONTACT's layout.
            MeshCoreProtocol.PUSH_CODE_NEW_ADVERT -> MeshCoreFrame.NewContact(decodeContact(frame))
            MeshCoreProtocol.PUSH_CODE_PATH_UPDATED -> MeshCoreFrame.PathUpdated

            else -> MeshCoreFrame.Unhandled(code, frame.size)
        }
    }

    /**
     * `05 <adv_type> <tx_power> <max_tx_power> <32B pubkey> <lat LE32> <lon LE32>
     *  <multi_acks> <advert_loc_policy> <telemetry_mode> <manual_add>
     *  <freq LE32> <bw LE32> <sf> <cr> <name UTF-8 to end of frame>`
     */
    private fun decodeSelfInfo(frame: ByteArray): MeshCoreFrame.SelfInfo {
        val fixed = 1 + 3 + PUB_KEY_SIZE + 4 + 4 + 4 + 4 + 4 + 2
        require(frame, fixed, "RESP_CODE_SELF_INFO")
        var i = 1
        val advertType = frame[i++].toInt() and 0xFF
        val txPower = frame[i++].toInt()
        val maxTxPower = frame[i++].toInt()
        val pubKey = frame.copyOfRange(i, i + PUB_KEY_SIZE); i += PUB_KEY_SIZE
        val lat = readLe32Signed(frame, i); i += 4
        val lon = readLe32Signed(frame, i); i += 4
        val multiAcks = frame[i++].toInt() and 0xFF
        val advertLocPolicy = frame[i++].toInt() and 0xFF
        val telemetry = frame[i++].toInt() and 0xFF
        val manualAdd = frame[i++].toInt() and 0xFF
        val freq = readLe32(frame, i); i += 4
        val bw = readLe32(frame, i); i += 4
        val sf = frame[i++].toInt() and 0xFF
        val cr = frame[i++].toInt() and 0xFF
        val name = String(frame, i, frame.size - i, Charsets.UTF_8)
            .trim { it == '\u0000' || it.isWhitespace() }
        return MeshCoreFrame.SelfInfo(
            advertType = advertType,
            txPowerDbm = txPower,
            maxTxPowerDbm = maxTxPower,
            publicKey = pubKey,
            latitude = microDegreesOrNull(lat),
            longitude = microDegreesOrNull(lon),
            multiAcks = multiAcks,
            advertLocPolicy = advertLocPolicy,
            telemetryModeBits = telemetry,
            manualAddContacts = manualAdd,
            frequencyKhz = freq / 1000,
            bandwidthKhz = bw / 1000,
            spreadingFactor = sf,
            codingRate = cr,
            nodeName = name,
        )
    }

    /**
     * `0D <fw_ver_code> <max_contacts/2> <max_channels> <ble_pin LE32>
     *  <12B build date> <40B model> <20B version> [<client_repeat>] [<path_hash_mode>]`
     *
     * The last two bytes are firmware v9+ and v10+ respectively, so they are
     * optional here rather than required.
     */
    private fun decodeDeviceInfo(frame: ByteArray): MeshCoreFrame.DeviceInfo {
        val fixed = 1 + 3 + 4 + 12 + 40 + 20
        require(frame, fixed, "RESP_CODE_DEVICE_INFO")
        var i = 1
        val fwVerCode = frame[i++].toInt() and 0xFF
        val maxContactsRaw = frame[i++].toInt() and 0xFF
        val maxChannels = frame[i++].toInt() and 0xFF
        val blePin = readLe32(frame, i); i += 4
        val buildDate = readPaddedString(frame, i, 12); i += 12
        val model = readPaddedString(frame, i, 40); i += 40
        val version = readPaddedString(frame, i, 20); i += 20
        val clientRepeat = if (frame.size > i) (frame[i++].toInt() != 0) else null
        val pathHashMode = if (frame.size > i) (frame[i].toInt() and 0xFF) else null
        return MeshCoreFrame.DeviceInfo(
            firmwareVersionCode = fwVerCode,
            // The firmware sends MAX_CONTACTS / 2 to fit the count in a byte.
            maxContacts = maxContactsRaw * 2,
            maxChannels = maxChannels,
            blePin = blePin,
            firmwareBuildDate = buildDate,
            manufacturerModel = model,
            firmwareVersion = version,
            clientRepeatEnabled = clientRepeat,
            pathHashMode = pathHashMode,
        )
    }

    /**
     * `03 <32B pubkey> <type> <flags> <out_path_len> <64B out_path> <32B name>
     *  <last_advert LE32> <gps_lat LE32> <gps_lon LE32> <lastmod LE32>`
     */
    private fun decodeContact(frame: ByteArray): MeshCoreFrame.Contact {
        val expected = 1 + PUB_KEY_SIZE + 3 + MAX_PATH_SIZE + 32 + 4 + 4 + 4 + 4
        require(frame, expected, "RESP_CODE_CONTACT")
        var i = 1
        val pubKey = frame.copyOfRange(i, i + PUB_KEY_SIZE); i += PUB_KEY_SIZE
        val type = frame[i++].toInt() and 0xFF
        val flags = frame[i++].toInt() and 0xFF
        val outPathLen = frame[i++].toInt() and 0xFF
        i += MAX_PATH_SIZE // out_path: routing detail, not needed above this layer
        val name = readPaddedString(frame, i, 32); i += 32
        val lastAdvert = readLe32(frame, i); i += 4
        val lat = readLe32Signed(frame, i); i += 4
        val lon = readLe32Signed(frame, i); i += 4
        val lastMod = readLe32(frame, i)
        return MeshCoreFrame.Contact(
            publicKey = pubKey,
            type = type,
            flags = flags,
            outPathLen = outPathLen,
            name = name,
            lastAdvertTimestamp = lastAdvert,
            latitude = microDegreesOrNull(lat),
            longitude = microDegreesOrNull(lon),
            lastMod = lastMod,
        )
    }

    /**
     * v3: `10 <snr*4> <res> <res> <6B pubkey> <path_len> <txt_type> <ts LE32> [<4B sig>] <text>`
     * v1: `07 <6B pubkey> <path_len> <txt_type> <ts LE32> [<4B sig>] <text>`
     */
    private fun decodeContactMessage(frame: ByteArray, v3: Boolean): MeshCoreFrame.ContactMessage {
        var i = 1
        var snr: Float? = null
        if (v3) {
            require(frame, 4 + PUB_KEY_PREFIX_SIZE + 2 + 4, "RESP_CODE_CONTACT_MSG_RECV_V3")
            snr = frame[i].toInt() / 4f // firmware sends (int8_t)(SNR * 4)
            i += 3 // snr + 2 reserved
        } else {
            require(frame, 1 + PUB_KEY_PREFIX_SIZE + 2 + 4, "RESP_CODE_CONTACT_MSG_RECV")
        }
        val sender = frame.copyOfRange(i, i + PUB_KEY_PREFIX_SIZE).toHex(); i += PUB_KEY_PREFIX_SIZE
        val pathLen = frame[i++].toInt() and 0xFF
        val textType = frame[i++].toInt() and 0xFF
        val timestamp = readLe32(frame, i); i += 4
        var signature: String? = null
        if (textType == MeshCoreProtocol.TXT_TYPE_SIGNED_PLAIN) {
            if (frame.size < i + 4) throw MeshCoreFrameException("signed message frame missing signature")
            signature = frame.copyOfRange(i, i + 4).toHex(); i += 4
        }
        return MeshCoreFrame.ContactMessage(
            senderPrefix = sender,
            pathLen = pathLen,
            textType = textType,
            senderTimestamp = timestamp,
            text = String(frame, i, frame.size - i, Charsets.UTF_8),
            snr = snr,
            signature = signature,
        )
    }

    /**
     * v3: `11 <snr*4> <res> <res> <ch_idx> <path_len> <txt_type> <ts LE32> <text>`
     * v1: `08 <ch_idx> <path_len> <txt_type> <ts LE32> <text>`
     */
    private fun decodeChannelMessage(frame: ByteArray, v3: Boolean): MeshCoreFrame.ChannelMessage {
        var i = 1
        var snr: Float? = null
        if (v3) {
            require(frame, 4 + 3 + 4, "RESP_CODE_CHANNEL_MSG_RECV_V3")
            snr = frame[i].toInt() / 4f
            i += 3
        } else {
            require(frame, 1 + 3 + 4, "RESP_CODE_CHANNEL_MSG_RECV")
        }
        val channelIndex = frame[i++].toInt() and 0xFF
        val pathLen = frame[i++].toInt() and 0xFF
        val textType = frame[i++].toInt() and 0xFF
        val timestamp = readLe32(frame, i); i += 4
        return MeshCoreFrame.ChannelMessage(
            channelIndex = channelIndex,
            pathLen = pathLen,
            textType = textType,
            senderTimestamp = timestamp,
            text = String(frame, i, frame.size - i, Charsets.UTF_8),
            snr = snr,
        )
    }

    // --- Primitives ---------------------------------------------------------

    private fun require(frame: ByteArray, minSize: Int, what: String) {
        if (frame.size < minSize) {
            throw MeshCoreFrameException("$what truncated: ${frame.size} bytes, need $minSize")
        }
        if (frame.size > MAX_FRAME_SIZE) {
            throw MeshCoreFrameException("$what oversized: ${frame.size} bytes, max $MAX_FRAME_SIZE")
        }
    }

    private fun writeLe32(out: ByteArray, offset: Int, value: Long) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value shr 8) and 0xFF).toByte()
        out[offset + 2] = ((value shr 16) and 0xFF).toByte()
        out[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun readLe16(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

    /** Unsigned 32-bit little-endian, widened to Long so it never comes back negative. */
    private fun readLe32(b: ByteArray, offset: Int): Long =
        (b[offset].toLong() and 0xFF) or
            ((b[offset + 1].toLong() and 0xFF) shl 8) or
            ((b[offset + 2].toLong() and 0xFF) shl 16) or
            ((b[offset + 3].toLong() and 0xFF) shl 24)

    /** Signed 32-bit little-endian, for coordinates. */
    private fun readLe32Signed(b: ByteArray, offset: Int): Int = readLe32(b, offset).toInt()

    /** Reads a fixed-width, NUL-padded UTF-8 string. */
    private fun readPaddedString(b: ByteArray, offset: Int, width: Int): String {
        var end = offset
        val limit = minOf(offset + width, b.size)
        while (end < limit && b[end].toInt() != 0) end++
        return String(b, offset, end - offset, Charsets.UTF_8)
    }

    /**
     * Converts the firmware's 1e6 fixed-point coordinates to degrees.
     *
     * 0 means "no position" in both firmwares, so it maps to null rather than to
     * a point off the coast of Africa.
     */
    private fun microDegreesOrNull(value: Int): Double? =
        if (value == 0) null else value / 1_000_000.0

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex string must have an even length" }
        return ByteArray(hex.length / 2) { i ->
            ((hexDigit(hex[i * 2]) shl 4) or hexDigit(hex[i * 2 + 1])).toByte()
        }
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("not a hex digit: $c")
    }
}
