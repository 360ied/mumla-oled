# Testing and verification

Part of the [target architecture](README.md). Specifies the test layers,
the murmur interop harness, the native suite additions, on-device testing,
and progress tracking.

Implementation is fast; automated verification is what makes it safe.
Every migration step's done-when list is checked by the layers below.

## Test layers

| Layer | Runs in | Speed | Covers |
|---|---|---|---|
| State transitions | `testFossDebugUnitTest` | Fast | Every server message and intent as a pure function |
| Virtual time | `testFossDebugUnitTest` | Fast | `HumlaClient`, `Session` with a fake transport, `RouteControl`, voice control with a fake native layer, the policy collector |
| Native host suite | `check.sh` (existing `test_native_audio.sh`) | Medium | Engines, codec, OCB2, queues, loopback |
| murmur interop | `scripts/test_interop.sh` (never `check.sh`) | Slow (a minute or more) | Real TLS, real server, real reconnects |
| On-device | Manual, at the developer's discretion | Slowest | Audio devices, Bluetooth, Android service rules |

## State-transition tests

For each handler ported from `ModelHandler`, a test feeds protobuf messages
into the transition and asserts on the resulting `SessionState`. The
existing `ModelHandlerTopologyTest`, `ModelHandlerSelfMuteTest`,
`ModelHandlerUserRemoveTest`, `ChannelLinksTest`, and `ModelTest` are
ported first, so the transitions start out pinned to today's behavior.
Additional cases:
- `ChannelRemove` of a subtree with users and listeners inside;
- `PermissionQuery` with and without `flush`;
- self `UserState` echoes against every combination of desired and echoed
  self-mute and self-deafen, including the deaf-only fail-closed case;
