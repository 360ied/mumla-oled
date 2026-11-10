# Target Architecture

Snapshot: `master` at `08651804`, 2026-11-10. Citations name files and
symbols, not line numbers.

Status: proposal. No code exists for any of this. Nothing here authorizes
work; each migration step starts only on explicit request.

## Relationship to the whales list

The [whales list](README.md) is deliberately *satisficing*: the smallest
structural fix for each family of symptoms. This document is the
*optimizing* counterpart: the architecture Mumla OLED would have if it were
designed today, and a path to it. Both share the whales list's admission
bar and its refusal to sweep krill.

## Design goal

Make the correct path the easy path. Today, staying correct in almost any
part of the app means remembering concurrency rules (lock order, which
thread may call what, whether a session or view is still current). In this
architecture those rules are carried by the structure: ownership, lifetimes,
and thread placement are decided once, and ordinary code cannot get them
wrong without visibly breaking the design.

Implementation is not the bottleneck: the `HumlaService` decomposition took
a day and the Java-to-Kotlin migration three. **Verification is.** The plan
below is paced by what can be checked, not by what can be written.

## Overview

```mermaid
flowchart TB
    subgraph App["Application scope"]
        ROOT["AppContainer<br/>(composition root)"]
        CL["HumlaClient"]
        VE["VoiceEngine<br/>(native voice path)"]
        RC["RouteControl"]
        ST["Settings + database"]
        FEAT["Features<br/>(notifications, TTS, chat log,<br/>overlay, PTT sounds, ...)"]
        FG["ConnectionService<br/>(foreground lifetime only)"]
    end

    subgraph Sess["Session scope (one per connection)"]
        SE["Session"]
        TR["Transport<br/>(TLS/TCP control)"]
        SS[("SessionState")]
    end

    subgraph Screen["Screen scope"]
        VM["ViewModels"]
        V["XML views"]
    end

    SRV(["Mumble server"])

    ROOT --> CL & VE & RC & ST & FEAT & FG
    CL -->|creates, cancels| SE
    CL -->|start, stop| VE
    CL -->|session started, ended| RC
    SE --> TR
    SE --> SS
    VM -->|intents| CL
    SS -.->|state| VM
    VM -.-> V
    SS -.->|state, events| FEAT
    CL -.->|client state| FG
    SS -.->|voice policy| VE
    RC -.->|route state| VE
    TR <-->|TCP| SRV
    VE <-->|UDP voice| SRV
```

Solid arrows are calls or ownership; dotted arrows are state or events
that the receiver collects.

There are three lifetimes: the **application**, a **session** per
connection, and a **screen** per visible UI. Everything belongs to exactly
one of them and is cleaned up when its lifetime ends.

There are two languages with a deliberate line between them. **Kotlin owns
decisions** (who is muted, who we whisper to, which route, whether we are
connected). **Native code owns the voice path** (capture, processing,
encoding, packet framing, encryption, UDP, decoding, mixing, playback).
Kotlin tells native code what it may do; native code never asks Kotlin
anything per frame.

## Components

### `AppContainer` (composition root)

Created in `MumlaApplication`. Constructs every application-scoped object
by hand and passes dependencies through constructors: settings repository,
database, `HumlaClient`, `VoiceEngine`, `RouteControl`, and the feature
collectors. Screens obtain what they need from it through a ViewModel
factory. There is no DI framework.

### `HumlaClient`

The single entry point for connecting. Application-scoped.

- Holds the current connection as `StateFlow<ClientState>`:
  `Idle`, `Connecting(server, attempt)`, `Connected(session)`,
  `Reconnecting(server, attempt, lastState)`, `Failed(error)`.
- `connect(server)` cancels any current `Session` and creates a new one.
  `disconnect()` cancels it.
- Owns the reconnect policy. When a session fails, the client waits out the
  backoff and creates a fresh `Session`. During that time
  `Reconnecting.lastState` keeps the last channel tree available so screens
  can keep showing it.
