# What's Still Fine: Steps 1–4 Audit

Part of the [step 5a retrospective](README.md). A design-conformance read
of steps 1–4 against the [target architecture](../target-architecture/README.md)
at `71f96f81`, prompted by the 5a failure. Method: code read against the
spec, plus `scripts/check_architecture.py`; no tests were run.

> **Superseded in detail** by the deeper
> [steps 1–4 audit](steps1-4-issues/README.md), which found two confirmed
> regressions (SCO manager threading, transport leak on service destroy),
> larger verification gaps than listed below, and route-state semantics
> the spec describes but the code (like the pre-migration code) never
> implemented. The verdict below still holds; "conform" should be read as
> "conform in structure".

## Verdict

Steps 1–4 conform to the design. Field evidence agrees: `v0.27.3`
(steps 1–4) has been in daily use for hours, including Bluetooth, with one
regression (stuck talk indicators, fixed in `f6244c86`). That also meets
step 4's kill criterion, which is phrased as Bluetooth in daily use.

One done-when (step 1) was never satisfied, and there are three smaller
drifts. None justifies reworking steps 1–4; two items should be closed
before the 5a redo.

## The spec was not rewritten

Unlike 5a, steps 2–4 changed no design files; their merges touched only
`PROGRESS.md` and `open-items.md`. Step 1's `enforcement.md` edit tightened
the tripwire patterns and documented step-1 grandfathering (removed in
step 3). Spec commits on `master` between steps concerned 5a.

## Done-when status

| Step | Criterion | Status |
|---|---|---|
| 1 | Harness reproduces the `2e2a067d` stale-callback race, 10 of 10 | **Not met.** Deferred to step-3 tests; never validated (see below) |
| 2 | `Channel`, `User`, `ModelHandler` deleted; `StateImmutabilityTest` walks `SessionState` | Met |
| 3 | `HumlaConnection` deleted; interop drives `HumlaClient` | Met |
| 4 | `AudioHandler`, `AudioRouteController`, `BluetoothScoManager` off the allowlist; `checkNotMainThread()` guards blocking methods in `RouteControl` and `VoiceControl` | Met. "No blocking wait reachable from main" is by review, as specified |
| all | `check_architecture.py` | Passes, 29 files, matching `PROGRESS.md` |

## Conformance by area

- **[Route](../target-architecture/route.md): close.** `HandlerThread`
  `route` with a looper dispatcher; private route-session counter for
  stale-event checks; `submit(RouteCommand)`; `RouteState`, `RouteEvent`,
  and `RoutePreferences` as specified; blocking platform calls on the route
  thread. The public `setScoRequested`/`setHandsetStream` setters beside
  the `prefs` flow are the permitted `ConfigDelta` bridge (until step 7).
- **[Transport](../target-architecture/transport.md): conforms.** Plain
  reader `Thread`, `trySendBlocking` into a bounded inbound channel,
  bounded outbound channel drained by a writer coroutine,
  `checkNotMainThread` on blocking calls.
- **[State](../target-architecture/state.md): conforms.** Every change is
  `_state.update { applyX(...) }` with pure transitions; persistent
  collections; the immutability test walks the roots; handles are
  read-only except `talkState` (documented interim).
- **[Client](../target-architecture/client.md): mostly conforms.**
  Single-parallelism client dispatcher; non-blocking public methods;
  `ClientState` machine; `currentSession`; one `Session` per attempt;
  facts → transport → session in order; teardown in the fixed order (voice
  stop, route `SessionEnded`, session close, publish).

## Gaps

### 1. Step 1's harness validation was never closed

Step 1 found its harness "insensitive by construction" and deferred the
race check to step-3 virtual-time tests. `HumlaClientTest` now has
stale-callback tests (`staleConnectFailureDoesNotClobberNewerAttempt` and
two variants), but nobody confirmed they fail when the guard they protect
is removed. The literal `2e2a067d` revert is no longer possible (that code
is gone); the equivalent is to break `HumlaClient`'s stale-attempt guard in
a throwaway worktree and confirm those tests fail. This matters most for
the 5a redo, which cannot rely on daily use as its field test.

### 2. A generation counter came back (`connectEpoch`)

The spec's [README](../target-architecture/README.md) lists "generation
counters and stale-event checks" among what one-`Session`-per-connection
removes, and step 3 deleted `connectionGeneration`. `HumlaClient` now has
`connectEpoch` (four increment sites, stale checks in `onConnectFailed` and
the reconnect paths). Its comments explain why: a cancelled attempt can
surface a `TransportException` instead of a `CancellationException`, so
catch blocks act on a retired attempt. A structural alternative is to make
cancellation win (for example, `ensureActive()` before mapping errors).
Contained on one dispatcher; a smell, not a hazard. It is also the guard
gap 1 should test.

### 3. Undocumented interim coupling

`HumlaClient` and `Session` take `IHumlaObserver`, `mainPoster`, and
`denyFormatter`: the specified `Session` has five constructor parameters,
the real one thirteen. This is legacy-observer bridging until step 7,
reasonable, but the ground rules' interim list names only the `ConfigDelta`
bridge and interim owners.

### 4. The `talkState` interim now lives longer

`talkState` is a writable `var` on handles that are rebuilt on every
lookup: the documented interim behind the stuck-indicator regression,
meant to be replaced by `VoiceEngine.talkActivity` in 5a. With 5a reverted
it persists, guarded only by a doc rule ("never dedup or cache a handle's
`talkState`"). The 5a redo should land `talkActivity` early.

### 5. Interop covers half the specified scenarios

`MurmurConnectTest` has 5 scenarios (connect/sync/disconnect, two clients,
text, password accepted, password rejected). Missing from the
[testing](../target-architecture/testing.md#murmur-interop) list: channel
join and move, self-mute/deafen echo, kick → `Removed`, server restart →
`Reconnecting` → `Connected`, forced TCP and the UDP → tunnel switch, and
(from 5a) voice. Some lifecycle paths are covered by `HumlaClientTest`
under virtual time, but against fakes, not murmur.

## Before the 5a redo

Close gap 1 and add the gap-5 scenarios (kick, restart/reconnect, mute
echo first). Gaps 2–4 are tracked as open items; see
[proposed-spec-edits.md](proposed-spec-edits.md#open-itemsmd).
