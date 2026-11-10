# Target Architecture — Make the Safe Path the Default

Snapshot: `master` at `e3c43f9f`, 2026-11-10. Citations name files and
symbols, not line numbers.

Status: proposal. No code exists for any of this. Nothing here authorizes
work; each step below starts only on explicit request.

## Relationship to the whales list

The [whales list](README.md) is deliberately *satisficing*: for each family
of symptoms it gives the smallest structural fix that removes the family.
This document is the *optimizing* counterpart: an end state in which W1,
W2, and W3 (and most of W4 and W5) cannot come back, not just a state in
which they are fixed. Both documents share the whales list's admission bar
and its refusal to sweep krill.

## Thesis

Architecture should reduce how much discipline correct code requires. In
Mumla OLED today, almost every part of the app can be touched by several
threads at once, and staying correct means remembering rules: take this
lock before that one, never call this from main, check the session is still
current, check the view still exists. Forgetting one is how the shipped
bugs happened, including the mute-tap ANR
([mute-tap-deadlock](../mute-tap-deadlock/README.md)).

The spread is measurable. 31 Kotlin files under `app/src/main` and
`libraries/humla/src/main` use raw concurrency tools (`@Volatile`,
`@Synchronized`, `synchronized(`, `Thread(`, `Handler(`, `runBlocking`), at
about 330 sites. Each one is a place where a reviewer has to get it right.

The goal is not zero care. Some code really does need careful thread
handling. The goal is to **move the care from everywhere into a few clearly
marked files, make the safe way the normal way everywhere else, and have the
gate keep it that way.** Every mechanism below is described by what it
makes *impossible*, not by what it asks people to remember.

All of it uses primitives the project already depends on (coroutines,
`StateFlow`, lifecycle). Nothing here is exotic.

## Five mechanisms

### 1. Shared data is never edited in place

**Today.** [`Channel`](../../libraries/humla/src/main/java/se/lublin/humla/model/Channel.kt)
and [`User`](../../libraries/humla/src/main/java/se/lublin/humla/model/User.kt)
are mutable objects changed field by field on one thread and read on
others. A reader can catch an object halfway through an update, so every
field is `@Volatile`, child lists are copied under `synchronized`, and
[`ModelHandler`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.kt)
writers keep `@Synchronized` "as defense-in-depth".

**Instead.** Server state is one immutable value (`ServerState`: channels,
users, permissions, server settings) held in a `MutableStateFlow`. A change
makes a new copy and swaps it in atomically:

```kotlin
serverState.update { s -> s.withUser(session) { it.copy(isMuted = true) } }
```

Readers always see the old value or the new one, never a mix.

**Becomes impossible.**
- Half-updated reads: values are immutable.
- Deadlocks on model data: there is no lock (`update` is a compare-and-swap
  retry loop).
- Observers holding live objects that change under them: there is nothing
  mutable to hand out.

**It settles the "who writes `User`" question.** Server sync, self-mute,
and local mute can all update the same immutable `User`. Each write is an
atomic transform, so several writers need no coordination and no split of
`User` into per-writer pieces.

**Cost.** Copying on every change. After initial sync, updates are rare and
the copy is small. During the initial sync burst, `ModelHandler` builds
state privately and publishes once on `ServerSync`, the protocol's own
end-of-initial-state marker, so the burst is not a copy per message.

**Remaining convention.** The lambda passed to `update {}` must not have
side effects, because compare-and-swap may run it more than once. That is
one narrow, well-known rule, replacing a lock order documented in comments.

### 2. Each connection is its own self-contained object

**Today.** Per-connection state (`mConnection`, `mModelHandler`,
`mAudioHandler`, `mConnectionState`, `mReconnecting`, whisper targets)
lives in nullable fields on the long-lived
[`HumlaService`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.kt),
cleared and reused across connections. A late callback from an old
connection can reach the new one, so the code keeps `connectionGeneration`
and the session-token check in `AudioRouteController.shouldDropStaleEvent`,
plus four service locks.

**Instead.** `connect()` builds a `Session` holding everything for that
connection as non-null `val`s, plus its own `CoroutineScope`. Every socket,
audio, and timer callback is created by, and captures, the `Session` it
belongs to. Ending the connection cancels the session's scope; the service
holds a single reference to the current session.

