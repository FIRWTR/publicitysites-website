package com.unifiedmesh.core.bridge

import com.google.common.truth.Truth.assertThat
import com.unifiedmesh.core.model.MeshProtocol
import org.junit.Test

class BridgeTextCodecTest {

    @Test
    fun `encodes first hop without a counter`() {
        val encoded = BridgeTextCodec.encode(MeshProtocol.MESHTASTIC, "Bear", "Need help at camp", hops = 1)
        assertThat(encoded).isEqualTo("[MT: Bear] Need help at camp")
    }

    @Test
    fun `encodes later hops with a counter`() {
        val encoded = BridgeTextCodec.encode(MeshProtocol.MESHCORE, "Ridge Base", "Copy.", hops = 3)
        assertThat(encoded).isEqualTo("[MC x3: Ridge Base] Copy.")
    }

    @Test
    fun `round trips through decode`() {
        val encoded = BridgeTextCodec.encode(MeshProtocol.MESHTASTIC, "Bear", "Need help at camp", hops = 2)
        val marker = BridgeTextCodec.decode(encoded)

        assertThat(marker).isNotNull()
        assertThat(marker!!.originProtocol).isEqualTo(MeshProtocol.MESHTASTIC)
        assertThat(marker.originSender).isEqualTo("Bear")
        assertThat(marker.body).isEqualTo("Need help at camp")
        assertThat(marker.hops).isEqualTo(2)
    }

    @Test
    fun `unmarked text decodes to null`() {
        assertThat(BridgeTextCodec.decode("We are heading back to camp.")).isNull()
        assertThat(BridgeTextCodec.isMarked("We are heading back to camp.")).isFalse()
    }

    @Test
    fun `text that merely starts with a bracket is not a marker`() {
        assertThat(BridgeTextCodec.decode("[urgent] fire on the ridge")).isNull()
        assertThat(BridgeTextCodec.decode("[XX: Bear] hello")).isNull()
    }

    @Test
    fun `strips bracket characters from sender names so markers cannot be forged`() {
        val encoded = BridgeTextCodec.encode(
            MeshProtocol.MESHTASTIC,
            originSender = "Ev[il] ]Bear[",
            body = "hi",
            hops = 1,
        )

        assertThat(encoded).isEqualTo("[MT: Evil Bear] hi")
        val marker = BridgeTextCodec.decode(encoded)
        assertThat(marker!!.body).isEqualTo("hi")
        assertThat(marker.hops).isEqualTo(1)
    }

    @Test
    fun `a sender crafting a fake marker cannot claim zero hops`() {
        // Someone types this by hand on the Meshtastic side. Decoding it must still
        // report at least one hop so the engine treats it as already-bridged.
        val marker = BridgeTextCodec.decode("[MT x0: Bear] sneaky")
        assertThat(marker!!.hops).isEqualTo(1)
    }

    @Test
    fun `unknown sender falls back to a placeholder`() {
        val encoded = BridgeTextCodec.encode(MeshProtocol.MESHCORE, null, "beacon", hops = 1)
        assertThat(encoded).isEqualTo("[MC: ?] beacon")
    }

    @Test
    fun `stripMarker returns the body for marked and unmarked text alike`() {
        assertThat(BridgeTextCodec.stripMarker("[MT: Bear] hello")).isEqualTo("hello")
        assertThat(BridgeTextCodec.stripMarker("hello")).isEqualTo("hello")
    }

    @Test
    fun `long sender names are truncated rather than blowing the airtime budget`() {
        val encoded = BridgeTextCodec.encode(MeshProtocol.MESHTASTIC, "x".repeat(100), "hi", hops = 1)
        assertThat(encoded.length).isLessThan(40)
    }
}
