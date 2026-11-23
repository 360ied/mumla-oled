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
| AAudio directly or through Oboe | Spike: stream reopening on device change, device quirks, binary size. Reopening must leave both streams started, never closed for longer than today's brief restarts (see [keep-alive shields](voice.md#keep-alive-shields)) | Step 5b |
| AAudio callback or blocking mode | Spike: glitch rate and latency for both on the developer's device. Also the [keep-alive shields](voice.md#keep-alive-shields): the input stream still registers as recording-active, the output stream stays started through silence, and whether callback mode's zero feeding changes how the OS counts playback, observed untethered on battery with the screen off. Decides whether the render idle state exists at all; the [render wake](voice.md#render-idle-and-wake) serves blocking mode and goes unused in callback mode | Step 5b |
| Echo cancellation with AAudio | Spike: allocate an AAudio session ID, attach `AcousticEchoCanceler`, confirm it works on the `VOICE` profile | Step 5b |

## Open (not gating)

| Item | Notes |
|---|---|
| Interop harness growth | Deliverable work inside each sub-step, not a gate: 5a.2 adds the codec JNI to the host `libhumlaaudio` and drives UDP ping framing end to end, with text kept as a no-regression check, 5a.3a adds the voice JNI and the tunnel voice scenario through the native render entry point, 5a.3b adds inbound UDP voice, reconnect with voice, and drives `VoiceEngine`, 5a.4 covers both directions (see [testing](testing.md#murmur-interop)) |
| Legacy chaining tolerance | Deferred by decision (2026-11-20): keep the tolerant chain — frames past the twelfth are dropped and counted — and the [codec](voice.md#packet-codec) text that calls it an intentional divergence from upstream, which rejects a legacy datagram with anything but one frame and an optional 12-byte positional trailer. Revisit only if a real server or client needs the stricter reading |
| Volume adjustment application point | Deferred by decision (2026-11-23): keep per-packet scaling before the mix and unity fallback for invalid wire factors. Upstream applies the last dequeued packet's factor to the whole mixed quantum after its own fades and multiplies negative or non-finite values unchanged; with a constant, valid factor the two agree. Revisit only if a listener-volume change inside one quantum becomes audible |
| Jitter slot pool size (1024 slots proposed) | Settled *during* step 5a by the pool stress test in the native suite (see [testing](testing.md#native-host-suite)); part of the step's done-when, not a precondition for starting it |
| TCP reader as a coroutine | Could take `HumlaTCP` off the allowlist; possible after step 8. Until then it stays in `BOUNDARY_FILES` even though the reader thread it is named for now lives in `TransportImpl` (see [issue 31](issues/31-boundary-files-mismatch.md)) |
| Trace replay fixtures | Planned after the harness exists (see [testing](testing.md#test-layers)) |
| `connectEpoch` in `HumlaClient` | A generation counter the design meant to remove; exists because cancelled attempts can surface `TransportException`. Replace with cancellation-first error mapping (e.g. `ensureActive()` before handling); the step-1 validation now fails the stale-callback tests when either guard is removed (see [testing](testing.md#murmur-interop)) |
| Writable `talkState` on ephemeral handles | Documented interim behind the stuck-indicator regression; ends in 5a.3a for remote and self, through the service-lifetime `talkActivity` publisher that `VoiceEngine` takes over in 5a.3b (see [migration](migration.md#5a3a--native-render)) |
| `HumlaNative` failure reporting and fake native layer | [HumlaNative](../../libraries/humla/src/main/java/se/lublin/humla/HumlaNative.kt) owns the single `System.loadLibrary("humlaaudio")` from 5a.1 and every `external` class goes through it, but its two remaining spec duties need 5a.3b machinery (an item of that sub-step): reporting a load failure as `VoiceEvent.PipelineError` on release builds (the event type arrives with the `VoiceEngine` facade) and JVM tests injecting a fake native layer instead of catching `UnsatisfiedLinkError` (they use zero-handle guards today) |
| Legacy server version plumbing | `VoiceFormat.legacyServerVersion` and the native `setFormat` parameter it feeds are unread now that the 1.2.2 workaround is gone (see [issue 35](issues/35-legacy-server-version-unread.md)) |

## Resolved

Items resolved while closing the 5a codec tail (2026-11-20):

| Item | Resolution | Where |
|---|---|---|
| Honor `volume_adjustment` | Per-packet and unsmoothed: the parser fills `InboundPacket.volumeAdjustment` (`1.0` when the field is `0`, its own "unset", or when a peer sends an invalid factor), the jitter buffer keeps it with the packet, and render applies it to that packet's decoded samples before the mix. FEC and concealment chunks use the voice's last seen factor, so a loss keeps the sender's volume. Legacy packets carry no factor; applying local per-channel volumes against pre-1.5 servers stays out of scope as a separate upstream branch. The two application-point divergences from upstream are recorded above. Pinned by codec fixture parsing and six engine render tests | [voice](voice.md#packet-codec), [voice](voice.md#output-engine) |
| Drop the 1.2.2 CELT workaround | Deleted with its constants and the legacy-version plumbing inside the codec: the legacy format only ever yields `VoiceOpus`, so the ordinal swap was dead, and upstream 1.5 drops non-Opus legacy voice instead. The version stays recorded in `VoiceFormat` and unread; the residual plumbing is [issue 35](issues/35-legacy-server-version-unread.md) | [voice](voice.md#packet-codec) |
| Empty `opus_data` terminator | Dropped, upstream parity: a protobuf `Audio` message with empty `opus_data` yields no frame, with or without `is_terminator`, and outbound protobuf framing refuses to produce one. Our input engine never emits one (a terminator with nothing accumulated encodes a full silence packet), so Mumla OLED peers lose nothing. A legacy peer's empty terminator relayed by a 1.5 server is dropped by upstream clients too; here it ends as a dead-voice expiry about 100 ms later instead of an immediate drain. Legacy empty terminators remain | [voice](voice.md#packet-codec) |

Items resolved while checking the 5a.3 gates (2026-11-14):

| Item | Resolution | Where |
|---|---|---|
| SCO manager confinement | `BluetoothScoManager` runs on the route thread (timers, device listener, API 30 receiver, failure delivery) with plain fields and route-thread assertions on every entry point; nothing reads it from another thread (the service's SCO query reads route state). The route thread uses the looper dispatcher. Detected by `RouteThreadConfinementTest` and `BluetoothScoManagerTest`; the last item gating 5a.3a | [issue 01](issues/01-sco-manager-two-thread-mutation.md), [route](route.md#thread) |
| Output engine lifecycle versus the single-caller rule | While render runs, the render thread is the only caller; the control thread constructs, resets, and destroys the engine only while render is stopped, ordered by `Thread.start`/`join` in 5a and by a completed AAudio stream stop in 5b, with debug assertions. Today already builds the engine before the render thread starts and destroys it after the join, so a reset flag would not make the rule literally true. 5a.3a builds a fresh engine per start and needs no reset | [voice](voice.md#start-stop-reconfigure), [migration](migration.md#5a3a--native-render) |
| Talk state from native to Kotlin | Levels, not events: render folds the engine's talk events into a level table and publishes it with cumulative drop counts through a sequence lock (render the single writer); 5a.4's self talk state is one atomic flag. Replaces the capture and render event queues, whose full-ring drops would lose a not-talking and stick an indicator. A spell under one notifier interval (about 20 ms) can go unseen, harmless since senders hold 150–250 ms and voices expire after 10 missed frames. The notifier keeps its 20 ms tick: the capture thread already wakes every 10 ms for the open mic, so the tick adds little | [voice](voice.md#queues), [migration](migration.md#5a3a--native-render) |
| `talkActivity` writers | From 5a.4 the notifier only. In 5a.3a the notifier, `AudioHandler`'s talking callback (capture thread; `shutdown`'s final not-talking, which 5a.3a moves after the capture join because today `shutdown` resets `mTalking` before joining), and the clear; every write uses `update {}` so concurrent writers cannot lose a change. Self talk events come from the capture thread alone: since 5a.1 `setMuted` only stores config and the engine applies it in `processFrame` (the 'concurrent native callbacks' comment in `AudioHandler.onTalkingStateChanged` is stale) | [voice](voice.md#outputs) |
| Interim push safety in 5a.3a | Interim pushes and `stopPlaying`'s teardown run under `AudioOutput`'s existing monitor: no push reaches a destroyed `voice_io`, and overlapping old and new UDP coroutines stay one producer at a time. No new lock lines; render never takes the monitor | [migration](migration.md#5a3a--native-render) |
| Dropped-packet counts in 5a.3a | Logged until `VoiceStatus` arrives in 5a.3b | [migration](migration.md#5a3a--native-render) |
| `talkActivity` publisher, hints, and self in 5a.3a | Owner: `HumlaService`, built in `onCreate` with `VoiceControl` and `HumlaClient`, passed to both, so `Session` builds handles with it and each `AudioHandler` build feeds it. Repaint hints: on every `talkActivity` change, a hint for every user in the session, with no snapshot comparison (the bridge's no-transition-memory rule from `1fd8940f`, after the stuck-indicator fix `ad164673`). Self: `AudioHandler`'s Kotlin talking callback sets `selfTalking` from 5a.3a, so the writable `talkState` goes entirely in 5a.3a; 5a.4 only changes the source | [migration](migration.md#5a3a--native-render), [state](state.md#compatibility-handles-migration-only), [voice](voice.md#outputs) |
| `talkActivity` after the output engine goes away | The publisher clears remote users after the notifier's final read whenever the engine is destroyed or reset: at Stop and, in 5a.3a, every `AudioHandler` rebuild after a route or settings change. The destructor emits no passive events, `talkActivity` is the only talk-state source, and Murmur reuses session IDs, so stale entries would stick or light the wrong user. Pinned by a rebuild-while-talking test | [voice](voice.md#outputs), [migration](migration.md#5a3a--native-render) |
| Known-sessions set for the [sender filter](voice.md#sender-filter) | `VoicePolicy.knownSessions` beside `locallyMuted`, through the policy sequence lock; native snapshot layout left to 5a.3b's design. Capacity 256; overflow sets a flag that skips the unknown-sender check (fail open) while the muted check still applies. The collector lag is accepted: one hop (microseconds to about a millisecond) against a joining user's sync round trip before their first voice, and at our own connect both paths drop everything until `ServerSync`. Fallback if first syllables are ever lost: `Session` writes the list itself ahead of publishing, through its own single-writer sequence lock | [voice](voice.md#sender-filter) |
| `removeUser` routing to the render thread | The tunnel ring carries it as a control item. `UserRemove` reaches `AudioHandler.messageUserRemove` through `Session.handleMessage` on the session message coroutine, the same coroutine that calls `queueTunnelled`, so the tunnel ring already has the right producer and a new control ring is not needed. A removal stays ordered after that user's tunnelled voice. No slots are reserved for removals: a dropped removal, like a late UDP frame from the leaving user (the same window as today's `AudioOutput` filter), costs only a voice the engine's dead-voice rule releases about 100 ms after its audio runs out. `VoiceLink` and the facade gain `removeUser(session)`, called on the same coroutine; `Session` already uses `VoiceLink` for `queueTunnelled`, `events`, and `status`, and issues 16 and 17 touch only the route default and `setServerVersion` | [voice](voice.md#queues), [migration](migration.md#5a3a--native-render) |

Items resolved while preparing step 5a.3b (2026-11-14, 2026-11-15):

| Item | Resolution | Where |
|---|---|---|
| `VoiceLink` contract as implemented | Issues [16](issues/16-voice-link-owns-udp-route.md) and [17](issues/17-udp-format-per-packet.md). The transport pushes `SetVoiceRoute(!forceTcp)` at `ServerSync` (not at transport start, which a cancelled connect attempt can still reach) and the link only follows; `VoiceCommand.Start` drops its `tunnel` field, and `Stop` resets the route to tunnel so each session starts tunnelled. The transport fixes `VoiceFormat` (UDP format plus legacy version) at the first `Version` and the client passes it in `Start`; UDP ping versions are reported, never applied, and `ServerState.info` is not a format source | [transport](transport.md#keepalive-and-udp-health), [voice](voice.md#kotlin-facade) |
| Render idle and wake | A POSIX semaphore in `voice_io`, entered through `awaitWork(timeoutMs)` between render calls with today's timeouts (none without active voices, 20 ms with). Render sets an `idle` flag and rechecks both rings before waiting; producers push, then post only if they clear the flag, with sequentially consistent ordering on both sides. The post lives in the `voice_io` push functions, so the native receive thread, the tunnel caller, and the interim Kotlin pushes share one wake path with no JNI call. Stop posts after clearing the running flag, replacing the thread interrupt. What the OS sees is unchanged (a thread blocked in a futex, the track started and underrunning, no zeros written), so no [keep-alive shield](voice.md#keep-alive-shields) moves; the pacer is untouched. Lands as the first item of 5a.3b. Serves a 5b blocking-mode render thread as is and goes unused in callback mode. Rejected: a Kotlin wake from native (a JNI call on the receive path needing the same protocol, deleted in 5b) and continuous silence (writes zeros, against the output-stream shield and 5a's no-behavior-change rule, and restores the 50 Hz spin `b1a1118e` removed) | [voice](voice.md#render-idle-and-wake), [migration](migration.md#5a3b--native-receive-and-transport) |
| `Session`/`TransportImpl` virtual-time tests | `SessionTest` and `TransportImplTest` exist, `HumlaClientTest` covers the missing client scenarios, and one invariant checker asserts testing.md's five invariants after every step (extended by issue [34](issues/34-published-policy-not-an-invariant.md) to check the published policy); each pinned guard was shown to fail when broken. `Connecting → Connecting` added to the `ClientState` diagram. Scope cancellation closing the socket stays with issue 02 | Issues [04](issues/04-session-transport-virtual-time-gaps.md#resolution), [05](issues/05-client-edge-invariants-weak.md#resolution) |
| Published `VoicePolicy` as an invariant | `ClientHarness` builds a real `PolicyCollector` on the test dispatcher, fed by a `HumlaService` mirror (`flatMapLatest` over `currentSession`, null maps to the muted fallback); `InvariantChecker` asserts after every step that the latest policy converges to the current session (transmit gate, muted fallback with no session, `locallyMuted`, full convergence). Frozen-mirror self-tests prove the detector fires on a stale transmitting policy; `PolicyCollectorTest` pins self-mute, disconnect, and user-removal transitions | [issue 34](issues/34-published-policy-not-an-invariant.md) |

Item resolved by the 5a.3 re-plan (2026-11-14):

| Item | Resolution | Where |
|---|---|---|
| 5a.3 re-plan | Split into 5a.3a (native render) and 5a.3b (native receive and transport). 5a.2's inbound JNI returns parsed frames to Kotlin, which filters and hands each frame back to the output engine through a bridge mutex shared by three threads, while the render thread calls into Kotlin for talk state; 5a.3a removes both without touching the network. Render-first keeps Kotlin parsing and the sender filter, pushing surviving frames into the inbound and tunnel rings through two interim calls (one per producer), so no sequence locks arrive until the receive half. `talkActivity` is published from 5a.3a by a production class `VoiceEngine` takes over, not a partial facade. 5a.3b stays one sub-step: OCB2 and framing are already native and the 20 s fallback stays in `TransportImpl`, so the new code is the socket, receive thread, lifecycle, and facade; its risk is pinned by done-when items for crypt-count parity (fixture recorded from the Kotlin path before deletion), resync recovery, and clean stop and reconnect. `HumlaUDP`, Kotlin `CryptState`, and `NativeCryptStateJni.cpp` are deleted in 5a.3b, not 5a.4, since they have no callers once the socket is native. Prerequisites retargeted: issue 01 before 5a.3a; issues 04/05, 16, 17 and the known-sessions set before 5a.3b | [migration](migration.md#5a3--native-receive) |

Items resolved while preparing step 5a.2 (2026-11-13):

| Item | Resolution | Where |
|---|---|---|
| Interop PCM injection before `voice_io` | No test-only seam, and no PCM round-trip in 5a.2. The live-path check moves to the encode framing entry point: `AudioHandler` stops acquiring devices in its constructor, so the encoder is driven from a JVM test and compared byte-for-byte with the Java-generated classes and `PacketBuffer`, failing when the native codec is absent or wrong. Inbound framing is pinned by the codec goldens instead: `AudioOutput(listener)` constructs device-free, but its parse path returns unless playback is live, and 5a.3 replaces that path with the native receive, so a device-free parse entry point here would be deleted one sub-step later. End-to-end coverage in 5a.2 is a UDP ping round trip, which needs no PCM and goes red if native ping framing is absent or wrong. Voice between two clients over the tunnel moves to 5a.3, where synthetic PCM enters at the native capture and render entry points — production API, not a hook. Rationale: with capture still Kotlin and no `VoiceEngine`, a 5a.2 PCM round-trip runs the same codec at both ends, so it proves self-consistency rather than wire parity, while the seam it needs lives in the classes 5a.3 rewrites | [testing](testing.md#murmur-interop), [migration](migration.md#step-5a--engine-refactor-codec-network-threads) |

Item resolved while implementing step 5a.1:

| Item | Resolution | Where |
|---|---|---|
| RNNoise enable transition in the 5a.4 settings push | Settled as a restart, not a live push: the switch follows the model, which allocates the same way, so both are applied on the control plane when the model loads — engine creation or a capture restart — and neither is in `VoiceSettings`. A settings-driven toggle would need a state snapshot restored allocation-free; deliberately not pursued | [voice](voice.md#input-engine), [voice](voice.md#start-stop-reconfigure) |
| Push-to-talk press and release inside one frame | Accepted as intended, the step's one intentional behavior change: per-frame configuration cannot observe an edge no frame sampled, and a press shorter than the 10 ms frame it would fill is not a transmission attempt. The pre-refactor engine's 150 ms hangover for it is not reproduced; a press that any frame observes still arms the full hangover. Pinned by `testPttSubFrameTapTransmitsNothing` | [voice](voice.md#input-engine), [migration](migration.md#step-5a--engine-refactor-codec-network-threads) |

Items resolved by the step-1 harness work (2026-11-13):

| Item | Resolution | Where |
|---|---|---|
| Step 1 harness validation | Both `HumlaClient` epoch guards disabled: the three stale-callback tests fail 10 of 10; intact on `master`: pass 10 of 10. Throwaway break reverted uncommitted | [testing](testing.md#murmur-interop), [PROGRESS](PROGRESS.md) |
| Missing interop scenarios | `MurmurConnectTest` grows 5 → 13: kick → `Removed` (no reconnect), restart → `Reconnecting` → `Connected`, self-mute/deafen echo with coercion, channel join and move, forced-TCP tunnel, UDP pings, and both UDP-blocked → tunnel fallback triggers (never-open UDP voice switching on the 20 s giveup, plus a UDP socket that cannot be opened switching at once with the session surviving on the tunnel); suite green clean and under CPU load | [testing](testing.md#murmur-interop) |

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
| Robolectric in `:libraries:humla` tests | Accepted. Added for driving today's `HumlaConnection` until step 3; also used for `AudioOutput` talk state and for the SCO manager and route-thread confinement tests (issue 01) | [testing](testing.md#murmur-interop) |
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
