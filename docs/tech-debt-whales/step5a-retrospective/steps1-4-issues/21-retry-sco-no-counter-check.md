# 21 — `RetrySco` skips the session-counter precondition, publishes nothing, and always reports success

| | |
|---|---|
| Category | Spec drift |
| Severity | Benign (downgraded from the audit's "harmful"; see Impact) |
| Verified | Yes, against the current tree and the pre-step-4 tree |
| Area | Route |
| Spec | [route.md § API](../../target-architecture/route.md#api), [route.md § RouteState](../../target-architecture/route.md#routestate) (`Fallback` → `Settling` row) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/route/RouteControl.kt` (`submit`, `onRetrySco` around line 307); `HumlaService.kt` (`retryBluetoothSco`, around line 932); `IHumlaService.kt` (line 73 and its doc comment) |
| Gates | Not gating |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

The spec's `RetrySco` acts only when today's preconditions hold, including
"the route-session counter has not moved", moves `Fallback` → `Settling`,
and its boolean result becomes the next `RouteState`. In the code,
commands are deliberately not epoch-gated, `onRetrySco` checks only
"SCO requested, session live, link idle", starts the link, and publishes
no state. `HumlaService.retryBluetoothSco()` returns `true` whenever the
route controller exists, contradicting `IHumlaService`'s documented
contract.

## What the spec says

`route.md` § API: `data object RetrySco : RouteCommand // today's
retryBluetoothSco; its boolean result becomes the next RouteState`.

`route.md` § RouteState:

> | `Fallback` → `Settling` | `RetrySco`, only when today's `retrySco`
> preconditions hold (SCO requested in preferences, a session is live, the
> SCO link exists and is idle, and the route-session counter has not
> moved); otherwise the command does nothing |

`IHumlaService.retryBluetoothSco` doc: "@return true if bring-up was
(re)started, false if there was nothing to do (link up, starting,
unrequested, or disconnected)."

## What the code does

`RouteControl.submit` comment: "Commands are never epoch-gated:
SessionStarted/SessionEnded sequence the session, and dropping Ended on a
counter race strands Ready (spec any->Idle). Stale device/SCO/callback
paths keep their own epoch guards".

`onRetrySco` (around line 307):

```kotlin
val link = sco ?: return
if (!scoRequested.get() || !hasSession) return
if (!link.isIdle()) return
link.start()
```

No check of the current state (`Fallback`), no counter check, no
publication. `HumlaService.retryBluetoothSco()` (around line 932):
`routeControl.retrySco(); true` (false only if `routeControl` is not
initialized or throws).

## Before the migration

`AudioRouteController.retrySco()` at `4459e745^` (around line 220) checked
`requested && live && link != null && link.isIdle()`, started the link,
then re-validated `host.sessionToken`, `isLive`, and `scoRequested`, and
stopped the link and returned `false` if any had changed. It returned
`true` only for an accepted retry. So both the session re-validation and a
truthful boolean were lost in step 4.

## Impact

- **Stale retry in the next session:** a retry posted during teardown can
  run after `SessionEnded` → `SessionStarted`. It then starts SCO for the
  new session, but only if SCO is requested (a global preference), the new
  session is live, and the link is idle; the new session's own
  `updateScoRoute()` would start SCO under the same conditions anyway. So
  the practical effect is at most a redundant start, which
  `BluetoothScoManager.start()` ignores when not idle. This is why the
  audit's "harmful" rating is downgraded.
- **Boolean contract:** callers cannot distinguish an accepted retry from a
  no-op. Check whether the app uses the result (for example, to show a
  "retrying" message); if it does, it shows it even when nothing happened.
- **State:** no `Fallback` → `Settling` transition is published, so state
  consumers do not see the retry.

## Fix directions

1. Make `retrySco` return a truthful result: have `RouteControl` expose a
   suspending or callback-based retry that reports whether it started, or
   evaluate the preconditions synchronously on a snapshot (accepting a
   benign race) and return that.
2. Either implement the spec's `Fallback` → `Settling` publication on an
   accepted retry (ties into [09](09-route-settling-unobservable.md) and
   [10](10-route-fallback-unreachable.md)) or remove that row from the
   spec.
3. Optionally capture the route-session counter at `submit` time for
   `RetrySco` only (not for `SessionStarted`/`SessionEnded`, which must not
   be dropped), matching the spec's precondition list.

## Acceptance

`retryBluetoothSco()` returns false when nothing was started; spec and code
agree on what a retry publishes; a `RouteControl` test covers retry in
`Fallback`, retry when not requested, and a retry racing a session change.
`./scripts/check.sh` passes.
