# 11 — `RouteChanged` is not emitted for state changes made by a settle recheck

| | |
|---|---|
| Category | Spec drift |
| Severity | Benign today (the only subscriber ignores `RouteChanged`); a trap for future consumers |
| Verified | Yes (code read) |
| Area | Route |
| Spec | [route.md § RouteState](../../target-architecture/route.md#routestate) (Events) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/route/RouteControl.kt` (`recompute`, `armRouteSettle`) |
| Gates | Not gating; fold into the [09](09-route-settling-unobservable.md)/[10](10-route-fallback-unreachable.md) route work |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

`recompute` suppresses `RouteEvent.RouteChanged` when called from a settle
recheck (`isRecheck = true`). Settle rechecks can change the published
state (for example `Ready(VOICE_HANDSET)` → `Fallback(VOICE_HANDSET, "SCO
unavailable")` on the first recheck, because `isFallback && !firstCreate`
is only true after the first pass, or `VOICE_HANDSET` → `MEDIA` when a
sink appears). Those state changes happen with no `RouteChanged` event,
and a recheck-driven entry into `Fallback` also has no `ScoFailed`.

## What the spec says

> **Events:** `ScoFailed(reason)` when entering `Fallback` because of SCO,
> and `RouteChanged` on every entry to `Ready` or `Fallback`.

## What the code does

`recompute` (around line 449):

```kotlin
if (changedState || update.changed) {
    _state.value = next
    if (!isRecheck) {
        if (!_events.tryEmit(RouteEvent.RouteChanged)) { ... }
    }
}
```

`armRouteSettle` (line 634) schedules
`recompute("media settle recheck", forceReload = false, isRecheck = true, firstCreate = false)`.

Concrete path: SCO requested, no media sink, SCO not up. Session start
publishes `Ready(VOICE_HANDSET, VOICE)` (first pass: `firstCreate` blocks
`Fallback`) and arms the settle recheck. 3 s later, the recheck computes
`isFallback = true`, `!firstCreate`, so `next = Fallback(VOICE_HANDSET,
VOICE, "SCO unavailable")`, `changedState = true`, state is published, and
no event is emitted.

## Impact

Today only `HumlaService` subscribes to route events, and it ignores
`RouteChanged`, so nothing breaks. Any future feature that relies on the
event stream (the spec's route/event split assumes features can) misses
real route changes.

## Fix directions

Emit `RouteChanged` whenever the published state actually changes,
regardless of `isRecheck`. If the suppression was meant to avoid cue
rebuilds (SoundPool, proximity) on rechecks that change nothing, the
`changedState || update.changed` guard already covers that; if a
recheck-specific suppression is still wanted, gate it on "state unchanged",
not on "is a recheck". When a recheck enters `Fallback` because SCO is
requested and not up, also emit `ScoFailed` (or adjust the spec to say
`ScoFailed` is only for link failures reported by the SCO manager).

## Acceptance

A `RouteControl` virtual-time test: SCO requested, no media sink, link
never comes up; after the 3 s recheck the state is `Fallback` and a
`RouteChanged` event was emitted. `./scripts/check.sh` passes.

## Related

- [09](09-route-settling-unobservable.md),
  [10](10-route-fallback-unreachable.md)
