package com.unifiedmesh.protocol.meshcore

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Byte-level tests for the companion protocol.
 *
 * The expected bytes are written out by hand from the frame layouts in upstream
 * `examples/companion_radio/MyMesh.cpp`, so a change in the firmware's wire
 * format shows up here rather than in the field.
 */
class MeshCoreCodecTest {

    // --- Encoding -----------------------------------------------------------

    @Test
    fun `device query announces the protocol version`() {
        // CMD_DEVICE_QUERY = 22 (0x16), then app_target_ver.
        assertThat(MeshCoreCodec.deviceQuery(3).toList())
            .containsExactly(0x16.toByte(), 0x03.toByte())
            .inOrder()
    }

    @Test
    fun `app start leaves seven reserved bytes before the app name`() {
        val frame = MeshCoreCodec.appStart("UnifiedMesh")

        assertThat(frame[0]).isEqualTo(0x01.toByte())
        // The firmware requires len >= 8 and reads the name from byte 8.
        assertThat(frame.copyOfRange(1, 8).toList()).containsExactly(
            0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(), 0.toByte(),
        )
        assertThat(String(frame, 8, frame.size - 8)).isEqualTo("UnifiedMesh")
        assertThat(frame.size).isAtLeast(8)
    }

    @Test
    fun `direct message frame matches CMD_SEND_TXT_MSG layout`() {
        val frame = MeshCoreCodec.sendTextMessage(
            recipientPrefixHex = "1a2b3c4d5e6f",
            text = "hi",
            timestampSeconds = 0x01020304,
        )

        assertThat(frame[0]).isEqualTo(0x02.toByte()) // CMD_SEND_TXT_MSG
        assertThat(frame[1]).isEqualTo(0x00.toByte()) // TXT_TYPE_PLAIN
        assertThat(frame[2]).isEqualTo(0x00.toByte()) // attempt
        // Timestamp is little-endian.
        assertThat(frame.copyOfRange(3, 7).toList())
            .containsExactly(0x04.toByte(), 0x03.toByte(), 0x02.toByte(), 0x01.toByte())
            .inOrder()
        assertThat(frame.copyOfRange(7, 13).toList())
            .containsExactly(
                0x1a.toByte(), 0x2b.toByte(), 0x3c.toByte(),
                0x4d.toByte(), 0x5e.toByte(), 0x6f.toByte(),
            )
            .inOrder()
        assertThat(String(frame, 13, frame.size - 13)).isEqualTo("hi")
        // The firmware requires len >= 14 for this command.
        assertThat(frame.size).isAtLeast(14)
    }