**Becomes impossible.**
- A late callback affecting a newer connection: it holds the old,
  cancelled `Session`, and work launched on a cancelled scope never runs.
- Forgetting to reset a field on reconnect: there are no fields to reset.

`connectionGeneration`, `shouldDropStaleEvent`, and the per-connection
locks have nothing left to do and are deleted.

### 3. Slow parts take requests; nobody waits on them

**Today.** The main thread can wait on slow things. The mute path holds
`mMuteLock` across `startRecording`'s bounded join of the old capture
thread (up to 200 ms), and Bluetooth/route calls such as
`setCommunicationDevice` can run on main, because route control is
main-confined and SCO bring-up runs on its caller's thread. When two such waits depend on each
other, the app freezes.

**Instead.** Audio control (start, stop, mute, reconfigure) and route
control each run in their own coroutine on their own thread, and expose
only `submit(command)`: a non-blocking send into a queue they drain.
Joining threads and slow platform calls happen inside that coroutine.
Results come back as state (mechanism 1) or events.

**Becomes impossible.**
- Main blocking on audio or route work: main has no `join` or lock it
  could take.
- The mute-tap class of deadlock: there is no lock shared between main and
  the audio threads to take in the wrong order.

The mic gate stays immediate: muting updates state that the capture thread
reads on every frame (one atomic read), so muting never waits for the
command queue to drain.

### 4. Screens pull updates instead of being called

**Today.** [`IHumlaObserver`](../../libraries/humla/src/main/java/se/lublin/humla/util/IHumlaObserver.kt)
calls into screens from whatever thread the change happened on, including
after a screen has closed. Every observer has to hop threads and check that
its view still exists. Literal-port "NPE-exact" guards for that situation
appear in 42 files.

**Instead.** Screens collect state flows (and event flows, below) with
`repeatOnLifecycle`. Collection runs on main while the screen is visible
and stops automatically when it is not.

**Becomes impossible.**
- Updates arriving on the wrong thread: the receiver picks the thread, not
  the sender.
- Updates arriving after the screen is gone: collection has already
  stopped. The observer NPE guards disappear because the case they guard
  can no longer occur.

**State vs events.** `StateFlow` keeps only the latest value, which is
right for anything a screen displays. Things that must never be dropped
(chat messages, SCO failure, permission denied) use a `SharedFlow` or
`Channel`. That is one distinction to learn, not a convention at every
call site.

### 5. The gate enforces it

A small check in [`check.sh`](../../scripts/check.sh) fails if any of
`@Volatile`, `@Synchronized`, `synchronized(`, `Thread(`, `Handler(`, or
`runBlocking` appears in main sources outside an allowlist file.

**The allowlist is a ratchet.** It starts as today's 31 files, so the gate
is green on day one. Each migration step removes files from it, and the
check also fails if the list contains a file that no longer needs to be on
it. It can only shrink, so progress is mechanical and old habits cannot
creep back in.

**Becomes impossible.** Adding a lock, raw thread, or blocking bridge to
ordinary code without the change visibly touching the allowlist in review.

## What stays careful, on purpose

Some code genuinely needs hand-written thread handling. The estimated
final allowlist is about nine files:

- [`CryptState`](../../libraries/humla/src/main/java/se/lublin/humla/net/CryptState.kt)
  and the two native engine facades (`NativeAudioInputEngine`,
  `NativeAudioOutputEngine`): JNI over thread-safe native code, already
  covered by the native suite.
- `AudioInput` and `AudioOutput`: the realtime capture and render threads.
- `HumlaTCP` and `HumlaUDP`: blocking socket readers.
- `HumlaSSLSocketFactory`: TLS trust callbacks on the socket thread.
- `CrashReporter`: the uncaught-exception handler, which must work without
  coroutines.

The number is an estimate, and some may leave (the socket readers could
move to coroutines on an I/O dispatcher). The point is the shape: **from
31 files where care is needed to about nine, all at real boundaries, with
the gate keeping it there.**

## Beyond concurrency

These parts are worth doing for their own sake, and they fit together with
the mechanisms above.

### Remove the service-binding layer

