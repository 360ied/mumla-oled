# Mute-Tap ANR: `AudioHandler` ↔ `NativeAudioInputEngine` Deadlock

In October 2026, Mumla OLED hit an input-dispatch ANR
shortly after connecting: tapping the mute menu item deadlocked the main
thread against the audio capture thread. This document records the
incident evidence, the lock-order proof, every affected call path on
current master, and the fix shape — so a future reader can tell intention
from oversight when touching audio locking.

## 1. Incident record

Source: `adb shell dumpsys dropbox --print <timestamp> data_app_anr`
(the live logcat had already rotated past the window, and a live
grep for the package matched nothing).

- `Process: se.lublin.mumla.oled15`, version `0.26.1`, `Foreground: Yes`
- `Activity: se.lublin.mumla.oled15/se.lublin.mumla.app.MumlaActivity`
- `Process-Runtime: <redacted>` — connected shortly before, then tapped mute
- `Subject: Input dispatching timed out
  (Window:... se.lublin.mumla.oled15/se.lublin.mumla.app.MumlaActivity
  is not responding. Waited 5000ms for MotionEvent)`

All other dropbox entries around the window are unrelated third-party apps. This entry is the only Mumla OLED record.

## 2. The two threads

**main thread**, from a mute menu tap — waiting on the engine monitor
while holding the handler monitor:

```text
"main" prio=5 Blocked
  at se.lublin.humla.audio.NativeAudioInputEngine.setMuted(...)
  - waiting to lock <lock-E> (a ...NativeAudioInputEngine) held by the capture thread
  at e5.c.K(...)
  - locked <lock-A> (a e5.c)
  at se.lublin.humla.HumlaService.N(...)
  - locked <lock-A> (a e5.c)
  at ...onMenuItemSelected(...)
  ... ActionMenuItemView.onClick → View.performClick → Looper ...
```

**MumlaAudioInput (capture thread)**, the capture thread (~100 Hz) — waiting on
the handler monitor while holding the engine monitor:

```text
"MumlaAudioInput" prio=5 Blocked
  at e5.c.onTalkingStateChanged(...)
  - waiting to lock <lock-A> (a e5.c) held by the main thread
  at ...NativeAudioInputEngine.nativeProcessFrame(Native method)
  ...
  at ...NativeAudioInputEngine.processFrame(...)
  - locked <lock-E> (a ...NativeAudioInputEngine)
```

`e5.c` is the obfuscated
[`AudioHandler`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.kt)
(monitors line up: it owns the synchronized mute path in §5 and the
synchronized talking-state callback), and `HumlaService.N` is the mute
fan-out (`setSelfMuteDeafState` → optimistic gate push; see
[`HumlaService`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.kt)).

## 3. Lock-order proof

Let A = the `AudioHandler` monitor (`@Synchronized` methods) and
E = the `NativeAudioInputEngine` monitor (`@Synchronized` methods):

| Thread | Holds | Wants | Path |
|---|---|---|---|
| main (menu tap) | A | E | `setSelfMutedOptimistic` → `updateMuteState` → `setMuted` |
| capture (every 10 ms frame) | E | A | `processFrame` → `nativeProcessFrame` → JNI upcall → `onTalkingStateChanged` |

The cycle closes across the JNI boundary:
[`NativeAudioInputEngine.processFrame`](../../libraries/humla/src/main/java/se/lublin/humla/audio/NativeAudioInputEngine.kt)
is `@Synchronized`, so the capture thread holds E for the whole
`nativeProcessFrame` call, and the JNI talking-state callback
(`setTalkingCallback` lambda in
[`NativeAudioInputEngineJni.cpp`](../../libraries/humla/src/main/jni/audio_engine/NativeAudioInputEngineJni.cpp))
invokes `onTalkingStateChanged` **synchronously on the calling thread** —
which is `@Synchronized` on the handler
([`AudioHandler.kt`](../../libraries/humla/src/main/java/se/lublin/humla/protocol/AudioHandler.kt)).
Meanwhile the main thread holds A inside `updateMuteState` and blocks on
E inside `setMuted`. Neither side can proceed: textbook ABBA.

## 4. Why the native layer is innocent

The C++ engine already follows the copy-then-dispatch-outside-the-lock
discipline. `AudioInputEngine::processFrame` copies the callbacks and
queued packets under `m_callbackMutex` + `m_mutex`, releases both, and
only then fires the talking and packet callbacks
([`AudioInputEngine.cpp`](../../libraries/humla/src/main/jni/audio_engine/AudioInputEngine.cpp),
"Dispatch callbacks outside the lock to prevent deadlock"); `setMuted`
does the same for its mute-transition talking notification plus
terminator packet. The bug is purely the Java monitor layering one level
up: `@Synchronized` on `processFrame` spans the native call *and* the
synchronous Java upcall it performs.

Side note, not causal here: the JNI packet-callback lambda takes
`callbackMutex` around its `CallVoidMethod` while the talking-callback
lambda does not. That asymmetry is harmless today (the packet path takes
only `mPacketBufferLock`, never A), but any future handler-monitor use in
`onAudioPacketEncoded` would build the same trap — keep that path
A-free.

## 5. Blast radius on current master

Line numbers below predate the fix (`master@f9f079ae`); the fix moves
them — follow the symbol names, which are stable.

Every hold-A-then-take-E site shares the defect, not just the menu tap:

1. **Menu tap (this ANR).** `setSelfMutedOptimistic` → `updateMuteState`
   → `mNativeEngine.setMuted` (pre-fix `AudioHandler.kt:402→371→382`), reached
   from `HumlaService.applyOptimisticSelfMuteDeaf` (`HumlaService.kt`).
   Main thread → user-visible ANR after 5 s.
