# Technical Debt Whales — Mumla OLED

Snapshot: `master` at `f7f32c9c`, 2026-11-10. Citations name files and
symbols, not line numbers; re-search the symbol at `HEAD`.

This is a short list of **structural** debt in Mumla OLED. It deliberately
replaces the deleted `docs/tech-debt-audit/` grab bag, which was a long list
of low-severity items whose individual fixes caused more regressions than
they removed.

## Admission bar (read before adding anything)

An item belongs here only if **all** of these hold:

1. **Root cause, not symptom.** It explains a *family* of past bugs or
   commits, not one defect. Point to the churn it causes.
2. **Structural fix.** Paying it down changes ownership, lifetime, or
   threading boundaries — not a guard, null check, or extra lock.
3. **Deletes code on landing.** The finished fix removes more defensive
   machinery (locks, volatiles, generation counters, stale checks) than it
   adds.
4. **Has a done-when oracle** that a reviewer can check mechanically.

Things that fail the bar — individual null checks, single missing logs,
naming, doc rot, isolated dead code — do **not** go here, and are **not**
fixed as standalone sweeps. Fix them only when a whale (or a feature) is
already rewriting that code. Standalone krill sweeps are how the old audit
caused regressions.

## Recommended order

```
W6 (harness + CI) ──► W1 (session) ──► W2 (model snapshots) ──► W5 (app layer)
                           │
                           └──► W3 (audio control actor)
W4 (finish or revert the data/DI layer) — independent decision, any time
```

W6 comes first because W1–W3 rework concurrency. Without an end-to-end
harness, those rewrites would be checked the same way the current race
fixes are: unit fakes plus manual device runs.

---

## W1 — No session object: `HumlaService` is the session

**Evidence**

- [`HumlaService`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.kt)
  implements `IHumlaService` *and* `IHumlaSession`. Per-connection state
  (`mConnection`, `mModelHandler`, `mAudioHandler`, `mConnectionState`,
  `mReconnecting`, `reconnectJob`, `mVoiceTargetId`, `mWhisperTargetList`)
  lives as nullable or `lateinit` fields on the long-lived service.
- That state is guarded by `reconnectLock`, `connectLock`, `applyLock`,
  `whisperLock`, the `ModelHandler` monitor, plus `connectionGeneration` and
  the session-token re-check in `AudioRouteController.shouldDropStaleEvent`.
- Churn: `HumlaService.kt` is the most-modified source file since
  2026-09-01 (30 commits). In the same window, 31 code commits (`humla:`,
  `audio:`, `app:`, `net:`) have subjects naming a race, teardown, deadlock,
  or stale event.

**Why it is a whale**

When a connection's objects outlive the connection, every callback has to
ask "is this still my session?" The generation counter, stale-event drop,
and the locks are all answers to that question. Each new race fix adds
another check instead of removing the cause.

**Shape of the fix**

- A per-connect `Session` (name open) is constructed by `connect()` and
  owns `HumlaConnection`, `ModelHandler`, `AudioHandler`, whisper targets,
  and a child `CoroutineScope`.
- Teardown is `session.close()`: cancel the scope, then close the owned
  objects in a fixed order. Callbacks capture their `Session` and can only
  touch its state; a closed session ignores late events by construction.
- `HumlaService` keeps process-lifetime concerns only: wake/wifi locks,
  network callback, foreground notification, the route controller, the
  observer fan-out, and "current session" as one reference.
- `IHumlaSession` is implemented by the session (or a thin forwarder), not
  by the service. The binder surface may need a compatibility forwarder
  during the transition.

**Done when**

- `connectionGeneration` and `shouldDropStaleEvent` are deleted.
- `HumlaService` has no nullable per-connection fields.
- At most one lock remains in `HumlaService` for session handoff (ideally
  zero, with handoff confined to main).
- W6 harness covers: connect → disconnect → connect, reconnect during
  sync, and disconnect during audio start.

**Regression guard:** land behind the W6 harness; move state in slices,
one field group per commit; the binder surface stays source-compatible.

---

## W2 — Shared mutable model graph handed out live

**Evidence**

- [`Channel`](../../libraries/humla/src/main/java/se/lublin/humla/model/Channel.kt)
  and [`User`](../../libraries/humla/src/main/java/se/lublin/humla/model/User.kt)
  are mutable objects. Every scalar is `@Volatile` (23 in `model/`), and
  child lists are copied under `synchronized` on each read.
