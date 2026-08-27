# Vendored Meshtastic protobuf definitions

These `.proto` files are copied verbatim from
[meshtastic/protobufs](https://github.com/meshtastic/protobufs).

- Upstream commit: `1b4cb00f3d6b0d620354a11fdd1e0b592f3cb7f5` (2026-08-21)
- Files: the transitive import closure of `mesh.proto` — `atak`, `channel`,
  `config`, `device_ui`, `mesh`, `module_config`, `portnums`, `telemetry`,
  `xmodem`.

They are vendored rather than pulled from a published artifact so the build is
reproducible and does not depend on a snapshot repository. **Do not hand-edit
them.** To update, re-copy the closure from upstream at a known commit, record
the new commit here, and run the codec tests — `MeshtasticCodecTest` asserts
against the field numbers and enum values the app actually relies on.

Licensing follows upstream (GPL-3.0). See `docs/PROTOCOL-NOTES.md`.