`IHumlaService`/`IHumlaSession`, service binding, and
[`HumlaServiceFragment`](../../app/src/main/java/se/lublin/mumla/util/HumlaServiceFragment.kt)
are Jumble-era library heritage: they let third-party apps embed the
protocol service. Mumla OLED is one app in one process, and the app
references `IHumlaService` at 44 sites through a surface that was frozen
only for the duration of the migration.

Once screens collect flows (mechanism 4), nothing needs to bind the
service. The session and its state live at application scope; the
`Service` holds only the foreground lifetime. The XML views and their
pixels do not change, and the [killed Compose rewrite](../migration/README.md)
stays killed.

### Replace inheritance with composition

[`MumlaService`](../../app/src/main/java/se/lublin/mumla/service/MumlaService.kt)
extends the `open` `HumlaService` and overrides 48 members. Its app
features (TTS, PTT sounds, overlay, hot corner, proximity sensor,
notifications, chat log) become independent collectors of the session's
state and event flows. `HumlaService` becomes `final`.

### Composition root, no DI framework

Drop Hilt and kapt from both modules (one `@Inject` exists today, and
`:libraries:humla` applies both with zero use) and use one hand-written
composition root in `MumlaApplication`. Room and DataStore stay, behind
suspend/`Flow` APIs; the `runBlocking` bridges leave the allowlist with
this step. This resolves whales W4 in the "revert" direction.

## How the whales map

| Whale | Satisficing fix ([README](README.md)) | This document |
|---|---|---|
| W1 no session object | Per-connect `Session` object owning a scope | Same, plus callbacks capture their session (mechanism 2) |
| W2 shared mutable model | Single writer publishing snapshots | Immutable `ServerState` with atomic updates; any number of writers (mechanism 1) |
| W3 audio control locks | Single audio-control thread | Command-only audio and route control; main cannot wait (mechanism 3) |
| W4 half-finished data/DI | Finish *or* revert | Revert: composition root, suspend storage APIs |
| W5 app god objects | Composition plus ViewModel | Binding layer removed; features collect flows (mechanism 4) |
| W6 no harness | murmur loopback suite in `check.sh` | Same, plus deterministic virtual-time tests (below) |

## Testing

**Deterministic tests (every `check.sh`).** Session lifetime, audio
control, and route control are coroutines, so they run under
`kotlinx-coroutines-test` virtual time against a fake transport, with no
real threads or sleeps. [`AudioRouteControllerTest`](../../libraries/humla/src/test/java/se/lublin/humla/audio/AudioRouteControllerTest.kt)
already works this way. Scenarios to cover:
- disconnect at every message boundary of the sync sequence;
- reconnect racing a user-initiated disconnect;
- mute, deafen, and PTT spam during connect and disconnect;
- route flaps mid-connect.

Assertions to check throughout:
- no voice frame is sent while muted;
- at most one live session; no work runs for a cancelled one;
- every user is in exactly one channel, and every channel's parent exists.

**murmur interop (every `check.sh`).** The whales list's W6 suite: a
throwaway `murmur` from the dev shell on loopback, exercising real TLS,
real UDP crypt, and the real connection code. This is required, not
optional: the fake transport only tests our idea of the server.

**Trace replay (later).** Because `ModelHandler` becomes "message in, new
`ServerState` out", recorded control streams from real servers can be
replayed as regression fixtures.

Native audio and crypto keep their existing host suite unchanged.

## Migration path

Each step lands as atomic commits in a worktree under the usual
`check.sh` gate. Each step's "done" includes removing files from the
allowlist, so progress is visible in one place.

1. **Harness and gate.** murmur interop, the virtual-time scaffolding, and
   the allowlist check seeded with today's 31 files. Done when a known past
   race (e.g. disconnect during sync) is reproduced red.
2. **Immutable server state.** `ServerState` in a `StateFlow`; `Channel`
   and `User` become immutable; `ModelHandler` publishes on `ServerSync`
   and per change afterwards. `IHumlaObserver` keeps working, delivering
   snapshots, until step 5. Done when `Channel`, `User`, and
   `ModelHandler` leave the allowlist.
3. **Session object.** Per-connection state moves into `Session`; callbacks
   capture it. Done when `connectionGeneration` and `shouldDropStaleEvent`
   are deleted and `HumlaConnection` leaves the allowlist. Likely needs
   splitting (connection code spans about 2,200 lines across
   `HumlaConnection`, `HumlaTCP`, and `HumlaUDP`).
