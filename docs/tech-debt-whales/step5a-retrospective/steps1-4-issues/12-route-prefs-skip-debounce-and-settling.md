# 12 — Re-decisions skip `Settling`; preference changes skip the 300 ms debounce

| | |
|---|---|
| Category | Spec drift |
| Severity | Benign (no user-visible problem known) |
| Verified | Yes (code read) |
| Area | Route |
| Spec | [route.md § RouteState](../../target-architecture/route.md#routestate) (`Ready`/`Fallback` → `Settling` row) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/route/RouteControl.kt` (`onPrefsChanged`, `onDevicesChanged`, `recompute`) |
| Gates | Not gating; fold into the [09](09-route-settling-unobservable.md) decision |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

The spec says a device or preference change that produces a different
decision moves `Ready`/`Fallback` → `Settling`, after a 300 ms debounce.
In the code, device changes are debounced (300 ms) and then publish the new
`Ready`/`Fallback` directly, with no `Settling`. Preference changes are
not debounced at all and also publish directly.

## What the spec says

> | `Ready`/`Fallback` → `Settling` | A device change or preference change produces a different decision (after today's 300 ms debounce) |

## What the code does

Device changes (`onDevicesChanged`, line 346):

```kotlin
debounceJob?.cancel()
debounceJob = scope.launch {
    delay(ROUTE_RECOMPUTE_DEBOUNCE_MS)          // 300 ms
    ...
    recompute("devices changed", forceReload = false, isRecheck = false, firstCreate = false)
}
```

Preference changes (`onPrefsChanged`, line 334):

```kotlin
if (changed) {
    recompute("settings changed", forceReload = true, isRecheck = false, firstCreate = false)
    updateScoRoute()
}
```

No delay. `recompute` never publishes `Settling` unless the current state
is `Idle` (which it is not during a session).

`ROUTE_RECOMPUTE_DEBOUNCE_MS = 300L` (line 74).

## Before the migration

The pre-step-4 controller had no `RouteState`, and its settings path
(`RouteEvent.SettingsChanged` → `recompute("settings changed", event.reload, ...)`)
was likewise not debounced. Behavior is preserved; the spec row describes
a state and a debounce that preference changes never had.

## Impact

- The state never communicates "re-deciding".
- A preference change that requests SCO publishes the non-SCO route
  immediately and restarts voice, then restarts again when SCO connects
  (same double-restart shape as [09](09-route-settling-unobservable.md)).
- A burst of `ConfigDelta` preference updates (for example `bluetoothSco`
  and `audioStream` changing together) causes several back-to-back
  recomputes rather than one.

## Fix directions

Decide with [09](09-route-settling-unobservable.md):

- If the spec is corrected to match behavior, drop the
  `Ready`/`Fallback` → `Settling` row (or limit it to SCO-pending
  re-decisions) and say preference changes apply immediately.
- If the spec's `Settling` semantics are implemented, route preference
  changes through the same 300 ms debounce as device changes, and publish
  `Settling` while an SCO bring-up triggered by the change is pending.

## Acceptance

`route.md` and a `RouteControl` virtual-time test agree on what a
preference change publishes and when. `./scripts/check.sh` passes.

## Related

- [09](09-route-settling-unobservable.md)
