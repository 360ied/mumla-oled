# Testing and verification

Part of the [target architecture](README.md). Specifies the test layers,
the murmur interop harness, the native suite additions, the on-device
checks, and how results are recorded.

Implementation is fast; verification is the bottleneck. Every migration
step is paced by these checks, not by how quickly code can be written.

## Test layers

| Layer | Runs in | Speed | Covers |
|---|---|---|---|
| State transitions | `testFossDebugUnitTest` | Fast | Every server message and intent as a pure function |
| Virtual time | `testFossDebugUnitTest` | Fast | `HumlaClient`, `Session` with a fake transport, `RouteControl`, voice control with a fake native layer, the policy collector |
| Native host suite | `check.sh` (existing `test_native_audio.sh`) | Medium | Engines, codec, OCB2, queues, loopback |
| murmur interop | `check.sh` | Slow | Real TLS, real server, real reconnects |
| On-device | Manual, recorded | Slowest | Audio devices, Bluetooth, Android service rules |

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
- every event, including that informational events are not emitted before
  `SYNCED`.

## Virtual-time tests

Under `kotlinx-coroutines-test`, with fakes for `Transport`,
`NetworkMonitor`, `AudioPlatform`, `ScoLink`, and the native voice layer.
[`AudioRouteControllerTest`](../../../libraries/humla/src/test/java/se/lublin/humla/audio/AudioRouteControllerTest.kt)
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
- route flaps mid-connect, SCO failure while `Settling`, `RetrySco`.

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
[`test_native_audio.sh`](../../../scripts/test_native_audio.sh):

- **Golden outputs** (recorded before the step 5a refactor, from today's
  engines): input sequences covering speech, silence, VAD hold, PTT
  toggles, mid-utterance mute, and pre-speech flush; output sequences
  covering in-order, reordered, lost, terminator-only, and 33 concurrent
  senders (forcing eviction). Stored as binary fixtures under the native
  test directory. The refactored engines must reproduce them exactly.
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

**Harness:** JUnit tests in `:libraries:humla` tagged with a
`MurmurInterop` category, excluded from `testFossDebugUnitTest` and run by
a dedicated Gradle test task (`murmurInteropTest`) that `check.sh` calls.
Each test class:

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
- wrong server password → `Rejected`, no reconnect;
- kick by SuperUser → `Removed`, no reconnect;
- server stopped and restarted on the same port → `Reconnecting` with the
  expected delays, then `Connected`;
- forced TCP; UDP pings and the switch to tunnel mode when UDP is blocked;
- from step 5a: voice between two clients over UDP and over the tunnel.

**Harness validation (step 1's done-when).** The harness must catch a
real past race. Take the stale-callback race fixed in `2e2a067d` ("bump
generation before old teardown"): in a throwaway worktree, revert that part
of the fix, run the matching scenario (connect, then connect again while
the first connection is still tearing down), and confirm it fails. Record
the result in `PROGRESS.md`. If no past race can be reproduced, the
harness cannot vouch for the later steps (see
[migration](migration.md#kill-criteria)).

## On-device checks

Run on at least one device on the oldest supported Android version (11)
and one on the newest. Each step's checks come from the existing torture
list (SCO flap, A2DP stagger, overlay drag and rotate, notification paths)
plus:

| Step | Checks |
|---|---|
| 2 Immutable state | Large server join (hundreds of users); channel list stays live while users move; comment and avatar fetch |
| 3 Client and session | Network drop and reconnect with the app backgrounded; Wi-Fi to mobile handover; reconnect countdown in the notification |
| 4 Voice control and route | SCO connect, flap, and failure at connect; A2DP stagger; mute during SCO settle; wired headset plug and unplug |
| 5a Engine refactor and network threads | Voice both directions on UDP and in tunnel mode; crypt resync after a network change; glitch rate against today's baseline |
| 5b AAudio | Latency and glitch rate against the 5a baseline; echo cancellation on speaker; SCO and wired routing on Android 11 and the newest version |
| 6 Screens | Rotation and backgrounding on every connected screen; no updates after a screen closes |
| 7 App cut-over | Connect from the UI on Android 14+; talk broadcast and notification actions while connected and while not; process death while connected |

## `PROGRESS.md`

Created in step 1 next to this file. One row per step, updated in the
step's final commit:

```markdown
| Step | Status | Commits | Allowlist (files / matches) | On-device results |
|---|---|---|---|---|
| 1 Harness and gate | done | abc1234..def5678 | 31 / 330 | n/a (harness validated against 2e2a067d) |
| 2 Immutable state | in progress | | | |
```

On-device results list the device, Android version, and pass or fail per
check. A step is not done until its row is filled in.