- [`ModelHandler`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/ModelHandler.kt)
  documents main-thread confinement for writers, yet keeps `@Synchronized`
  on writers "as defense-in-depth" and `ConcurrentHashMap`s because the UDP
  thread calls `getUser` lock-free.
- [`IHumlaObserver`](../../libraries/humla/src/main/java/se/lublin/humla/util/IHumlaObserver.kt)
  delivers live `IChannel`/`IUser` references; adapters such as
  `ChannelListAdapter` re-walk the live tree on every callback.
- Multi-field changes are not atomic to readers: a user moving channels is
  `removeUser` on one channel and `addUser` on another, and a channel rename
  can be read alongside its old position.

**Why it is a whale**

There is no single threading rule for the model. Confinement, volatiles,
and monitors are all applied at once. No reader can get a consistent view,
and nobody can tell which guard actually carries the correctness load. So
nobody dares to remove any of them.

**Shape of the fix**

- `ModelHandler` stays the single writer on one thread, and after each
  applied message batch it publishes an immutable snapshot
  (`ServerState`: channels, users, links, permissions) via a `StateFlow`.
- The UDP and audio paths get their own narrow lookup (session → user
  audio state) owned by the audio side, not the UI model.
- Observer callbacks are kept for compatibility but carry snapshot values;
  new UI code collects the flow.

**Done when**

- `@Volatile` and `synchronized` are gone from `model/`.
- `ModelHandler` writers have no `@Synchronized`.
- No `IHumlaObserver` method passes a mutable model object.
- W6 harness asserts snapshot consistency across user moves and channel
  re-sorts.

**Depends on:** W1 (the snapshot flow is owned by the session).

---

## W3 — Audio control plane shares locks with the capture thread

**Evidence**

- [`AudioHandler`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.kt)
  has 37 `@Volatile`/`synchronized` sites and four monitors with a
  documented order: `mMuteLock` → `this` → `mInput`/`mOutput`, plus
  `mBandwidthLock` and `mPacketBufferLock`.
- The mute path deliberately holds `mMuteLock` across `startRecording`'s
  bounded stale join (up to 200 ms), and it can run on main.
- This already shipped one ABBA deadlock as an input-dispatch ANR in
  v0.26.1, followed by three rounds of review fixes; see
  [mute-tap-deadlock](../mute-tap-deadlock/README.md).

**Why it is a whale**

As long as the UI thread takes locks that the real-time capture/render
threads also take, every new control (mute, deafen, route change, bitrate
degradation) is a fresh chance to get the lock order wrong. The ordering
lives only in comments.

**Shape of the fix**

- One audio-control thread (a single-threaded dispatcher or command queue)
  owns start, stop, mute, reconfigure, and bandwidth changes.
- Main and binder callers *post* commands and never block on audio locks.
- Capture and render threads talk to control only via the native engine's
  existing thread-safe setters and atomics.
- The native C++ engines and their JNI boundary stay frozen (same fence as
  the [migration](../migration/README.md)).

**Done when**

- No `synchronized` block in `AudioHandler` is reachable from main.
- `mMuteLock` and the documented multi-monitor order are deleted.
- No `Thread.join` happens on main.
- Native suite stays green; W6 harness covers mute and deafen spam during
  connect and disconnect.

**Depends on:** W6. Easier after W1 (the session owns the control thread).

---

## W4 — Half-finished modernization: async stack behind sync facades

**Evidence**

- [`MumlaRoomDatabase`](../../app/src/main/java/se/lublin/mumla/db/MumlaRoomDatabase.kt)
  wraps every method in `runBlocking(Dispatchers.IO)` because "callers
  include the main thread". The KDoc calls this interim until "Slice 4
  suspend-ifies the service core". Slice 4 landed and the bridge remains.
- [`SettingsRepository`](../../app/src/main/java/se/lublin/mumla/SettingsRepository.kt)
  and [`DataStorePreferenceDataStore`](../../app/src/main/java/se/lublin/mumla/preference/DataStorePreferenceDataStore.kt)
  use the same `runBlocking` bridge over DataStore.
- Hilt: one `@Inject` (`SettingsRepository`) and one `@HiltAndroidApp`,
  against 57 `getInstance(` call sites. No `@AndroidEntryPoint`, no
  ViewModels.
- `:libraries:humla` applies the Hilt plugin, `legacy-kapt`, and
  `hilt-compiler` with **zero** Hilt/Dagger usage in its sources.

**Why it is a whale**

The project pays for the modern stack (kapt build time, Hilt plugin
coupling to AGP versions, DataStore and Room threading rules) without
getting its benefits. Every caller still assumes synchronous storage.