4. **Command-only audio and route control.** Done when `mMuteLock` is
   deleted, no `join` runs on main, and `AudioHandler`,
   `AudioRouteController`, and `BluetoothScoManager` leave the allowlist.
5. **Screens collect flows.** Replace `IHumlaObserver` use in fragments,
   the activity, and the overlay. Done when no screen implements an
   observer and the observer NPE guards are gone.
6. **App cut-over.** Binding layer removed, `MumlaService` features as
   collectors, `HumlaService` `final`, composition root, Hilt and kapt
   removed, storage APIs suspend. Done when no app code binds the service
   and `HumlaService`, `MumlaService`, `MumlaActivity`, and the storage
   files leave the allowlist.
7. **Clean up.** Delete `IHumlaObserver` and `HumlaCallbacks` if nothing
   still needs them. Done when the allowlist is down to the boundary files.

Steps 2–4 are each worth landing on their own. Stopping after any of them
leaves the codebase strictly better.

## Kill criteria

Stop and fall back to the satisficing whales list if:

- **Step 1** cannot reproduce at least one known past race: the harness
  would not buy the safety the later steps depend on.
- **Step 2** causes measurable lag on a large server that publishing on
  `ServerSync` does not fix. (A persistent-map structure is the fallback
  before giving up.)
- **Voice regresses** in glitch rate or mouth-to-ear latency after step 4
  and cannot be fixed within this design.

And treat this as a sign the design is wrong, not just the code:

- **A file cannot leave the allowlist without a new special mechanism.**
  If removing the locks from some code keeps requiring new coordination
  machinery, the problem has been modeled wrong. Rethink it; do not add
  the machinery.

## Rejected alternatives

Earlier drafts of this document proposed heavier designs. They are
recorded so nobody rebuilds them.

| Alternative | Why rejected |
|---|---|
| **One control loop** owning all non-realtime state, with a pure `step(state, event)` core | Forces one order on things that have none. A large join sync would queue ahead of mute taps and route events, and every fix for that was a piece of the system trying to get back out of the loop. |
| **Authorities**: state split by who decides it (server, local user, platform route, transport, voice), with joined "views" for readers | Over-engineered for the size of the problem. It split `User` by writer even though every reader needs the joined value, then rebuilt the join with views and consistency classes. Immutable values with atomic updates (mechanism 1) let several writers share one `User` without any of that. |
| **Rules only**: a short list of concurrency rules applied across the code | Just discipline written down. It changes nothing about how easy the wrong path is. The mechanisms above turn each rule into something the code structure or the gate enforces. |

## Parked: native voice hot path

The biggest optional move is putting the whole voice path in C++
(capture → DSP → Opus → OCB2 → `sendto`, and the reverse), with Kotlin only
publishing settings. That removes per-frame JNI crossings and garbage
collection from the realtime path. It is parked because it breaks two
deliberate decisions: the native [fence](../migration/README.md) and the
`AudioRecord`/`AudioTrack` (no Oboe/AAudio) stance.

**Revisit trigger:** measured glitches or jitter attributable to the
JNI/Kotlin hop after step 4.

## Non-goals

- **Compose, or any UI toolkit change.** The views and their pixels stay.
- **Rewriting the native audio and crypto code**, in Rust or otherwise.
  They stay fenced.
- **Kotlin Multiplatform or an iOS port.** There is no second consumer.
- **New features.** The [upstream feature gap audit](../upstream-feature-gap-audit/README.md)
  gets cheaper after this, but it is out of scope.

## Open questions

- **Final allowlist:** whether the socket readers and
  `HumlaSSLSocketFactory` can move to coroutines and leave it.
- **Where `ServerState` lives:** inside `Session` (dies with the
  connection; the UI sees an empty state between connections) or at
  application scope (the UI can keep showing the last state during a
  reconnect).
- **Observer compatibility:** whether `IHumlaObserver` survives step 7,
  given that every in-tree consumer can move to flows.
- **Route thread:** a dedicated looper for route control, or main with all
  blocking calls moved off it.
- **`check.sh` budget:** how much murmur time the gate can absorb (see
  [check-sh-speed](../check-sh-speed/README.md)).