    @Test
    fun `a recipient prefix of the wrong length is rejected`() {
        val error = runCatching {
            MeshCoreCodec.sendTextMessage("1a2b", "hi", 0)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `channel message frame matches CMD_SEND_CHANNEL_TXT_MSG layout`() {
        val frame = MeshCoreCodec.sendChannelTextMessage(channelIndex = 2, text = "yo", timestampSeconds = 1)

        assertThat(frame[0]).isEqualTo(0x03.toByte())
        assertThat(frame[1]).isEqualTo(0x00.toByte()) // must be TXT_TYPE_PLAIN
        assertThat(frame[2]).isEqualTo(0x02.toByte()) // channel index
        assertThat(frame.copyOfRange(3, 7).toList())
            .containsExactly(1.toByte(), 0.toByte(), 0.toByte(), 0.toByte())
            .inOrder()
        assertThat(String(frame, 7, frame.size - 7)).isEqualTo("yo")
    }

    @Test
    fun `get contacts omits the since filter when it is not set`() {
        assertThat(MeshCoreCodec.getContacts().size).isEqualTo(1)
        // The firmware only reads 'since' when len >= 5.
        assertThat(MeshCoreCodec.getContacts(since = 42).size).isEqualTo(5)
    }

    // --- Decoding -----------------------------------------------------------

    @Test
    fun `decodes a v3 channel message with SNR`() {
        val text = "We are heading back to camp."
        val frame = buildFrame(
            0x11, // RESP_CODE_CHANNEL_MSG_RECV_V3
            26, // snr * 4  => 6.5 dB
            0, 0, // reserved
            0x00, // channel index
            0xFF, // path_len: 0xFF on receive means it arrived direct
            0x00, // TXT_TYPE_PLAIN
        ) + le32(1_700_000_000) + text.toByteArray()

        val decoded = MeshCoreCodec.decode(frame) as MeshCoreFrame.ChannelMessage

        assertThat(decoded.channelIndex).isEqualTo(0)
        assertThat(decoded.snr).isEqualTo(6.5f)
        assertThat(decoded.senderTimestamp).isEqualTo(1_700_000_000L)
        assertThat(decoded.text).isEqualTo(text)
    }

    @Test
    fun `decodes a negative SNR correctly`() {
        // The firmware casts to int8_t, so 0xF8 is -8 quarter-dB, i.e. -2.0 dB.
        val frame = buildFrame(0x11, 0xF8, 0, 0, 0, 0xFF, 0) + le32(1) + "x".toByteArray()

        val decoded = MeshCoreCodec.decode(frame) as MeshCoreFrame.ChannelMessage

        assertThat(decoded.snr).isEqualTo(-2.0f)
    }

    @Test
    fun `decodes a v3 direct message`() {
        val frame = buildFrame(0x10, 20, 0, 0) +
            byteArrayOf(0x1a, 0x2b, 0x3c, 0x4d, 0x5e, 0x6f) +
            buildFrame(0x03, 0x00) + // path_len 3 (flood, 3 hops), TXT_TYPE_PLAIN
            le32(1_700_000_100) +
            "Copy.".toByteArray()

        val decoded = MeshCoreCodec.decode(frame) as MeshCoreFrame.ContactMessage

        assertThat(decoded.senderPrefix).isEqualTo("1a2b3c4d5e6f")
        assertThat(decoded.pathLen).isEqualTo(3)
        assertThat(decoded.wasDirect).isFalse()
        assertThat(decoded.snr).isEqualTo(5.0f)
        assertThat(decoded.text).isEqualTo("Copy.")
        assertThat(decoded.signature).isNull()
    }

    @Test
    fun `a signed direct message carries a four byte signature before the text`() {
        val frame = buildFrame(0x10, 20, 0, 0) +
            byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66) +
            buildFrame(0xFF, 0x02) + // path_len direct, TXT_TYPE_SIGNED_PLAIN
            le32(5) +
            byteArrayOf(0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte()) +
            "signed".toByteArray()

        val decoded = MeshCoreCodec.decode(frame) as MeshCoreFrame.ContactMessage

        assertThat(decoded.signature).isEqualTo("aabbccdd")
        assertThat(decoded.text).isEqualTo("signed")
        assertThat(decoded.wasDirect).isTrue()
    }

    @Test
    fun `decodes the legacy non-v3 message frames`() {
        val frame = buildFrame(0x08, 0x01, 0xFF, 0x00) + le32(99) + "legacy".toByteArray()

        val decoded = MeshCoreCodec.decode(frame) as MeshCoreFrame.ChannelMessage

        assertThat(decoded.channelIndex).isEqualTo(1)
        assertThat(decoded.snr).isNull()
        assertThat(decoded.text).isEqualTo("legacy")
    }

    @Test
    fun `decodes the sent confirmation`() {
        val frame = buildFrame(0x06, 0x01) + le32(0xDEADBEEF) + le32(4000)

        val decoded = MeshCoreCodec.decode(frame) as MeshCoreFrame.Sent

        assertThat(decoded.routeFlood).isTrue()
        assertThat(decoded.expectedAck).isEqualTo(0xDEADBEEFL)
        assertThat(decoded.estimatedTimeoutMillis).isEqualTo(4000L)
    }

    @Test
    fun `decodes battery and storage`() {
        val frame = buildFrame(0x0C) + le16(3920) + le32(120) + le32(4096)

        val decoded = MeshCoreCodec.decode(frame) as MeshCoreFrame.BatteryAndStorage

        assertThat(decoded.batteryMilliVolts).isEqualTo(3920)
        assertThat(decoded.storageUsedKb).isEqualTo(120L)
        assertThat(decoded.storageTotalKb).isEqualTo(4096L)
    }

    @Test
    fun `decodes device info including the null padded strings`() {
        val frame = buildFrame(0x0D, 13, 50, 8) +
            le32(123456) +
            padded("2026-08-01", 12) +
            padded("T1000-E", 40) +
            padded("v1.9.0", 20) +
            buildFrame(0x01, 0x00) // v9+ client repeat, v10+ path hash mode

        val decoded = MeshCoreCodec.decode(frame) as MeshCoreFrame.DeviceInfo

        assertThat(decoded.firmwareVersionCode).isEqualTo(13)
        // The firmware sends MAX_CONTACTS / 2.
        assertThat(decoded.maxContacts).isEqualTo(100)
        assertThat(decoded.maxChannels).isEqualTo(8)
        assertThat(decoded.blePin).isEqualTo(123456L)
        assertThat(decoded.firmwareBuildDate).isEqualTo("2026-08-01")
        assertThat(decoded.manufacturerModel).isEqualTo("T1000-E")
        assertThat(decoded.firmwareVersion).isEqualTo("v1.9.0")
        assertThat(decoded.clientRepeatEnabled).isTrue()
        assertThat(decoded.pathHashMode).isEqualTo(0)
    }

    @Test
    fun `device info tolerates firmware that omits the newer trailing bytes`() {
        val frame = buildFrame(0x0D, 9, 50, 8) +
            le32(0) + padded("2025-01-01", 12) + padded("Heltec", 40) + padded("v1.5", 20)

        val decoded = MeshCoreCodec.decode(frame) as MeshCoreFrame.DeviceInfo

        assertThat(decoded.clientRepeatEnabled).isNull()
        assertThat(decoded.pathHashMode).isNull()
    }

    @Test
    fun `decodes self info and derives the node id from the key prefix`() {
        val pubKey = ByteArray(32) { (it + 1).toByte() }
        val frame = buildFrame(0x05, 0x01, 20, 22) + pubKey +
            le32(44_428_000) + le32(-110_588_500) +
            buildFrame(0x01, 0x00, 0x00, 0x00) +
            le32(869_525) + le32(250_000) +
            buildFrame(11, 5) +
            "Ridge Base".toByteArray()

        val decoded = MeshCoreCodec.decode(frame) as MeshCoreFrame.SelfInfo

        assertThat(decoded.nodeId).isEqualTo("010203040506")
        assertThat(decoded.nodeName).isEqualTo("Ridge Base")
        assertThat(decoded.latitude).isWithin(1e-9).of(44.428)
        assertThat(decoded.longitude).isWithin(1e-9).of(-110.5885)
        assertThat(decoded.frequencyKhz).isEqualTo(869L)
        assertThat(decoded.spreadingFactor).isEqualTo(11)
        assertThat(decoded.codingRate).isEqualTo(5)
    }

    @Test
    fun `a zero coordinate means no position rather than the Gulf of Guinea`() {
        val pubKey = ByteArray(32)
        val frame = buildFrame(0x05, 0x01, 20, 22) + pubKey +
            le32(0) + le32(0) +
            buildFrame(0, 0, 0, 0) + le32(0) + le32(0) + buildFrame(7, 5) + "N".toByteArray()

        val decoded = MeshCoreCodec.decode(frame) as MeshCoreFrame.SelfInfo

        assertThat(decoded.latitude).isNull()
        assertThat(decoded.longitude).isNull()
    }

    @Test
    fun `decodes a contact frame`() {
        val pubKey = ByteArray(32) { (0xA0 + it).toByte() }
        val frame = buildFrame(0x03) + pubKey +
            buildFrame(0x01, 0x00, 0x02) + // ADV_TYPE_CHAT, flags, out_path_len
            ByteArray(64) + // out_path
            padded("Ridge Base", 32) +
            le32(1_700_000_000) +
            le32(44_501_900) + le32(-110_796_100) +
            le32(1_700_000_500)

        val decoded = MeshCoreCodec.decode(frame) as MeshCoreFrame.Contact

        assertThat(decoded.id).isEqualTo("a0a1a2a3a4a5")
        assertThat(decoded.name).isEqualTo("Ridge Base")
        assertThat(decoded.type).isEqualTo(MeshCoreProtocol.ADV_TYPE_CHAT)
        assertThat(decoded.latitude).isWithin(1e-9).of(44.5019)
        assertThat(decoded.lastMod).isEqualTo(1_700_000_500L)
    }

    @Test
    fun `a new advert push is decoded as a contact`() {
        val pubKey = ByteArray(32) { (it + 3).toByte() }
        val frame = buildFrame(0x8A) + pubKey +
            buildFrame(0x01, 0x00, 0xFF) + ByteArray(64) + padded("Truck", 32) +
            le32(1) + le32(0) + le32(0) + le32(2)

        val decoded = MeshCoreCodec.decode(frame) as MeshCoreFrame.NewContact

        assertThat(decoded.contact.name).isEqualTo("Truck")
        assertThat(decoded.contact.latitude).isNull()
    }

    @Test
    fun `an advert push carries only the public key`() {
        val frame = buildFrame(0x80) + ByteArray(32) { (it + 1).toByte() }

        val decoded = MeshCoreCodec.decode(frame) as MeshCoreFrame.AdvertReceived

        assertThat(decoded.nodeId).isEqualTo("010203040506")
    }

    @Test
    fun `decodes push and terminal codes`() {
        assertThat(MeshCoreCodec.decode(buildFrame(0x83))).isEqualTo(MeshCoreFrame.MessagesWaiting)
        assertThat(MeshCoreCodec.decode(buildFrame(0x0A))).isEqualTo(MeshCoreFrame.NoMoreMessages)
        assertThat(MeshCoreCodec.decode(buildFrame(0x0F))).isEqualTo(MeshCoreFrame.Disabled)

        val sendConfirmed = MeshCoreCodec.decode(buildFrame(0x82) + le32(7) + le32(1500))
        assertThat(sendConfirmed).isEqualTo(MeshCoreFrame.SendConfirmed(7L, 1500L))
    }

    @Test
    fun `decodes an error frame`() {
        val decoded = MeshCoreCodec.decode(buildFrame(0x01, 0x03)) as MeshCoreFrame.Error

        assertThat(decoded.code).isEqualTo(MeshCoreProtocol.ERR_CODE_TABLE_FULL)
        assertThat(decoded.description).isEqualTo("table full")
    }

    @Test
    fun `channel info exposes the name but never the secret`() {
        val frame = buildFrame(0x12, 0x00) + padded("Public", 32) + ByteArray(16) { 0x7F }

        val decoded = MeshCoreCodec.decode(frame) as MeshCoreFrame.ChannelInfo

        assertThat(decoded.index).isEqualTo(0)
        assertThat(decoded.name).isEqualTo("Public")
        // MeshCoreFrame.ChannelInfo has no field that could carry the secret.
        assertThat(MeshCoreFrame.ChannelInfo::class.java.declaredFields.map { it.name })
            .containsNoneOf("secret", "psk", "key")
    }

    @Test
    fun `an unknown response code is preserved rather than throwing`() {
        val decoded = MeshCoreCodec.decode(buildFrame(0x8D, 1, 2, 3)) as MeshCoreFrame.Unhandled

        assertThat(decoded.code).isEqualTo(0x8D)
        assertThat(decoded.length).isEqualTo(4)
    }

    @Test
    fun `a truncated frame of a known type is rejected`() {
        val error = runCatching { MeshCoreCodec.decode(buildFrame(0x0C, 1, 2)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(MeshCoreFrameException::class.java)
    }

    @Test
    fun `an empty frame is rejected`() {
        val error = runCatching { MeshCoreCodec.decode(ByteArray(0)) }.exceptionOrNull()

        assertThat(error).isInstanceOf(MeshCoreFrameException::class.java)
    }

    @Test
    fun `an oversized frame is rejected`() {
        val error = runCatching {
            MeshCoreCodec.decode(buildFrame(0x0C) + ByteArray(MeshCoreProtocol.MAX_FRAME_SIZE))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(MeshCoreFrameException::class.java)
    }

    @Test
    fun `UTF-8 message text survives the round trip`() {
        val text = "Nordlys i natt — kaffe på bordet ☕"
        val frame = buildFrame(0x11, 0, 0, 0, 0, 0xFF, 0) + le32(1) + text.toByteArray(Charsets.UTF_8)

        val decoded = MeshCoreCodec.decode(frame) as MeshCoreFrame.ChannelMessage

        assertThat(decoded.text).isEqualTo(text)
    }

    // --- Helpers ------------------------------------------------------------

    private fun buildFrame(vararg bytes: Int): ByteArray =
        ByteArray(bytes.size) { bytes[it].toByte() }

    private fun le16(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private fun le32(value: Long): ByteArray = ByteArray(4) { ((value shr (it * 8)) and 0xFF).toByte() }

    private fun le32(value: Int): ByteArray = le32(value.toLong() and 0xFFFFFFFFL)

    private fun padded(value: String, width: Int): ByteArray =
        ByteArray(width).also { value.toByteArray().copyInto(it) }
}