- every field-level detail listed under
  [server message transitions](state.md#server-message-transitions)
  (cycle and self-parent guards, placeholder parents, re-sorting, root
  fallback, hash-driven clearing, link replace/add/remove);
- a text message from a locally ignored sender produces no event;
- local mute and ignore applied from `LocalFacts` when a registered user
  appears;
- whisper target registration up to 30, the 31st returning `-1`, and
  freeing;
- listening add, remove, and volume intents and their outbound messages;
- the bandwidth cap lowering bitrate and frames per packet
  (`computeEffectiveConfig`), with `AudioBandwidthDegradationTest` ported;
- every event, including that informational events are not emitted before
  `SYNCED`.

## Virtual-time tests

Under `kotlinx-coroutines-test`, with fakes for `Transport`,
`NetworkMonitor`, `AudioPlatform`, `ScoLink`, and the native voice layer.
[`AudioRouteControllerTest`](../../libraries/humla/src/test/java/se/lublin/humla/audio/AudioRouteControllerTest.kt)
is the model.

Scenarios:
- disconnect at every message boundary of the sync sequence;
- `connect` to another target while connected, while connecting, and while
  reconnecting;
- reconnect racing a user disconnect and a `cancelReconnect`;
- network-available events during each `ClientState`;
- the reconnect delay sequence and counter rules (from
  `ReconnectBackoffTest`);
- mute, deafen, and PTT spam during connect and disconnect;
- route flaps mid-connect, SCO failure during the first bring-up (`Ready` → `Fallback`), `RetrySco` from `Fallback`;
- `Reject`, removal of self, and a trust failure each ending in `Failed`
  with no reconnect;
- forced TCP from `ServerSync`, the UDP-to-tunnel switch and back, and a
  `CryptResyncRequested` event producing exactly one empty `CryptSetup`;
- a full transport outbound channel failing the session.

Invariants asserted after every step of every scenario:
- at most one live session; nothing runs in a cancelled session's scope;
- `VoiceCommand.Stop` is submitted before `RouteCommand.SessionEnded`;
- no `VoicePolicy` allows transmit while `effectiveSelfMute` is true or the
  user is server-muted, deafened, or suppressed;
- every user's `channelId` refers to an existing channel, except
  transiently after `ChannelRemove`;
- `ClientState` transitions only along the edges in
  [client](client.md#clientstate).

## Native host suite

Additions to the existing suite run by
[`test_native_audio.sh`](../../scripts/test_native_audio.sh):

- **Golden outputs** (recorded before the step 5a refactor, from today's
  engines): input sequences covering speech, silence, VAD hold, PTT
  toggles, mid-utterance mute, and pre-speech flush; output sequences
  covering in-order, reordered, lost, terminator-only, and 33 concurrent
  senders (forcing eviction). Stored as binary fixtures under the native
  test directory (`libraries/humla/src/test/fixtures/engine-goldens/`) and
  re-recorded only by an explicit, reviewed run
  (`MUMLA_RECORD_ENGINE_GOLDENS=<dir>`). The refactored engines must
  reproduce them exactly.
- **No steady-state allocation:** the test build replaces global
  `operator new`/`delete` and `malloc`/`calloc` with counting versions;
  after warm-up, calls to `processFrame`, `queuePacket`, and `render` must
  not change the count.
- **Jitter slot pool:** 32 voices with heavy jitter adaptation for 60
  simulated seconds; the pool never runs out, or the test reports the
  peak so the pool size can be set from data.
- **Codec:** frame and parse both formats; byte-for-byte equality with
  fixtures produced by the Java-generated `MumbleUDP` classes and today's
  `PacketBuffer` encoding (the fixtures are generated once by a JVM test
  and checked in).
- **Loopback:** PCM → input engine → codec → OCB2 encrypt → OCB2 decrypt →
  codec → output engine → PCM, no socket or device.
- **Queues:** SPSC rings under a multi-million-item stress test with real
  threads, checking order and no loss below capacity.
- **Sequence lock:** a writer and three readers on real threads; readers
  never observe a torn value.

## murmur interop

**Dev shell:** `pkgs.murmur` is added to the flake's `buildInputs` in step
1. Its binary is `mumble-server` (version 1.5.939 in the flake's nixpkgs on
2026-11-10).

**Where it runs:** its own script, `scripts/test_interop.sh`, like
`scripts/test_native_audio.sh`. It re-executes itself inside the nix dev
shell as `check.sh` does, and runs a dedicated Gradle test task
(`murmurInteropTest`). `check.sh` never calls it: `check.sh` is the fast
mandatory pre-commit gate, and a suite that takes a minute or more does not
belong there.

**When to run it:** never required. It is recommended when a change touches
networking code: the transport, the client and session, or the native
networking in `voice_io`. Step 1's harness validation (below) is the one
place the spec runs it as part of a done-when.

**Harness:** JUnit tests in `:libraries:humla` tagged with a
`MurmurInterop` category, excluded from `testFossDebugUnitTest` and every
other default test task. Each test class:

1. picks a free loopback port;
2. writes a temporary `murmur.ini` (loopback host, that port, a temporary
   SQLite database, a test certificate and key from test resources,
   `welcometext`, and a server password where a test needs one);
3. sets the SuperUser password with `mumble-server -ini <ini> -readsupw`
   (password on standard input, not on the command line);
4. starts `mumble-server -fg -ini <ini>` and waits until the port accepts
   connections;
5. stops the process after the class (and on JVM shutdown).

The test certificate is self-signed for `127.0.0.1`, committed under test
resources, and trusted by the test's client configuration. It is test
material only.

Until step 3, the harness drives today's `HumlaConnection` under
Robolectric's paused main looper (Robolectric is added to
`:libraries:humla`'s test dependencies, at the version `:app` already
pins). From step 3 on, it drives `HumlaClient` directly.

**Scenarios:**
- connect, sync, disconnect; two clients see each other;
- channel join and move (SuperUser creates channels);
- text message in both directions;
- self-mute and self-deafen echo, including coercion;
- server password accepted at connect; wrong server password →
  `Rejected`, no reconnect;
- kick by SuperUser → `Removed`, no reconnect;
- server stopped and restarted on the same port → `Reconnecting` with the
  expected delays, then `Connected`;
- forced TCP; UDP pings; and both switch-to-tunnel triggers — the 20 s
  zero-counter giveup and a UDP socket that cannot be opened at all
  (`UdpUnavailable`, switch at once);
- from step 5a.2: text and tunnelled ping framing carried end-to-end
  through the native codec (no audio device involved);
- from step 5a.3: voice between two clients over UDP and over the tunnel
  (inbound UDP from 5a.3, both directions from 5a.4;
  see [migration](migration.md#step-5a--engine-refactor-codec-network-threads)).

**Voice scenarios run the real native path.** The host build of
`libhumlaaudio` (crypto JNI only today) grows with the step: the codec JNI
arrives in 5a.2, the voice JNI in 5a.3. The interop test loads it and
drives whatever is live at that point. 5a.2 adds no voice scenario: with
capture still Kotlin and no `VoiceEngine`, a PCM round-trip would run the
same codec at both ends and prove self-consistency rather than wire
parity, and injecting the PCM would need a test-only seam in
`AudioHandler` that 5a.3 deletes. The native codec is instead driven live
at the production framing entry points (JVM test, byte-equality against
the Java-generated classes) and end-to-end through the tunnel (text and
pings). From 5a.3 the interop test injects synthetic PCM at the native
capture and render entry points, which are production API. A stub voice link
is acceptable only for scenarios that do not claim to cover voice, and
must say so in its name.

**Native loading on Android.** A debug build fails loudly at startup if
`HumlaNative.ensureLoaded()` fails, so daily use of any snapshot catches a
missing library immediately.

**Harness validation (step 1's done-when).** The harness must catch a
real past race. Take the stale-callback race fixed in `2e2a067d` ("bump
generation before old teardown"): in a throwaway worktree, revert that part
of the fix, run the matching scenario (connect, then connect again while
the first connection is still tearing down), and confirm it fails. Record
the result in `PROGRESS.md`. If no past race can be reproduced, the
harness cannot vouch for the later steps (see
[migration](migration.md#kill-criteria)).

Run 2026-11-13 on `master` (`f5973f71`): with both stale-attempt guards
in `HumlaClient` disabled (`if (false)` at the `runAttempt` bug guard and
the `onConnectFailed` epoch guard), all three stale-callback tests
(`staleConnectFailureDoesNotClobberNewerAttempt`,
`staleEqualTargetFailureDoesNotClobberNewerAttempt`,
`staleBugFailureDoesNotClobberNewerAttempt`) fail 10 of 10 runs; with the
guards intact they pass 10 of 10. The
throwaway break was reverted without committing. Recorded in
[`PROGRESS.md`](PROGRESS.md).

## On-device testing

Mumla OLED is a one-developer hobby project, and the developer is the main
user of the latest version and the only user of snapshot releases. On-device
testing is therefore **at the developer's discretion**: anything from a
careful pass to a quick smoke test, with snapshot releases in daily use as
the field test. Nothing in this spec requires a device checklist.

Because of that, the automated layers above carry the verification load.
They are what every step's done-when list relies on.

When testing on a device, the steps most likely to show problems only
there are:
- **step 4**, Bluetooth headset routing (connect, flap, failure at
  connect, mute while the route settles);
- **step 5b**, AAudio latency, glitches, and echo cancellation on the
  speaker.

Android 11–12 support (the minSdk 30 floor) is theoretical and stays
untested; the spec does not plan device work for it.

## `PROGRESS.md`

Created in step 1 at
`docs/target-architecture/PROGRESS.md`, as the
`HumlaService` decomposition did. One row per step, updated in the step's final commit:

```markdown
| Step | Status | Commits | Allowlist (files / matches) | Notes |
|---|---|---|---|---|
| 1 Harness and gate | done | abc1234..def5678 | 34 / 335 | harness validated against 2e2a067d |
| 2 Immutable state | in progress | | | |
```

Notes are free-form (spike findings, deferred items, anything noticed on a
device).
