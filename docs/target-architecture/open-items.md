# Open items

Part of the [target architecture](README.md). Tracks underspecification:
every design question raised while writing the spec, whether it is
resolved, and where. Implementation of a step may start once every item
that gates it is resolved.

Update this file in the same commit that resolves an item. Where an item
is tracked as an [issue](issues/README.md), the row only links to it; the
issue file holds the details and status. A spike's
findings go into the spec file it affects, and the item here links to
them.

## Open (gating)

| Item | How it gets resolved | Gates |
|---|---|---|
| AAudio directly or through Oboe | Spike: stream reopening on device change, device quirks, binary size | Step 5b |
| AAudio callback or blocking mode | Spike: glitch rate and latency for both on the developer's device | Step 5b |
| Echo cancellation with AAudio | Spike: allocate an AAudio session ID, attach `AcousticEchoCanceler`, confirm it works on the `VOICE` profile | Step 5b |
| `VoiceLink` contract as implemented | Make the transport the sole owner of the UDP route and format: issues [16](issues/16-voice-link-owns-udp-route.md), [17](issues/17-udp-format-per-packet.md) | Step 5a.3 |
| `Session`/`TransportImpl` virtual-time tests | Add the missing scenarios and invariants: issues [04](issues/04-session-transport-virtual-time-gaps.md), [05](issues/05-client-edge-invariants-weak.md) | Step 5a.3 |
| SCO manager confinement | Confine `BluetoothScoManager` to the route thread: issue [01](issues/01-sco-manager-two-thread-mutation.md) | Step 5a.3 |
| Interop UDP-blocked → tunnel fallback | Add the switch-to-tunnel-mode-when-UDP-is-blocked scenario to `MurmurConnectTest` (the one clause of "Missing interop scenarios" with no covering test; see [testing](testing.md#murmur-interop)) | Step 5a |
| Voice interop harness | Host `libhumlaaudio` extended beyond crypto and loaded by the JVM interop test, driving `VoiceEngine` with synthetic PCM (see [testing](testing.md#murmur-interop)) | Step 5a.2 |

## Open (not gating)

| Item | Notes |
|---|---|
| Jitter slot pool size (1024 slots proposed) | Settled *during* step 5a by the pool stress test in the native suite (see [testing](testing.md#native-host-suite)); part of the step's done-when, not a precondition for starting it |
| TCP reader as a coroutine | Could take `HumlaTCP` off the allowlist; possible after step 8. Until then it stays in `BOUNDARY_FILES` even though the reader thread it is named for now lives in `TransportImpl` (see [issue 31](issues/31-boundary-files-mismatch.md)) |
| Trace replay fixtures | Planned after the harness exists (see [testing](testing.md#test-layers)) |
| `connectEpoch` in `HumlaClient` | A generation counter the design meant to remove; exists because cancelled attempts can surface `TransportException`. Replace with cancellation-first error mapping (e.g. `ensureActive()` before handling) once the step-1 validation can catch a regression |
| Writable `talkState` on ephemeral handles | Documented interim behind the stuck-indicator regression; ends in 5a.3 (remote) and 5a.4 (self) via `VoiceEngine.talkActivity` |
| Upstream-parity codec changes | Found during the first 5a attempt, deferred out of 5a (no behavior changes inside the step): honor `volume_adjustment`; drop the 1.2.2 CELT workaround; empty `opus_data` terminator handling; legacy chaining tolerance. Land after 5a.4 as their own commits |

## Resolved

Items resolved by the step-1 harness work (2026-11-13):

| Item | Resolution | Where |
|---|---|---|
| Step 1 harness validation | Both `HumlaClient` epoch guards disabled: the three stale-callback tests fail 10 of 10; intact on `master`: pass 10 of 10. Throwaway break reverted uncommitted | [testing](testing.md#murmur-interop), [PROGRESS](PROGRESS.md) |
| Missing interop scenarios (part) | `MurmurConnectTest` grows 5 → 11: kick → `Removed` (no reconnect), restart → `Reconnecting` → `Connected`, self-mute/deafen echo with coercion, channel join and move, forced-TCP tunnel, UDP pings; suite green clean and under CPU load. The UDP-blocked → tunnel fallback clause has no covering test and stays open (see the gating row above) | [testing](testing.md#murmur-interop) |

Items resolved by the [step 5a retrospective](history/step5a-retrospective/README.md):

| Item | Resolution | Where |
|---|---|---|
| Route-state semantics | Spec follows code (2026-11-12): `Ready` means decided, not confirmed; `Settling` is never observed; voice opens at once and restarts when SCO comes up. `Fallback` is redefined by intent (SCO requested, not active, a bring-up failed), reachable with a media sink; `RouteChanged` on every published change. Code follow-ups: issues [10](issues/10-route-fallback-unreachable.md), [11](issues/11-route-changed-suppressed-on-recheck.md) | [route](route.md#routestate) |
| 5a sub-step order | Receive first (5a.3), then send (5a.4): verifiable by ear, and keeps the policy push out of the first native I/O slice | [migration](migration.md#step-5a--engine-refactor-codec-network-threads) |

Items resolved by the 2026-11-10 spec review are marked *(review)*.

| Item | Resolution | Where |
|---|---|---|
| `VoiceEngine` scope | Application-scoped (survives reconnects); revisit only if keeping streams and engines across sessions causes problems on device | [voice](voice.md#kotlin-facade) |
| Contradictions and undefined types *(review)* | One `TextMessageSent` shape; the transport alone owns UDP vs tunnel; `VoiceSettings`, `CryptKeys`, `CryptCounts`, `LocalFacts`, `LocalFactsStore`, `WhisperTarget`, `SyncPhase`, `ChannelTree`, `ServerEndpoint`, `VoiceLink` defined | [state](state.md#types), [client](client.md#connecttarget), [client stats](client.md#statistics), [transport](transport.md#responsibilities), [voice](voice.md#policy-and-settings-handoff) |
| Behaviors the first draft dropped *(review)* | Listening-volume message, ignored senders' text, other users' self-mute log, welcome text, bandwidth cap on the encoder, voice target 31, outbound drop-oldest, chat log limits, no chat-visibility suppression, mismatch dialog without pinning | [state](state.md#server-message-transitions), [voice](voice.md#queues), [app](app.md#features), [app screens](app.md#screens) |
| `ChannelRemove` listener cleanup | Whole subtree, an intentional fix of a pre-existing leak | [state](state.md#server-message-transitions) |
| Allowlist counting *(review)* | Matching lines per file; 335 lines in 34 files at `3041a6ca`; boundary files may join only via `BOUNDARY_FILES` | [enforcement](enforcement.md#concurrency-tripwire) |
| Immutability walk scope *(review)* | Stops at `Session`; `ServerEndpoint` replaces the Room entity in `ClientState` | [enforcement](enforcement.md#state-immutability-test) |
| Intermediate steps *(review)* | `ConfigDelta` bridge until step 7; container and features (step 6) before screens and service cut-over (step 7) | [migration](migration.md#ground-rules) |
| Route session identity *(review)* | The controller keeps a private route-session counter for stale-event checks | [route](route.md#thread) |
| Live handles keep screens updating | Spike passed: adapter-over-interfaces (`ChannelAdapterTest` over Mockito `IUser`/`IChannel`) + live handles (`HandlesTest`: live reads, cached scalars, equality, navigation) | Step 2 |
| `kotlinx-collections-immutable` version | Pinned at 0.5.2 at step 2 start; state tests run against it | Step 2 |
| App-layer coverage *(review)* | Port rule plus full settings and feature tables | [app](app.md#port-rule) |
| Round 2 *(review)* | `CryptSetup` cases in today's order; links applied replace, remove, add; channel whisper targets only (user whispers unsupported today); one bandwidth source in `ServerInfo`; `currentBandwidth` from voice status; voice failure table; `ConnectionWarning` event; chat-log move and recording filters; handle member sources, narrowed navigation types, setters removed; `ClientCertificate` as `ByteString`; walk stop rules; native exceptions format and `SINGLE_THREADED_ENGINE` flag; `BOUNDARY_FILES` paths; interim owners, `LegacyVoiceLink`, `VoiceControl`; `checkNotMainThread()` | [state](state.md#server-message-transitions), [transport](transport.md#crypt-setup), [voice](voice.md#failures), [enforcement](enforcement.md#native-tripwire), [migration](migration.md#ground-rules) |
| Single control loop vs alternatives | Rejected; immutable state with atomic updates, one session object, command-only slow components | [README](README.md#rejected-alternatives) |
| Splitting `User` by who decides each field | Rejected; one `UserInfo` with every fact, atomic updates | [state](state.md#types) |
| Deep immutability of state | `kotlinx.collections.immutable` persistent collections plus a reflection test | [state](state.md#rules), [enforcement](enforcement.md#state-immutability-test) |
| Copy cost of immutable state | Persistent collections; publish once at `ServerSync`; batch queued messages | [state](state.md#sync-phases-and-publication) |
| Self-mute: desired vs echoed | Desired value drives the gate; echo updates the model; self-deaf echo always closes the gate; persisted and restored | [state](state.md#self-mute-and-deafen) |
| When informational events fire | Only after `SYNCED`; warnings in every phase | [state](state.md#events) |
| Where chat strings are formatted | In the app, from semantic events; temporary adapter until step 6 | [state](state.md#events), [migration](migration.md#step-6--container-storage-and-features) |
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
| Talk-state bridge between 5a and step 7 | The bridge carries no transition memory: `talkActivity` levels are the sole state (passive when absent), observer callbacks are repaint hints only, and every consumer re-reads the level on use (`UserHandle.talkState` reads `talkActivity.value` live). No dedup store, no edge forwarding; the step-2 fresh-handle regression must not return as a bridge | [state](state.md#compatibility-handles-migration-only), [voice](voice.md#kotlin-facade) |
| Microphone permission missing at voice start | The activity requests permissions before connecting, as today; if the stream still fails to open, the `VoiceEngine` reports `PipelineError` and the session stays connected without audio | [voice](voice.md#failures) |