**Shape of the fix — a decision, not an investigation**

- **Finish:** suspend/`Flow` DAO and settings APIs, callers move to
  coroutines, and singletons become injected bindings.
- **Revert DI:** drop Hilt and kapt, keep Room and DataStore behind explicit
  singletons, and accept the sync bridge where the call sites are truly
  off-main.
- Either way, removing Hilt and kapt from `:libraries:humla` is free and
  should happen first.

**Done when:** either no `runBlocking` remains on a main-reachable path and
`getInstance(` is confined to the composition root, **or** Hilt and kapt are
gone from both modules.

---

## W5 — App layer: god objects via inheritance, no UI state layer

**Evidence**

- [`MumlaService`](../../app/src/main/java/se/lublin/mumla/service/MumlaService.kt)
  extends the `open` `HumlaService` and overrides 48 members. On top of the
  protocol service it carries TTS, `SoundPool` PTT sounds, the overlay, the
  hot corner, the proximity sensor, notifications, the chat log, and the
  talk broadcast receiver.
- [`MumlaActivity`](../../app/src/main/java/se/lublin/mumla/app/MumlaActivity.kt)
  (about 1,700 lines) owns TLS trust dialogs, reconnect observers, crash
  report snackbars, the drawer, runtime permissions, key events, and
  first-run flow.
- Fragments bind the service through `HumlaServiceFragment` and read the
  live model directly; there is no ViewModel anywhere.

**Why it is a whale**

Inheritance welds the app to the library's lifecycle internals. Every
`HumlaService` change risks an override in `MumlaService`, and every
feature added to the activity grows the same file.

**Shape of the fix**

- `HumlaService` becomes `final`. App features (TTS, sounds, overlay, hot
  corner, notifications, chat log) become components that observe the
  session and service.
- `MumlaActivity` connection, trust, and reconnect state moves into a
  ViewModel exposing UI state; dialogs render from that state.
- The XML UI stays. The killed Compose rewrite does not block this.

**Done when:** `HumlaService` is not `open`; `MumlaService` (or its
replacement) overrides lifecycle methods only; `MumlaActivity` holds no
connection or reconnect bookkeeping.

**Depends on:** W1 and W2 (components need a session and snapshots to
observe).

---

## W6 — No end-to-end harness, no CI

**Evidence**

- `HumlaConnection`, `HumlaTCP`, and `AudioHandler` have no unit tests.
  `HumlaService` has only `HumlaServiceVoiceTargetTest`.
- No test drives a real or fake server through
  connect → sync → voice → reconnect → disconnect.
- No CI workflow exists; `./scripts/check.sh` runs only on developer
  machines.
- Every race fix in W1's churn was verified by unit fakes plus manual
  device runs.

**Why it is a whale**

W1–W3 rewrite concurrency. Without a harness that can reproduce session
races deterministically, those rewrites would carry the same regression
risk that sank the old grab bag.

**Shape of the fix**

- Add `murmur` (present in the flake's `nixos-unstable` nixpkgs) to the
  dev shell, plus a host-JVM integration suite that starts a throwaway
  server on loopback and drives `HumlaConnection` and the session end to
  end (TLS, sync, UDP crypt, reconnect, disconnect races).
- Add a CI workflow running `./scripts/check.sh` plus the integration
  suite.

**Done when:** a scripted connect/disconnect/reconnect torture run passes
in CI. A known past race (e.g. disconnect during sync) is reproduced red,
then green.

---

## Pattern (not a whale): literal Java-to-Kotlin port

About 183 comments across 42 files mark behavior as preserved "literally"
or "NPE-exact" from the Java era. For example, `ChannelListFragment`
observers explicitly throw `NullPointerException` when a callback arrives
after the view is gone. Nullability was never modeled.

Per the admission bar, this is **not** swept on its own. Retire the
markers only in code a whale or feature is already rewriting. For example,
W2 and W5 naturally delete the observer NPE throws.

## Considered and rejected

- **Checked-in generated protobuf Java.** The vendored `.proto` files
  match upstream apart from Java and lite-runtime options. Unscripted
  regeneration is minor.
- **Native audio and crypto.** Fenced, recent, and covered by the native
  suite.
- **XML UI.** The Compose rewrite was killed deliberately; see
  [migration](../migration/README.md).
- **Custom crash reporter** (about 2,100 lines across `CrashReporter`,
  `CrashReportStore`, and `CrashReportsFragment`). Large, but working and
  tested; replacing it removes no root cause.
