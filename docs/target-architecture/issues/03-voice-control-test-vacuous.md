# 03 — `VoiceControlTest` never exercises the pipeline

| | |
|---|---|
| Status | Open — deliberately deferred: `VoiceControl` is deleted in 5a.4; the lesson applies to its replacement. |
| Category | Verification gap |
| Severity | Harmful (step 4's central class is behaviorally untested) |
| Verified | Yes, by reading the test and the class |
| Area | Step-4 voice control |
| Spec | [testing.md § Test layers](../testing.md#test-layers) ("voice control with a fake native layer"), [migration.md § Step 4](../migration.md#step-4--voice-control-and-route) item 2 and done-when |
| Code | `libraries/humla/src/test/java/se/lublin/humla/voice/VoiceControlTest.kt`, `libraries/humla/src/main/java/se/lublin/humla/voice/VoiceControl.kt` |
| Gates | Low priority on its own: `VoiceControl` is deleted in step 5a.4. But its replacement must not repeat the pattern |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

`VoiceControl` owns the `AudioHandler` lifecycle (create, recreate on route
or handset change, tear down) and follows `RouteState`. Its test class has
three tests, and **none of them starts a session**, so every test exits
`tryCreate()` at its first line. Pipeline creation, reconfiguration,
recreation, teardown, the attach/detach error paths, and the
`checkNotMainThread()` guards that step 4's done-when relies on are never
executed by any test.

## What the spec says

`testing.md` § Test layers, Virtual time row:

> `HumlaClient`, `Session` with a fake transport, `RouteControl`, voice
> control with a fake native layer, the policy collector

`migration.md` § Step 4, item 2:

> `VoiceControl` (`voice/VoiceControl.kt`), an interim class that owns the
> voice control thread and today's `AudioHandler` lifecycle (create,
> destroy, recreate), following `RouteState`.

Step 4 done-when:

> no `Thread.join` or other blocking wait is reachable from the main thread
> (by review, and `checkNotMainThread()` guards every blocking method in
> `VoiceControl` and `RouteControl`)

## What the code does

`VoiceControlTest.kt` contains:

| Test (line) | What it does | What it asserts |
|---|---|---|
| `sessionLifecycleDoesNotCrashWithoutRoute` (69) | Sets `RouteState.Ready(VOICE_HANDSET, VOICE)`, calls `sessionEnded()`, `close()` | Nothing (no exception) |
| `closeCancelsWithoutPipeline` (86) | `close()` | Nothing |
| `settingsChangeWithoutSessionDoesNotCrash` (93) | Changes `VoiceSettings` | `currentHandler() == null` |

`VoiceControl.tryCreate()` (`VoiceControl.kt:158`) begins:

```kotlin
private fun tryCreate() {
    val session = sessionRef.get() ?: return
    ...
```

`sessionRef` is set only by `sessionStarted(session)` (line 107), which no
test calls. Therefore these are never executed by tests:

- `maybeReconfigure` (line 172): route/handset change detection and the
  "settings-only change re-seeds targets without rebuild" path.
- `recreate` (line 212): tear down the old handler, build a new one, attach
  it to the session; includes `checkNotMainThread()`.
- `buildHandler` (line 304): `AudioHandler` construction with the route's
  profile, handset stream, and settings.
- `tearDown` (line 282): handler detach and shutdown; includes
  `checkNotMainThread()`.
- `onRouteState` (line 138) transitions other than "no session".

The class has no seam for a fake native layer or a fake `AudioHandler`
factory, which is presumably why the tests avoid starting a session.

## Before the migration

Not applicable: `VoiceControl` is new in step 4. Its predecessor logic
lived in `HumlaService` and was not unit-tested either, so this is not a
loss of coverage, but it is a claimed coverage layer that does not exist.

## Impact

- The class that creates, recreates, and destroys the microphone/speaker
  pipeline on every route change has no behavioral test. Regressions in
  "voice follows route state" (open only in `Ready`/`Fallback`, restart on
  route or profile change, stop on `Idle`) would ship unnoticed.
- Step 4's done-when ("`checkNotMainThread()` guards every blocking method
  in `VoiceControl`") was satisfied by reading the code, not by any test
  that runs those methods.
- Pattern risk: step 5a's `VoiceEngine` has the same shape (Kotlin facade
  over a native layer, lifecycle following `RouteState`). The first 5a
  attempt's `VoiceEngineTest` was likewise degraded-mode only
  (`startVoiceDegradedWithoutNative`), which is part of why a dead native
  path passed the gate (see [what went wrong](../history/step5a-retrospective/what-went-wrong.md)).

## Fix directions

Because `VoiceControl` is removed in 5a.4, investing heavily in it is
optional. Two reasonable scopes:

1. **Minimal, now:** introduce an injectable handler factory
   (`(Session, AppliedRoute, VoiceSettings, Int) -> AudioHandler`, or an
   interface) so a test can supply a fake, then add virtual-time tests for:
   session start in `Settling` (no build), transition to `Ready` (build
   once), `Ready` with a different route (recreate), settings-only change
   (no recreate), `Idle` (tear down), `sessionEnded` (tear down), and build
   failure (error path, no crash, retry on next route change).
2. **Carry the lesson into 5a instead:** require that the 5a.3 `VoiceEngine`
   facade has a fake native layer from day one and that its tests start a
   session and drive `RouteState` through every transition. Record this in
   the 5a.3 done-when if not already implied.

## Acceptance (for option 1)

- At least one test calls `sessionStarted` and asserts a fake handler was
  built for `Ready`, rebuilt on a route change, and destroyed on `Idle`.
- The tests run under `testFossDebugUnitTest` (fast) and pass in
  `./scripts/check.sh`.

## Related

- [04](04-session-transport-virtual-time-gaps.md): the broader virtual-time
  gap.
- [09](09-route-settling-unobservable.md): `Settling` is never published in
  practice, so a "no build while `Settling`" test can only be written once
  that is resolved or against a hand-driven `RouteState` flow (which the
  test harness already uses).
