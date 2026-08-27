# Protocol notes

Every protocol constant, UUID, frame layout and handshake step in this app was
transcribed from upstream source read at a specific commit. Nothing here was
recalled from memory or inferred from a blog post. This file records where each
piece came from so it can be re-checked when either project moves.

**Rule for contributors:** do not add a constant, a field offset, or a command
code to this codebase without a corresponding line of upstream source. If you
cannot find it upstream, stop and go and read the firmware.

---

## MeshCore

Upstream: [`meshcore-dev/MeshCore`](https://github.com/meshcore-dev/MeshCore)
Read at commit `0679dbe` (2026-08-24).

| What | Where upstream |
|---|---|
| `CMD_*`, `RESP_CODE_*`, `PUSH_CODE_*`, `ERR_CODE_*` | `examples/companion_radio/MyMesh.cpp`, the `#define` block at the top |
| `MAX_FRAME_SIZE` = 176 | `src/helpers/BaseSerialInterface.h` |
| BLE service and characteristic UUIDs | `src/helpers/esp32/SerialBLEInterface.cpp` |
| `PUB_KEY_SIZE` = 32, `MAX_PATH_SIZE` = 64 | `src/MeshCore.h` |
| `OUT_PATH_UNKNOWN` = 0xFF | `src/helpers/ContactInfo.h` |
| `TXT_TYPE_*` | `src/helpers/TxtDataHelpers.h` |
| `ADV_TYPE_*` | `src/helpers/AdvertDataHelpers.h` |
| Contact frame layout | `MyMesh::writeContactRespFrame` |
| `RESP_CODE_SELF_INFO` layout | `MyMesh::handleCmdFrame`, the `CMD_APP_START` branch |
| `RESP_CODE_DEVICE_INFO` layout | `MyMesh::handleCmdFrame`, the `CMD_DEVICE_QUERY` branch |
| Inbound message frame layouts | `MyMesh::queueMessage` and `MyMesh::onChannelMessageRecv` |
| `RESP_CODE_SENT` layout | `MyMesh::handleCmdFrame`, the `CMD_SEND_TXT_MSG` branch |
| `PUSH_CODE_SEND_CONFIRMED` layout | `MyMesh.cpp`, the ACK handling near `expected_ack_table` |

Also consulted: the protocol reference at
`docs/companion_protocol.md` in the same repository.

### Things worth knowing

- **A BLE frame is one characteristic value.** No reassembly, no length prefix.
  This is why `MeshCoreBleTransport` refuses to run when the negotiated MTU
  cannot carry `MAX_FRAME_SIZE`: the stack truncates rather than splitting, and
  a contact record is 148 bytes.
- **`CMD_DEVICE_QUERY` must come first,** and its second byte is the protocol
  version the client understands. Sending less than 3 makes the firmware fall
  back to the pre-V3 message frames, which carry no SNR.
- **`path_len` means different things in each direction.** On send, `0xFF` means
  flood. On receive, `0xFF` means the packet arrived by a direct route.
- **Push codes are `>= 0x80`** and can arrive in the middle of a command
  exchange, which is why `MeshCoreSession` routes them separately instead of
  treating them as replies.
- **The contact listing is one exchange, many frames:** `RESP_CODE_CONTACTS_START`,
  then a `RESP_CODE_CONTACT` per contact, then `RESP_CODE_END_OF_CONTACTS`
  carrying a `lastmod` watermark for incremental re-sync.
- **`PUSH_CODE_NEW_ADVERT` reuses the contact frame layout** (the firmware builds
  it with `writeContactRespFrame`), while `PUSH_CODE_ADVERT` carries only a
  32-byte public key.
- **The offline queue is pull-based.** `PUSH_CODE_MSG_WAITING` is a tickle; the
  client then sends `CMD_SYNC_NEXT_MESSAGE` until it gets
  `RESP_CODE_NO_MORE_MESSAGES`.
- **Channel messages carry the sender's name inside the payload** as
  `"name: text"`. There is no sender field on the frame for group traffic.

---

## Meshtastic

Upstream, protobufs: [`meshtastic/protobufs`](https://github.com/meshtastic/protobufs)
at commit `1b4cb00f3d6b0d620354a11fdd1e0b592f3cb7f5` (2026-08-21).
The transitive import closure of `mesh.proto` is vendored under
`protocol/meshtastic/src/main/proto/meshtastic/`.

Upstream, client behaviour: [`meshtastic/meshtastic-android`](https://github.com/meshtastic/meshtastic-android)
read at commit `a681135` (2026-08-27).

| What | Where upstream |
|---|---|
| BLE service, `TORADIO`, `FROMRADIO`, `FROMNUM`, `LOGRADIO` UUIDs | `core/ble/src/commonMain/kotlin/org/meshtastic/core/ble/MeshtasticBleConstants.kt` |
| Device name pattern | same file, `BLE_NAME_PATTERN` |
| `want_config_id` nonces (69420 / 69421) | `core/repository/src/commonMain/kotlin/org/meshtastic/core/repository/HandshakeConstants.kt` |
| The notify-then-drain read loop | `core/ble/src/commonMain/kotlin/org/meshtastic/core/ble/KableMeshtasticRadioProfile.kt` |
| `ToRadio` / `FromRadio` / `MeshPacket` / `Data` / `NodeInfo` / `Routing` | the vendored `mesh.proto` |
| `PortNum` values | the vendored `portnums.proto` |
| `Channel` / `ChannelSettings` | the vendored `channel.proto` |
| `DeviceMetrics` | the vendored `telemetry.proto` |

### Things worth knowing

- **The radio never pushes packets.** It notifies `FROMNUM` to say data is
  waiting; the client then reads `FROMRADIO` repeatedly until a read returns
  empty. `MeshtasticBleTransport` owns that loop.
- **`FROMNUM` notifications are gated behind firmware state.** During the config
  handshake the notification may never arrive, so the transport seeds a drain at
  open and again after every write.
- **The handshake ends on `config_complete_id` echoing the nonce you sent.** The
  nonce must be non-zero or the echo is unmatchable.
- **`rx_time` is optional** — the firmware only sets it once it has a clock. The
  codec falls back to local receipt time, otherwise every such message sorts to
  1970.
- **`battery_level` of 101 means "powered, no battery"**, not 101% charge. The
  codec maps anything outside 0..100 to null.
- **Node numbers are unsigned 32-bit.** `0xFFFFFFFF` is the broadcast address and
  does not fit in a signed `Int`, so it is carried as a `Long` throughout.
- **Packet ids must be supplied by the client and be non-zero,** because the
  routing acknowledgement correlates on `Data.request_id` echoing the original
  packet id.
- **A `want_ack` on a broadcast would make every receiver answer.** The codec
  refuses to set it on channel traffic regardless of what the caller asks for.

### Updating the vendored protobufs

1. Copy the transitive import closure of `mesh.proto` from upstream at a known
   commit.
2. Record the new commit in `protocol/meshtastic/src/main/proto/meshtastic/README.md`
   and in this file.
3. Run `./gradlew :protocol:meshtastic:test`. `MeshtasticCodecTest` asserts
   against the specific fields and enum values the app relies on, so a breaking
   rename shows up there.

---

## What this app deliberately does not do

- **It does not make the radios speak each other's protocol.** Each radio has its
  own connection, its own adapter, and its own session. The phone is the only
  thing that sees both.
- **It does not read or store key material.** Meshtastic channel PSKs and
  MeshCore channel secrets arrive on the wire during normal operation and are
  discarded inside the protocol layer. No model type has a field they could
  occupy, and nothing writes them to the database or the log.
- **It does not weaken either protocol's security model.** Encrypted Meshtastic
  packets the radio holds no key for are not treated as text. Each radio does its
  own encryption exactly as it would with its own vendor app.
- **It does not merge identities across networks.** A Meshtastic node and a
  MeshCore contact with the same display name are two different entries with two
  different ids.
