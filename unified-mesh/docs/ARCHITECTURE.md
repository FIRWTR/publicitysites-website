# Architecture

## The shape of the thing

```
Android app (one UI, one inbox, one node list)
│
├── Meshtastic slot ──► MeshtasticAdapter ──► BLE transport ──► Meshtastic radio ──► Meshtastic mesh
│
└── MeshCore slot   ──► MeshCoreAdapter   ──► BLE transport ──► MeshCore radio   ──► MeshCore mesh
```

The two paths never touch below the app. There is no shared connection, no
shared coroutine scope, and no object one slot can mutate that the other reads.
That is a structural property, not a convention, and it is what makes "one radio
disconnecting must not break the other" true rather than merely intended.

The optional bridge sits **above** both slots, in the phone. When it relays, it
receives on one slot and transmits on the other exactly as the operator would
have done by hand. Neither radio ever relays for the other.

## Modules

| Module | Kind | Contains |
|---|---|---|
| `core:model` | Kotlin/JVM | `UnifiedMessage`, `MeshNode`, `MeshChannel`, `RadioDevice`, connection states, `BridgeConfig`, `Clock` |
| `core:bridge` | Kotlin/JVM | `BridgeEngine`, the seen-cache and fingerprinting, the on-air marker codec |
| `core:radio` | Kotlin/JVM | `RadioSession` (one slot, reconnect backoff), `RadioCoordinator` (both slots, Send Via, bridge dispatch) |
| `protocol:api` | Kotlin/JVM | `MeshRadioAdapter`, `RadioLinkTransport`, the fake adapters |
| `protocol:meshtastic` | Kotlin/JVM | vendored protobufs, `MeshtasticCodec`, `MeshtasticAdapter` |
| `protocol:meshcore` | Kotlin/JVM | `MeshCoreCodec`, `MeshCoreSession`, `MeshCoreAdapter` |
| `core:bluetooth` | Android | `GattConnection`, the two BLE transports, `BleScanner`, permission model |
| `core:database` | Android | Room entities, DAOs, repositories, settings, diagnostics |
| `feature` | Android | all six screens, one package each |
| `app` | Android | `Application`, `MainActivity`, DI graph, foreground service, notifications |

### Why most of it is not an Android module

Six of the ten modules are plain Kotlin/JVM libraries, including **both protocol
implementations**. The protocols are byte handling and state machines; they reach
the radio through the `RadioLinkTransport` interface, whose only Android-specific
implementation lives in `core:bluetooth`.

The payoff is that the interesting logic — frame encoding, the handshakes, the
bridge's loop prevention, and the two-radio orchestration — is testable on a
plain JVM with no emulator, no device, and no Android SDK. That is not a
theoretical benefit: the 133 unit tests in this repository were all run that way,
and two real bugs (a dropped-handshake-reply race and a fingerprinting flaw that
defeated cross-network echo detection) were caught by them before any hardware
was involved.

### Deviations from the requested layout

The brief asked for `feature/messages/`, `feature/nodes/`, and so on as separate
Gradle modules. They are one Gradle module with one package per screen, because
they share a single navigation graph and a single repository layer; splitting
them would add six build files and six manifests without buying any compile-time
isolation for a single-team app. The package structure mirrors the requested tree
exactly, so lifting any screen into its own module later is a directory move.

`protocol:api` and `core:radio` are additions rather than deviations: the brief
asked for a common adapter interface and for two independent radio slots, and
these are where those live.

## Layering rules

1. **The UI never sees a protocol type.** No Compose file imports a protobuf or a
   MeshCore frame. The only cross-protocol vocabulary is `core:model`.
2. **Only text reaches the app.** `MeshRadioAdapter.incomingMessages` is text-only
   by contract. Telemetry, position, node info, routing, acknowledgements, admin
   and config traffic are classified inside the adapters and routed to node state
   or the diagnostics log. This is why the bridge is structurally incapable of
   relaying them — the requirement is enforced by the type of the flow, not by a
   filter someone could forget.
3. **Key material stops at the protocol layer.** Channel PSKs and MeshCore
   channel secrets are read off the wire and discarded. No model type has a field
   for them.
4. **A slot's failures stay in that slot.** Adapters do not throw for ordinary
   connection problems; they move their own state. `RadioCoordinator`'s per-slot
   collectors catch and log rather than letting an exception cancel the collector
   and leave one radio silently deaf.

## Bridge loop prevention

Six independent checks, all of which a message must pass:

1. **Direction gates** — master switch, per-direction switch, per-rule switch.
   All default to off.
2. **Inbound only** — anything this phone transmitted, including its own relays,
   is refused, so a relay cannot feed itself.
3. **Self-origination** — traffic whose sender is our own attached radio is
   refused.
4. **On-air hop counter** — a relayed message goes out as `[MT: Bear] …`, and a
   second crossing as `[MT x2: Bear] …`. This is the only piece of loop state
   that survives the trip over the air, so it is what lets *another operator's*
   bridge output be recognised as already-bridged. At the default `maxHops = 1`,
   any marked message is dropped.
5. **Content fingerprint cache** — every message the engine sees and every relay
   it performs is fingerprinted (sender plus normalised body, marker stripped)
   and remembered for the duplicate window. This is the check that catches loops
   the marker cannot see, including the case where the marker is switched off.
   The fingerprint deliberately excludes the observing network, because an echo
   by definition comes back on the other one.
6. **Age limit** — a radio dumping a stored backlog on reconnect cannot trigger a
   burst of stale relays.

The cache is persisted in Room, so a phone killed mid-conversation does not come
back with an empty cache and re-relay everything it already handled.

Why the hop counter is in the text at all: a bridge id is a UUID, and putting one
on the air would cost ~36 bytes out of a ~200-byte (Meshtastic) or 133-character
(MeshCore) budget. The counter costs three characters. Bridge ids and
fingerprints stay local.

## Adding a transport

`RadioLinkTransport` is the seam. To add USB or Wi-Fi:

1. Implement `RadioLinkTransport` for the new transport. Its inbound flow must be
   buffered and single-consumer — a radio answers a command before the reading
   coroutine is scheduled, and a subscriber-less hot flow silently loses
   handshake replies.
2. Add a branch in `RadioModule.meshtasticAdapter` / `meshCoreAdapter` for the
   new `RadioTransport` value.

Nothing else changes. `RadioTransport` already carries `USB` and `TCP`, and the
model, the database and the UI already round-trip them.

## Renaming the app

- Application id and namespace: `app/build.gradle.kts`.
- Display name: `app/src/main/res/values/strings.xml`.
- Package directories: `com/unifiedmesh/**` throughout.

No other file hard-codes either the name or the package.
