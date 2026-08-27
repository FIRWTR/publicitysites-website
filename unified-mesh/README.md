# Unified Mesh

An Android app that connects to a **Meshtastic** radio and a **MeshCore** radio
at the same time, over two independent Bluetooth LE links, and presents both
networks through one interface.

The radios stay independent. Neither is ever asked to speak the other's protocol;
the phone connects to each separately and does the translation, display and
sending. An optional bridge can re-transmit text from one network onto the other,
with loop prevention that is the most carefully built part of the codebase.

```
Android app
│
├── Meshtastic connection ──► Meshtastic radio ──► Meshtastic mesh
│
└── MeshCore connection   ──► MeshCore radio   ──► MeshCore mesh
```

## What is here

| Area | State |
|---|---|
| Unified inbox, `MT`/`MC` tagging, filters | Built |
| Composer with SEND VIA Meshtastic / MeshCore / Both, per-radio results | Built |
| Radios screen: assign, connect, disconnect, reconnect, scan | Built |
| Nodes and contacts, grouped per network, never merged | Built |
| Map of positioned nodes with per-network markers | Built, tile-less by design — see below |
| Bridge with six-layer loop prevention | Built |
| Foreground service, reconnection with backoff, notifications | Built |
| Diagnostics log with sanitised export | Built |
| Meshtastic protocol (BLE, protobuf, config handshake, acks) | Built, verified against a simulated radio |
| MeshCore companion protocol (BLE, frames, contacts, offline queue) | Built, verified against a simulated radio |
| Builds to an installable APK | Verified on CI |
| Bench-tested against real hardware | **Not done — see Status** |

## Status, honestly

**139 unit tests pass**, covering the bridge decision logic, duplicate
suppression, TTL expiry, hop limiting, the multi-bridge round trip, channel
mapping, both protocol codecs at the byte level, both adapters driven against
simulated radios that speak the real wire formats, reconnect backoff, and the
radio-isolation guarantees.

**The whole project compiles**, and CI produces an installable debug APK on
every push. The tests also run there against the real ten-module build, which
matters: the local JVM harness compiles every pure-Kotlin module into a single
source set, so it cannot see errors that only exist across module boundaries.
Getting to that first green build took four rounds and found four such
problems — a missing Gradle wrapper, a duplicate protobuf builtin, a misplaced
`kotlin{}` block, and three smart casts that do not hold across modules.

**Nothing has been run against real hardware, or on a device at all.** A green
APK proves the code compiles and links; it does not prove the Compose screens
render, that Hilt's graph constructs at runtime, or that Room's schema is
valid. Those need a phone.

Before trusting this in the field, bench it in this order — it matches the way
the code is layered, so a failure at any step is localised:

0. Demo mode, no hardware: everything below except the radio protocols
   themselves. Do this first — it is free, and it flushes out most problems.
1. Meshtastic alone: connect, receive text, send text, node list.
2. MeshCore alone: connect, receive text, send text, contacts.
3. Both at once, sending and receiving on both.
4. Send Via Both.
5. Bridge on, one direction, one channel mapping.
6. Screen off, both radios connected, for an hour.

## Building

```
cd unified-mesh
./gradlew assembleDebug
```

Requirements: JDK 17+, Android SDK with API 36, and network access to
`dl.google.com` and Maven Central.

Minimum Android version is **10 (API 29)**. The `connectedDevice` foreground
service type this app needs arrived in API 29, and going lower would mean
carrying a second legacy Bluetooth permission model for a shrinking share of
devices.

## Running the tests

Everything that matters is a plain JVM test:

```
./gradlew :core:bridge:test :core:radio:test :protocol:api:test \
          :protocol:meshcore:test :protocol:meshtastic:test
```

No emulator, no device, no Android SDK required for those five.

## Trying it without radios: demo mode

**Settings → Demo mode → Use simulated radios.** Then Radios → assign both
slots as usual. The app runs against two in-memory radios instead of Bluetooth:
asynchronous handshake, traffic arriving every twenty seconds, delivery
confirmation, node lists, positions on the map. No hardware, no Bluetooth
permissions, and the whole app is exercised — inbox, composer, Send Via Both,
bridge, notifications, foreground service.

Assignment still goes through scan-then-assign-then-connect, so demo mode
exercises the real screens rather than a special-cased shortcut.

Turn it off and reconnect both radios to go back to real hardware.

## Permissions

- **API 31+**: `BLUETOOTH_SCAN` (declared `neverForLocation`) and
  `BLUETOOTH_CONNECT`. No location permission is requested — the app looks for
  known mesh radios and never derives position from scan results.
- **API 29–30**: `ACCESS_FINE_LOCATION`, because the platform gates BLE scan
  results behind it and offers no alternative.
- **API 33+**: `POST_NOTIFICATIONS`, requested separately from the Bluetooth
  permissions. The radios work without it; the operator just is not told when a
  message arrives.

Each permission carries its own plain-language explanation, defined next to the
code that needs it in `BluetoothPermissions`.

## Security

- The app never reads or stores channel keys. Meshtastic PSKs and MeshCore
  channel secrets arrive during normal operation and are discarded inside the
  protocol layer; no model type has a field they could occupy.
- Nothing sensitive reaches the diagnostics log. It records frame codes, lengths,
  port numbers, connection transitions and bridge decisions — never message text
  or key material — and the export is redacted again on the way out.
- Encrypted Meshtastic packets the radio holds no key for are never treated as
  text, so they cannot surface in the inbox or reach the bridge.
- Cloud backup and device transfer are disabled: a backup of a mesh operator's
  traffic is not something this app should create on their behalf.

## The map

The map plots positions from both networks with `MT`/`MC` markers on a
pan-and-zoom canvas, and **draws no background tiles**. That is deliberate: a
tile source means either a Google Maps API key or a network dependency, and a
mesh app is most useful exactly where there is no network. `MapTileSource` in
`feature/map` is the seam for adding one — an osmdroid implementation with
pre-cached offline tiles slots in underneath without changing anything above it.

It degrades gracefully throughout: many MeshCore contacts and plenty of
Meshtastic nodes never report a position, and the screen says so rather than
implying anyone is at 0,0.

## The bridge

Off by default, at three independent levels (master, direction, per-rule). When
on, it relays **text only** — telemetry, positions, routing, acknowledgements and
device configuration are structurally incapable of reaching it, because the
adapter contract keeps them off the message flow entirely.

Six checks stop a relayed message circulating forever, including an on-air hop
counter that lets *other people's* bridges be recognised. The design and the
reasoning behind each check are in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Protocol provenance

Every UUID, command code and frame layout was transcribed from upstream source
read at a specific commit. [docs/PROTOCOL-NOTES.md](docs/PROTOCOL-NOTES.md)
records exactly which file each constant came from, along with the firmware
behaviours that are easy to get wrong.

Nothing in this codebase was guessed.

## Licence

The vendored Meshtastic protobuf definitions are GPL-3.0, from
[`meshtastic/protobufs`](https://github.com/meshtastic/protobufs); their licence
governs their use. Choose a licence for the app itself before publishing.