- Sequences lifetimes across components: it starts and stops the
  `VoiceEngine` and tells `RouteControl` when a session starts and ends (see
  [session start and audio routing](#session-start-and-audio-routing)).
- Holds no protocol state itself.

### `Session`

Everything that exists for one connection, and nothing that outlives it.

- Created with its own `CoroutineScope`, a child of the client's scope.
  Every coroutine and timer for the connection runs in that scope.
- All fields are non-null `val`s set at construction: the `Transport`, the
  state flow, the event flow. Nothing is reset or reused.
- Applies server messages to its state in arrival order, in one coroutine
  that drains the transport's inbound channel. The protocol handlers that
  live in [`ModelHandler`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.kt)
  today become functions from (state, message) to new state.
- Exposes user intents as plain methods: `setSelfMute`, `setSelfDeafen`,
  `setPtt`, `joinChannel`, `sendText`, `setWhisperTarget`, `setLocalMute`,
  and so on. Each updates state atomically and, where the protocol needs
  it, enqueues a message to the server.
- On start, loads the local mute/ignore lists for this server from the
  database. Local mute changes are written back off the main thread.

**What ending a session stops, and how.** Coroutines in the session scope
and anything sent through its channels stop when the scope is cancelled.
Three things are not covered by cancellation alone and are handled
explicitly:
- The TCP reader thread is blocked in a socket read, which cancellation
  cannot interrupt. The scope's completion handler closes the socket,
  which unblocks the read; the reader's final send into a closed channel
  fails harmlessly.
- The `VoiceEngine` outlives sessions. The client stops it (closing the
  native UDP socket and discarding the session's crypt state) *before* the
  session's transport closes, and starts it again with the new session's
  socket and keys. Native code therefore never holds a reference into a
  dead session.
- Platform callbacks (route, network) go to application-scoped components,
  never to a session.

### `SessionState`

One immutable value per session, held in a `MutableStateFlow`:

```kotlin
data class SessionState(
    val server: ServerState,   // channels, users, permissions, server settings, synced flag
    val local: LocalState,     // PTT pressed, whisper target, pending self-mute/deafen proposals
)
```

`User` and `Channel` become immutable data classes inside `ServerState`.
A `User` carries every fact consumers need about that user (server mute,
self mute, local mute, volume), whoever decided it. Any writer changes
state with `state.update { it.copy(...) }`, an atomic swap. Readers on any
thread read `state.value` and get a complete, consistent value.

During the initial join, the session builds `ServerState` privately and
publishes it once when the server sends `ServerSync` (the protocol's
end-of-initial-state message). After that, each change publishes a new
value.

Things that must never be dropped (text messages, permission denied, user
joined or left, server rejected) are also emitted on a separate
`SharedFlow<SessionEvent>`. State is for "what is true now"; events are for
"this happened".

### `Transport`

The connection's TCP control channel. Owned by the session.

- A TCP reader thread does blocking reads, parses protobuf frames, and
  sends parsed messages into the session's inbound channel.
- A writer coroutine on the I/O dispatcher drains a bounded outbound
  channel. `send(message)` only enqueues; it never blocks the caller. A full
  queue means the connection is unhealthy and fails the session.
- Keepalive timers are coroutines in the session scope.
- Decides whether voice goes over UDP or is tunnelled over TCP, from the
  UDP ping results the `VoiceEngine` reports. In tunnel mode it carries
  voice packets between the server and the `VoiceEngine` (see below).
- Any fatal I/O error fails the session with a cause. The transport never
  reconnects on its own.

### `VoiceEngine` (native voice path)

The whole voice path, in C++, with a thin Kotlin facade. Application-scoped
(see [spikes](#spikes) for the alternative).

**Native side.**
- **Capture.** An AAudio input stream (voice-communication preset) feeds
  the existing DSP chain (RNNoise, filters, leveler), voice activity
  detection, the pre-speech buffer, and the Opus encoder.
- **Transmit.** One native component owns every piece of transmit state:
  the gate decision, voice activity, the pre-speech buffer, frame numbers,
  the end-of-transmission marker, and the whisper target. It frames packets
  (both the protobuf `MumbleUDP.Audio` format and the legacy format, as
  today's
  [`AudioHandler`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.kt)
  does) and encrypts them with the existing OCB2 code from
  [`crypto`](../../libraries/humla/src/main/jni/crypto/).
- **UDP.** Native code owns the UDP socket. One native thread receives and
  decrypts everything. Voice goes straight to the mixer; pings and other
  non-voice packets are reported to Kotlin (a few per second at most). Sent
  pings are requested by Kotlin.
- **Playback.** The existing jitter buffers, Opus decoders, and mixer
  ([`AudioOutputEngine`](../../libraries/humla/src/main/jni/audio_engine/AudioOutputEngine.cpp))
  feed an AAudio output stream's data callback directly.
- **Tunnel mode.** When UDP is unavailable, framed (unencrypted) packets go
  to Kotlin for the TCP tunnel, and tunnelled inbound packets come back the
  same way. This keeps one Kotlin crossing per frame, only in the fallback.

**Kotlin facade.** Non-blocking control only:
- `submit(VoiceCommand)` for start (socket address, crypt keys, codec
  settings), stop, and reconfigure. A Kotlin control thread performs these,
  because opening and closing audio streams can block.
- `setPolicy(VoicePolicy)`: see [transmit and mute](#transmit-and-mute).
- Outputs: `StateFlow<VoiceStatus>` (streams up, UDP reachable, crypt
  statistics), talk-activity edges as their own flow, and an event flow for
  pipeline errors and resync requests.

**Reused unchanged:** the DSP, VAD, pre-speech buffer, Opus wrappers,
jitter buffers, mixer, and OCB2 crypt, with their host tests.
**New native code:** AAudio stream handling (including reopening a stream
when the device disconnects it), packet framing and parsing for both
formats, and the UDP socket loop.

### Transmit and mute

Deciding whether to send has inputs that belong to the session (self mute,
server mute and suppress, PTT, whisper target) and mechanics that belong to
the voice path (voice activity, the pre-speech buffer, the
end-of-transmission marker). That split exists in any design. What this
design fixes is the other half of today's problem: the mechanics are
currently spread across Kotlin and C++, with three crossings per outgoing
frame (Kotlin capture → native encode → Kotlin framing → native encrypt →
Kotlin send). With the native path there is one owner of transmit state.

The session side is pushed, never pulled:

- `VoicePolicy` is a small value computed from `SessionState`: transmit
  allowed (self mute, server mute or suppress, deafen), PTT pressed,
  whisper target, and the per-user playback table (local mute, volume).
- One collector, on its own single-thread dispatcher (never the voice
  control thread, which can block on stream changes), maps each new
  `SessionState` to a `VoicePolicy`, skips unchanged values, and pushes it
  into native code, which swaps it in atomically.
- Native code applies the policy at the next frame boundary, so a mute
  takes effect within one collector dispatch plus at most one frame. On a
  transition mid-utterance it emits the end-of-transmission marker itself.

`VoicePolicy` is the only path from session state into the voice path, so
there is no second copy of mute state to keep in sync by hand.

**Before the native path lands** (migration step 4, below), the Kotlin
capture thread reads `SessionState` each frame and calls the native
engine's existing `setMuted` only when the answer changes. It is the same
thread that drives the engine, so there is no cross-thread contention, and
native code keeps owning the stateful mechanics.

### `RouteControl`

Audio routing: communication device, Bluetooth SCO, A2DP, and the settle
and debounce timing. Application-scoped.

- Runs on its own thread (a `HandlerThread`, so platform callbacks can be
  delivered there directly). Blocking platform calls such as
  `setCommunicationDevice` run there, not on main.
- Takes `submit(RouteCommand)`: session started, session ended, preference
  changed.
- Publishes `StateFlow<RouteState>` and events (SCO failed).
- Built from today's
  [`AudioRouteController`](../../libraries/humla/src/main/java/se/lublin/humla/audio/AudioRouteController.kt),
  [`BluetoothScoManager`](../../libraries/humla/src/main/java/se/lublin/humla/audio/BluetoothScoManager.kt),
  and `AudioPlatform`/`ScoLink`. Its decision logic and its tests (the
  settle budget, lagging-sink catch-up, debounce, mode claim and restore)
  carry over.

### Session start and audio routing

The hardest real-world part of the app, and the most churned:
`BluetoothScoManager` and `AudioRouteController` have 27 commits between
them since September, and slice 7 of the `HumlaService` decomposition
existed to fix the order of session start against the route funnel. The
design keeps the dependencies one-directional:

- **`HumlaClient` sequences lifetimes only.** On connect it sends
  `SessionStarted` to `RouteControl` and `start` to the `VoiceEngine`. On
  teardown it stops the `VoiceEngine` first, then sends `SessionEnded`, so
  nothing captures from a route that is being torn down.
- **`RouteControl` decides and publishes readiness.** `RouteState` is one
  of `Idle`, `Settling(target)`, `Ready(route)`, or
  `Fallback(route, reason)`. `Settling` lasts at most the existing settle
  budget; if SCO does not come up in time, or fails, the state becomes
  `Fallback` with the reason, and a `ScoFailed` event lets a feature show
  the message.
- **`VoiceEngine` follows readiness.** It opens its streams only on `Ready`
  or `Fallback`, never on an unsettled route. When the route changes during
  a session, it keeps the current streams until the new route is `Ready`,
  then reopens them. It also reopens when AAudio reports a disconnected
  stream.

Route never waits for voice and voice never calls into route, so the two
cannot deadlock or loop. The cost is a short silence at connect while SCO
settles, which matches the existing settle budget rather than adding to it.
This sequence needs on-device verification (see
[verification budget](#verification-budget)).

### App layer

- **`ConnectionService`** is a foreground `Service` of type `microphone`.
  Android 14 and later generally refuse to start a microphone foreground
  service while the app is in the background, so:
  - The UI path that calls `client.connect` starts `ConnectionService`
    first, while the app is in the foreground.
  - The service stays in the foreground for the client's whole active
    period, including every reconnect cycle, and stops only when the client
    returns to `Idle`.
  - Connects started from the background (the talk broadcast receiver,
    notification actions) are accepted only while the service is already
    in the foreground.

  The service holds the wake and Wi-Fi locks, nothing binds to it, and it
  contains no connection logic.
- **Features** are small classes, each collecting the state and events it
  cares about: connection notification, message notifications, TTS, chat
  log, PTT sounds, overlay, hot corner, proximity sensor, and the talk
  broadcast receiver. They replace the responsibilities currently piled into
  [`MumlaService`](../../app/src/main/java/se/lublin/mumla/service/MumlaService.kt)
  by inheritance from `HumlaService`.
- **Screens** keep their XML layouts. Each gets a ViewModel that turns
  client and session state into UI state, and calls client or session
  methods for user actions. Fragments and activities collect UI state with
  `repeatOnLifecycle`, so updates arrive on main and only while visible.
  [`MumlaActivity`](../../app/src/main/java/se/lublin/mumla/app/MumlaActivity.kt)'s
  connection, trust-prompt, and reconnect bookkeeping moves into its
  ViewModel.
- **Settings** are a `Flow<Settings>` from the existing DataStore
  repository. `HumlaClient`, `VoiceEngine`, and features collect the keys
  they use; storage APIs become `suspend`.

## Threads

| Thread | Runs | May block? |
|---|---|---|
| Main | Views, ViewModels, UI-touching features | Never |
| Default dispatcher | Session message handling, state updates, feature logic | No |
| Voice policy | Mapping `SessionState` to `VoicePolicy` and pushing it native | No |
| I/O dispatcher | TCP writer, database, DataStore | On I/O only |
| TCP reader (one per session) | Blocking socket reads and frame parsing | On the socket |
| Voice control | Opening, closing, reconfiguring audio streams | Yes, off main |
| Native capture and render callbacks | Realtime audio (AAudio) | Never |
| Native UDP receive | Receive, decrypt, route voice to the mixer | On the socket |
| Route | Platform audio-route calls and callbacks | Yes, off main |

Nothing on main ever waits for another thread. Threads communicate by
enqueuing commands or by publishing state that others read.

## Walkthroughs

### Connecting

1. The server list's ViewModel starts `ConnectionService` and calls
   `client.connect(server)`.
2. The client cancels any current session, creates a new `Session`, and
   publishes `Connecting`.
3. The session opens its `Transport`. The TLS handshake and authentication
   run on the I/O side.
4. Server messages flow into the session. It builds `ServerState`
   privately and publishes it on `ServerSync`. The client publishes
   `Connected(session)`.
5. The client sends `SessionStarted` to `RouteControl` and starts the
   `VoiceEngine` with the session's UDP address and crypt keys. Route
   settles; voice opens its streams on `Ready` or `Fallback`.
6. Screens and features, collecting state, show the channel tree.

### The user mutes

```mermaid
sequenceDiagram
    participant UI as Screen (main)
    participant S as Session
    participant SS as SessionState
    participant P as Voice policy
    participant VE as VoiceEngine (native)
    participant TR as Transport
    participant SRV as Server

    UI->>S: setSelfMute(true)
    S->>SS: update { self muted, pending }
    S->>TR: send(UserState self_mute)
    SS-->>P: new state
    P->>VE: setPolicy(transmit not allowed)
    Note over VE: next frame: stop sending,<br/>emit end-of-transmission marker
    SS-->>UI: new state, mute icon updates
    TR->>SRV: UserState
    SRV->>TR: UserState echo
    TR->>S: inbound message
    S->>SS: update { confirmed, pending cleared }
```

The mute is optimistic, with the server echo as confirmation, preserving
the desktop-parity rules recorded in
[optimistic-self-mute](../optimistic-self-mute/README.md) (deafen implies
mute; the server's version wins). The server also enforces it: murmur
drops voice from any user who is muted, suppressed, or self-muted, so the
client-side gate is about privacy (audio never leaves the device) rather
than about what other users hear.

### Someone else talks

The native UDP thread receives and decrypts the packet, checks the sender
against the per-user playback table in the current `VoicePolicy` (local
mute, volume), and queues it into that user's jitter buffer. The AAudio
render callback mixes it into the output. Talk-activity edges go to Kotlin
as their own flow, kept out of `SessionState`, which would otherwise change
many times per second.

### A Bluetooth headset connects

The platform callback arrives on the route thread. `RouteControl` moves to
`Settling`, then `Ready` with the headset. The `VoiceEngine`, following
`RouteState`, keeps its streams until `Ready`, then reopens them on the new
device. Screens showing the route update from the same state.

### The network drops

1. The TCP reader hits an I/O error and fails the session.
2. The client stops the `VoiceEngine` (closing its UDP socket and
   discarding the crypt keys), sends `SessionEnded` to `RouteControl`, and
   cancels the session's scope. The scope's completion handler closes the
   TCP socket.
3. The client publishes `Reconnecting(lastState)`, waits out the backoff,
   and creates a fresh `Session`. `ConnectionService` stays in the
   foreground throughout. The connecting walkthrough repeats from step 2.

### A screen closes or rotates

Its lifecycle stops collection. There is nothing to unregister, and no
update can reach a destroyed view.

## What the structure rules out

| Structure | Rules out |
|---|---|
| Immutable `SessionState`, atomic updates | Half-updated reads, locks on model data, observers holding live objects |
| One `Session` per connection | Late events reaching a newer connection; generation counters and stale-event checks |
| Native voice path with pushed `VoicePolicy` | Transmit state split across two languages; per-frame Kotlin work on the realtime path |
| Command-only voice and route control | Main waiting on audio or route work; lock-order deadlocks between main and audio |
| One-directional route → voice dependency | Route and voice waiting on each other |
| `repeatOnLifecycle` collection | Updates on the wrong thread or after a view is destroyed |
| Composition root, features as collectors | Inheritance coupling between app and library; the service-binding layer |

## Remaining conventions

Two rules remain, both narrow:

1. **No side effects inside `update {}`.** An atomic swap may run the
   lambda more than once.
2. **Decide inside `update {}`, not after reading.** Code that reads
   `state.value` and then acts on what it read (for example, "if muted,
   unmute") can act on a stale value. Decisions that depend on current
   state are made inside the `update` lambda, and the effect follows from
   its result. The same applies to holding a snapshot across time: keep IDs,
   not `User` or `Channel` values.

## Enforcement

A **tripwire** in [`check.sh`](../../scripts/check.sh) fails if
`@Volatile`, `@Synchronized`, `synchronized(`, `Thread(`, `Handler(`,
`runBlocking`, `ReentrantLock`, or `Executors.` appears in main sources
outside an allowlist file.

The allowlist starts as today's 31 files (about 330 sites) so the gate is
green on day one. Each migration step removes files, and the check fails if
a listed file no longer needs to be there, so the list can only shrink. The
expected end state is about six files at real boundaries: the native
facades (`VoiceEngine`, `CryptState`), `HumlaTCP` (the socket reader),
`HumlaSSLSocketFactory` (TLS callbacks on the socket thread), and
`CrashReporter` (the uncaught-exception handler). The Kotlin realtime
threads (`AudioInput`, `AudioOutput`) and `HumlaUDP` disappear with the
native path.

It is a tripwire, not a proof. Text search misses atomics and other
`java.util.concurrent` tools, and it cannot see mutability hidden inside
state. Two additions cover the most likely gaps:
- A unit test that walks the state classes (`SessionState` and everything
  reachable from it, `VoicePolicy`, `ClientState`) by reflection and fails
  on any `var` property or any field whose runtime type is a mutable
  collection.
- Review attention on any new `java.util.concurrent` import outside the
  allowlist.

## How the whales map

| Whale | Satisficing fix ([README](README.md)) | This architecture |
|---|---|---|
| W1 no session object | Per-connect `Session` owning a scope | `Session` under `HumlaClient`, reconnect owned by the client |
| W2 shared mutable model | Single writer publishing snapshots | Immutable `SessionState`; any writer, atomic updates |
| W3 audio control locks | Single audio-control thread | Native voice path with pushed policy; command-only control |
| W4 half-finished data/DI | Finish *or* revert | Revert: `AppContainer`, suspend storage APIs |
| W5 app god objects | Composition plus ViewModel | Features as collectors, ViewModels, no binding |
| W6 no harness | murmur loopback suite | Same, plus virtual-time tests and a larger native suite |

## Testing

- **Virtual-time tests (every `check.sh`).** `Session`, `HumlaClient`,
  voice control, the policy collector, and `RouteControl` are coroutines,
  so they run under `kotlinx-coroutines-test` against fakes, as
  [`AudioRouteControllerTest`](../../libraries/humla/src/test/java/se/lublin/humla/audio/AudioRouteControllerTest.kt)
  already does. Cover: disconnect at every step of the sync sequence;
  reconnect racing a user disconnect; mute, deafen, and PTT spam during
  connect and disconnect; route flaps mid-connect; SCO failure during
  `Settling`. Assert: no policy allows transmit while muted; at most one
  live session; voice stops before `SessionEnded`; every user in exactly
  one channel.
- **State-transition tests.** Message handling is (state, message) → new
  state, so each protocol handler is tested as a plain function.
- **Native host suite (every `check.sh`).** The native path moves framing
  and the UDP loop into code the existing host suite can reach. New tests:
  packet framing and parsing in both formats, checked byte-for-byte against
  the Java-generated protobuf classes; policy application at frame
  boundaries, including the end-of-transmission marker; a loopback test
  from capture input through encode, frame, encrypt, decrypt, decode, and
  mix, with no Android audio involved.
- **murmur interop (every `check.sh`).** The whales list's W6 suite: a
  throwaway `murmur` from the dev shell on loopback, with real TLS, UDP
  crypt, and connection code. Required, because fakes only test our idea of
  the server.
- **Trace replay (later).** Recorded server message streams replayed
  through the message handlers as regression fixtures.

## Verification budget

The real limit on pace is what cannot be automated: real audio devices,
Bluetooth, and Android's foreground-service rules. Each step lists its
on-device checks up front, drawn from the existing torture list (SCO flap,
A2DP stagger, overlay drag and rotate, notification paths) plus:

| Step | On-device checks |
|---|---|
| 2 Immutable state | Large server join; channel list stays live while users move |
| 3 Session and client | Network drop and reconnect in background; Wi-Fi to mobile handover |
| 4 Voice control and route | SCO connect, flap, and failure at connect; A2DP stagger; mute during SCO settle |
| 5a Native framing, crypt, UDP | Voice both directions on UDP and in tunnel mode; crypt resync after network change |
| 5b AAudio streams | Latency and glitch rate against the step 5a baseline; echo cancellation on speaker; SCO and wired headset routing on the oldest and newest supported Android versions |
| 7 App cut-over | Background connect rules on Android 14+; notification actions; talk broadcast |

### Spikes

Open design questions are answered by throwaway worktree spikes before the
step that depends on them, not by discussion:

- **Live handles for step 2:** does the compatibility approach below keep
  every screen updating?
- **AAudio or Oboe:** AAudio directly (minSdk 30 makes it always available)
  or the Oboe wrapper (handles stream reopening and device quirks, at the
  cost of another native dependency).
- **Echo cancellation with AAudio:** today `AudioInput` attaches the
  platform `AcousticEchoCanceler` to the `AudioRecord` session ID. AAudio
  can allocate a session ID for the same purpose; confirm the effect
  attaches and works on a voice route.
- **AAudio routing on Android 11–12:** whether an AAudio voice stream
  follows the legacy SCO path that the minSdk 30 floor reintroduced.
- **`VoiceEngine` scope:** application-scoped (survives reconnects) or per
  session (simpler teardown, rebuilds streams on every reconnect). Measure
  the rebuild cost on a device.
- **Framing codec:** a hand-written encoder and parser for the small
  `MumbleUDP.Audio` message, or a generated one. Either way it is checked
  byte-for-byte against the Java classes.

## Migration path

Each step lands as atomic commits in a worktree under the `check.sh` gate,
and each step's "done" includes shrinking the allowlist and passing its
on-device checks. Steps 2–5 are each worth landing on their own.

1. **Harness and gate.** murmur interop, virtual-time scaffolding, and the
   allowlist tripwire seeded with today's files. Done when a known past
   race (e.g. disconnect during sync) is reproduced red.
2. **Immutable state.** Introduce `SessionState` and new immutable types
   for users and channels. App code currently relies on `IUser` and
   `IChannel` being *live* (adapters keep references and expect them to
   change), so swapping snapshots in behind those interfaces would compile
   and silently freeze screens. Instead, `IUser` and `IChannel` become
   **live handles**: an ID plus the state flow, where every property read
   looks up the current value. Existing code keeps today's semantics; new
   code uses the snapshot types; the handles are deleted in step 8, and the
   compiler finds any remaining caller. Done when `Channel`, `User`, and
   `ModelHandler` leave the allowlist.
3. **`Session` and `HumlaClient`.** Per-connection fields move out of
   `HumlaService` into `Session`; reconnect moves to the client. Done when
   `connectionGeneration` and `shouldDropStaleEvent` are deleted and
   `HumlaConnection` leaves the allowlist. Likely split further: the
   connection code spans about 2,200 lines across `HumlaConnection`,
   `HumlaTCP`, and `HumlaUDP`.
4. **Voice control and route.** Audio start, stop, and reconfigure move to
   a control thread; the interim per-frame mute check replaces the mute
   locks; `RouteControl` moves to its own thread with the readiness states
   above. Done when `mMuteLock` is deleted, no `join` runs on main, and
   `AudioHandler`, `AudioRouteController`, and `BluetoothScoManager` leave
   the allowlist.
5. **Native voice path**, in two parts:
   - **5a. Framing, crypt, and UDP in native code**, still fed by the
     Kotlin `AudioRecord` and `AudioTrack` threads. This alone gives one
     owner of transmit state and replaces the interim mute check with
     `VoicePolicy`. Done when `HumlaUDP` and the Kotlin framing in
     `AudioHandler` are deleted.
   - **5b. AAudio capture and playback.** Done when `AudioInput` and
     `AudioOutput` are deleted and the step 5b on-device checks match or
     beat the 5a baseline.
6. **Screens on ViewModels.** Replace `IHumlaObserver` in fragments, the
   activity, and the overlay with collected UI state. Done when no screen
   implements an observer.
7. **App cut-over.** `AppContainer`, features as collectors,
   `ConnectionService` with the foreground rules above, binding layer
   removed, Hilt and kapt removed, storage APIs `suspend`. Done when nothing
   binds a service and `HumlaService`, `MumlaService`, `MumlaActivity`, and
   the storage files leave the allowlist.
8. **Clean up.** Delete `IHumlaObserver`, `HumlaCallbacks`, the live
   handles, and any other compatibility shims. Done when the allowlist is
   down to the boundary files.

## Kill criteria

Each criterion is about verification, not effort:

- **Step 1** cannot reproduce at least one known past race. Without that,
  the harness cannot vouch for later steps; fall back to the satisficing
  whales list.
- **Step 2's live-handle spike** shows screen behavior changes that the
  tests did not catch. Fix the harness before continuing.
- **Step 4's route sequence** fails the on-device SCO checks and cannot be
  fixed within the readiness model. Re-think the sequence before step 5.
- **Step 5b** regresses latency, glitch rate, echo cancellation, or routing
  on device and cannot be fixed. Stop at 5a: keep `AudioRecord` and
  `AudioTrack`, which still leaves one owner of transmit state.

Treat it as a design problem, not an implementation one, if a file cannot
leave the allowlist without new coordination machinery.

## Decisions this reverses

When the relevant steps land, these records must be updated in the same
branch:

- The native **fence** in the [migration](../migration/README.md)
  decisions ("C++ frozen, no behavior touch"). Step 5 extends the native
  code deliberately; the existing DSP, codec, and crypt code is reused, not
  rewritten, and the native suite remains the gate.
- The "no Oboe/AAudio" description of the audio pipeline in
  [`AGENTS.md`](../../AGENTS.md) (step 5b).
- The frozen binder surface (`IMumlaService`/`IHumlaService`/
  `IHumlaSession`), which was frozen only for the migration and is removed
  in step 7.

## Rejected alternatives

| Alternative | Why rejected |
|---|---|
| **One control loop** owning all non-realtime state | Forces one order on things that have none; a large join sync would queue ahead of mute taps and route events. |
| **Authorities**: state split by who decides it, with joined views for readers | Split `User` by writer even though every reader needs the joined value, then rebuilt the join with extra machinery. |
| **Rules only**: concurrency rules applied across the code | Discipline written down; does not make the wrong path harder. |
| **Per-frame Kotlin transmit decision** (`canTransmit(state)` on the capture thread, as the final design) | Duplicates or fights the stateful native mechanics (voice activity, pre-speech buffer, end-of-transmission marker). Kept only as the interim before step 5a. |

## Non-goals

- Compose or any UI toolkit change. The views and their pixels stay; the
  [killed Compose rewrite](../migration/README.md) stays killed.
- Rewriting the existing DSP, codec, or crypt C++, or moving any of it to
  Rust. Step 5 builds around that code.
- Kotlin Multiplatform or an iOS port.
- New features (see the
  [upstream feature gap audit](../upstream-feature-gap-audit/README.md)).

## Open questions

The design questions are listed under [spikes](#spikes). Remaining
non-design questions:

- **Socket reader:** whether the `HumlaTCP` read loop can move to a
  coroutine on the I/O dispatcher and leave the allowlist.
- **Observer compatibility:** whether anything outside the app still needs
  `IHumlaObserver` after step 8.
- **`check.sh` budget:** how much murmur time the gate can absorb (see
  [check-sh-speed](../check-sh-speed/README.md)).