2. **Server echo.** `messageUserState` → `updateMuteState` → `setMuted`
   (`AudioHandler.kt`, pre-fix `:616→371`). Runs on the TCP reader thread: same
   deadlock, but it stalls the protocol silently instead of ANR-ing.
3. **Connect.** `initialize` → `updateMuteState` → `setMuted`
   (`AudioHandler.kt`, pre-fix `:325→371`). Narrower window (recording starts inside
   the same call), same shape via a racing echo. `initialize` holds a
   second edge of the same kind: it also calls `setMaxBandwidth` →
   `mNativeEngine.setBitrate/setFramesPerPacket` while still holding A,
   so the connect path can deadlock through the bandwidth setters too.
   (The mid-call `messageServerSync`/`messageServerConfig` →
   `setMaxBandwidth` paths are safe today — they never hold A.)
4. **Disconnect.** `shutdown` → `mNativeEngine.destroy`
   (`AudioHandler.kt`, pre-fix `:566→576`) **plus a join hazard**: `shutdown` holds A
   while `mInput.shutdown()` *joins* the capture thread — and the capture
   thread needs A for `onTalkingStateChanged`. The existing comments guard
   the leaf locks ("never hold `mInput`/`mOutput` across shutdown/join")
   but miss that `this` is also a lock the capture thread needs.

The in-tree precedent for the correct shape is `setMaxBandwidth`
(`AudioHandler.kt`, pre-fix `:467-499`): decide and publish under `mBandwidthLock`,
then fan out to `mNativeEngine.setBitrate/setFramesPerPacket` **after
releasing it**. The mute path never adopted that shape.

## 6. Trigger and frequency

The capture thread offers a collision every 10 ms, and the talking-state
upcall fires exactly on speech transitions — the trace shows a transition
in flight when the tap landed. Any mute/unmute tap during active speech
can collide: rare per tap, inevitable over time. The short process runtime
fits a fresh connect followed by one tap. The main thread's own
`setMuted` can also emit a synchronous talking callback from C++; that
reentry is safe (main already holds both monitors, `@Synchronized` is
reentrant) — the deadlock always needs the *other* thread's in-flight
frame, which is why it reproduces intermittently rather than every tap.

## 7. Fix shape (applied)

Applied by the mute-tap fix: steps 1–4 below, with two as-built
refinements. First, only `processFrame` drops the engine monitor —
`setMuted` and the other setters stay `@Synchronized` so teardown
exclusion with `destroy()` is preserved; that is safe because after step
2 no caller holds A when invoking them, leaving the remaining E→A edge
unopposed. `destroy()` is `internal` with a documented join-before-destroy
owner protocol (the never-effective `finalize` backstop is removed).
Second, the mute decision is additionally serialized on a new `mMuteLock`
leaf lock (`mMuteLock` → `this` → `mInput`/`mOutput` → E, never acquired
while holding `this`): every update recomputes the composite from current
fields and the native setter early-returns on no-change, so concurrent
tap/echo/connect updates converge with at most one redundant engine call.
`shutdown` takes the same leaf around flag-clear, join, and destroy, and
flags `mInitialized=false` first, so a racing mute cannot `startRecording`
after the join; its disconnect notification fires only on a real
true→false talking edge. As §8 predicted, no lock-ordering regression
harness landed with the fix — that gap is still open.

Break the cycle on both sides. Either side alone would suffice, but both
is robust — and dropping `@Synchronized` from the 100 Hz hot path also
removes per-frame contention for unrelated setters:

1. **Engine: don't span the native call. [Applied]** Snapshot `nativeHandle` under
   the monitor, release, then call `nativeProcessFrame`. Lifetime is
   already protected by `shutdown` joining capture (`mInput.shutdown()`)
   before `destroy`; document and enforce that ordering (or add an
   in-flight guard — snapshot-then-call also races setter-vs-destroy on
   other threads, so the join-before-destroy invariant needs asserting,
   not just commenting) instead of holding E across JNI + upcall.
2. **Mute path: decide under A, act outside. [Applied, plus the `mMuteLock` leaf]** Snapshot the composite
   mute decision under `synchronized`, release, then call
   `mNativeEngine.setMuted` + `startRecording` outside — the
   `setMaxBandwidth` shape.
3. **Callback: shrink the critical section. [Applied]** Reduce
   `onTalkingStateChanged` to a check-and-set under the lock (`mTalking`
   is already `@Volatile`; an `AtomicBoolean` also works) and fan out to
   `mEncodeListener` / half-duplex mute **outside**. This also drops the
   secondary edges the callback holds while under A (`mOutput` monitor
   via `setHalfDuplexMuted`, plus whatever the encode listener takes).
4. **Shutdown: join outside A. [Diverged, deliberate: holds the new `mMuteLock` leaf across joins — re-lock-only would reopen the resurrection race; see the as-built note and the shutdown comment.]** Move `mInput.shutdown()`,
   `mOutput.stopPlaying()`, and `engine.destroy()` out of
   `synchronized(this)`; re-lock only for flag updates. This also fixes
   the join hazard in §5 item 4, which steps 1–3 alone do not.

## 8. Reproducibility note

The defect is timing-dependent (UI tap vs. 10 ms capture frame during a
talking transition), so it has no deterministic unit repro at the Java
level: `AudioHandler`'s constructor builds the native engine (loads
`.so`s, reads the RNNoise model) and opens capture, and the cycle needs
two live threads plus a JNI round-trip. A future regression test should
assert the *ordering invariant* instead — no `mNativeEngine` call while
holding the `AudioHandler` monitor, and no handler-monitor acquisition
inside `processFrame`'s native span — e.g. via a lock-ordering harness or
a bridge-interface mock that records entry/exit. If the fix lands without
that harness, say so in the commit so the gap stays visible.
