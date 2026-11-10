# Open items

Part of the [target architecture](README.md). Tracks underspecification:
every design question raised while writing the spec, whether it is
resolved, and where. Implementation of a step may start once every item
that gates it is resolved.

Update this file in the same commit that resolves an item. A spike's
findings go into the spec file it affects, and the item here links to
them.

## Open (gating)

| Item | How it gets resolved | Gates |
|---|---|---|
| Live handles keep screens updating | Spike (see [migration](migration.md#step-2--immutable-state)) | Step 2 |
| `kotlinx-collections-immutable` version | Pin the current release at step 2 start (pre-1.0 accepted); run the state tests against it | Step 2 |
| Jitter slot pool size (1024 slots proposed) | Pool stress test in the native suite (see [testing](testing.md#native-host-suite)) | Step 5a |
| AAudio directly or through Oboe | Spike: stream reopening on device change, device quirks, binary size | Step 5b |
| AAudio callback or blocking mode | Spike: glitch rate and latency for both on the developer's device | Step 5b |
| Echo cancellation with AAudio | Spike: allocate an AAudio session ID, attach `AcousticEchoCanceler`, confirm it works on the `VOICE` profile | Step 5b |

## Open (not gating)

| Item | Notes |
|---|---|
| `VoiceEngine` application-scoped or per session | Decided: application-scoped (survives reconnects). Revisit only if keeping streams and engines across sessions causes problems on device; measuring the rebuild cost would settle it |
| TCP reader as a coroutine | Could take `HumlaTCP` off the allowlist; possible after step 8 |
| Trace replay fixtures | Planned after the harness exists (see [testing](testing.md#test-layers)) |

## Resolved

| Item | Resolution | Where |
|---|---|---|
| Single control loop vs alternatives | Rejected; immutable state with atomic updates, one session object, command-only slow components | [README](README.md#rejected-alternatives) |
| Splitting `User` by who decides each field | Rejected; one `UserInfo` with every fact, atomic updates | [state](state.md#types) |
| Deep immutability of state | `kotlinx.collections.immutable` persistent collections plus a reflection test | [state](state.md#rules), [enforcement](enforcement.md#state-immutability-test) |
| Copy cost of immutable state | Persistent collections; publish once at `ServerSync`; batch queued messages | [state](state.md#sync-phases-and-publication) |
| Self-mute: desired vs echoed | Desired value drives the gate; echo updates the model; self-deaf echo always closes the gate; persisted and restored | [state](state.md#self-mute-and-deafen) |
| When informational events fire | Only after `SYNCED`; warnings in every phase | [state](state.md#events) |
| Where chat strings are formatted | In the app, from semantic events; temporary adapter until step 7 | [state](state.md#events), [migration](migration.md#step-7--app-cut-over) |
| Client states and transitions | `ClientState` with five states and fixed edges | [client](client.md#clientstate) |
| Reconnect rules | Today's policy, unchanged | [client](client.md#reconnect-policy) |
| TLS trust failures | `Failed(Trust)`, never reconnect; app shows today's dialogs | [client](client.md#clientstate), [transport](transport.md#tls) |
| Teardown order | Voice stop, route end, scope cancel, publish | [client](client.md#ending-a-session) |
| Mapping of `IHumlaSession`/`IHumlaService` | Table of old members to new API | [client](client.md#intents) |
| Transport threads and backpressure | Reader thread with blocking send into a bounded channel; writer coroutine; full outbound queue fails the session | [transport](transport.md#threads) |
| UDP socket ownership | The voice I/O layer; transport decides UDP vs tunnel | [transport](transport.md#responsibilities) |
| UDP/TCP switching thresholds and ping cadence | Today's constants | [transport](transport.md#keepalive-and-udp-health) |
| Engine refactor scope | Single-threaded, results as return values, no allocation after construction; listed allocation sites | [voice](voice.md#engine-refactor) |
| Engine behavior parity | Golden outputs recorded before the refactor | [voice](voice.md#behavior-pinning) |
| Native protobuf for UDP | nanopb (submodule runtime, committed generated code, no allocation); legacy format ported from `PacketBuffer`; both checked against the Java classes | [voice](voice.md#protobuf-through-nanopb) |
| Client-requested crypt resync | Kept as today (5 s since last good, 5 s between requests), now raised by the native receive thread | [transport](transport.md#crypt-setup) |
| When `scripts/test_interop.sh` runs | Never required; recommended when a change touches networking code (transport, client and session, native networking) | [testing](testing.md#murmur-interop) |
| Robolectric in `:libraries:humla` tests | Accepted, for driving today's `HumlaConnection` until step 3 | [testing](testing.md#murmur-interop) |
| Policy and settings handoff to native | Sequence locks, single writer | [voice](voice.md#policy-and-settings-handoff) |
| Realtime rules | Capture and render: no locks, allocation, or Kotlin calls; `sem_post` the only extra system call | [voice](voice.md#realtime-rules) |
| Deafen and playback | Server stops sending to deafened users; client unchanged | [voice](voice.md#voicepolicy) |
| Half duplex | Atomic "self talking" flag in the I/O layer; render writes silence | [voice](voice.md#threads) |
| Route thread and state | `HandlerThread`; `Idle`/`Settling`/`Ready`/`Fallback` with stream profiles | [route](route.md#routestate) |
| Session start sequence | Client sequences lifetimes; voice follows route state; network voice starts immediately | [route](route.md#session-start) |
| Foreground service rules | Started by the UI before connecting; stays foreground through reconnects | [app](app.md#connectionservice) |
| Replacement for each `MumlaService` responsibility | Feature table | [app](app.md#features) |
| Screen ViewModels and `MumlaActivity` duties | Tables | [app](app.md#screens) |
| Settings routing | `ConfigDelta` field table | [app](app.md#settings) |
| Module split | Packages inside `:libraries:humla`; Android-free packages enforced by the architecture check | [enforcement](enforcement.md#android-free-packages) |
| Allowlist format and ratchet | Path plus count; counts may only fall | [enforcement](enforcement.md#concurrency-tripwire) |
| murmur binary and harness | `pkgs.murmur` (`mumble-server`), per-class server, committed test certificate | [testing](testing.md#murmur-interop) |
| On-device verification | At the developer's discretion; snapshot releases are the field test; no checklist | [testing](testing.md#on-device-testing) |
| Android 11–12 | Support stays theoretical; no device testing or spikes | [testing](testing.md#on-device-testing) |
| Progress tracking | `PROGRESS.md` row per step (status, commits, allowlist, notes) | [testing](testing.md#progressmd) |
| Observer compatibility | `IHumlaObserver` deleted in step 8 | [migration](migration.md#step-8--clean-up) |
| Microphone permission missing at voice start | The activity requests permissions before connecting, as today; if the stream still fails to open, the `VoiceEngine` reports `PipelineError` and the session stays connected without audio | [voice](voice.md#outputs) |
