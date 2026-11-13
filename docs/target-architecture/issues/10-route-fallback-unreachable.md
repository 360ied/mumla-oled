# 10 — `Fallback` is unreachable whenever a media sink exists; `ScoFailed` and the state disagree

| | |
|---|---|
| Status | Decided 2026-11-12: option 1, `Fallback` defined by intent (SCO requested, not active, and a bring-up failed or the link dropped in this route session). `route.md` updated. **Code change remains**: `RouteControl.recompute` must publish `Fallback` on that condition for any decided route (track "SCO failed this route session"), not only for `VOICE_HANDSET`; the media-sink settle recheck must no longer produce `Fallback` by itself. |
| Category | Spec/behavior mismatch (route decisions preserved from before the migration; the new state labels are mis-mapped) |
| Severity | Harmful for the `RouteState` contract (state cannot express "SCO failed, degraded voice" in the common case); no change in audio behavior |
| Verified | Yes |
| Area | Route |
| Spec | [route.md § RouteState](../route.md#routestate) (transition table, Events) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/route/RouteControl.kt` (`recompute`, `updateRoute`, `computeVoiceRoute`, `handleScoFailed`, `armRouteSettle`) |
| Gates | Decide together with [09](09-route-settling-unobservable.md), before step 5a.3 |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

`Fallback` is published only when SCO is requested, the route is voice, and
the decided route is `VOICE_HANDSET`. `VOICE_HANDSET` happens only when
**no media output device** is present. A Bluetooth headset normally
exposes an A2DP media sink, so after an SCO failure the decided route is
`MEDIA` and the controller publishes `Ready(MEDIA, MEDIA)`, never
`Fallback`, while still emitting `RouteEvent.ScoFailed`. The settle budget
also never leads to `Fallback` in that case, because it is only armed for
`VOICE_HANDSET`.

## What the spec says

`route.md` § RouteState:

> | `Settling` → `Fallback` | SCO fails, or the settle budget runs out (today's 3 s recheck, at most 2 rechecks) |
>
> **Events:** `ScoFailed(reason)` when entering `Fallback` because of SCO,
> and `RouteChanged` on every entry to `Ready` or `Fallback`. A feature
> shows the SCO failure message ...; screens and features read the current
> route from `state`.

So `Fallback` is the state for "SCO was wanted and is not available", and
`ScoFailed` accompanies entering it.

## What the code does

Route decision (`updateRoute`, line 499, and `computeVoiceRoute`, line 77):

```kotlin
fun computeVoiceRoute(scoActive: Boolean, mediaDevicesPresent: Boolean): Boolean =
    scoActive || !mediaDevicesPresent

val next = when {
    scoRouteActive -> AppliedRoute.VOICE_SCO
    voice -> AppliedRoute.VOICE_HANDSET
    else -> AppliedRoute.MEDIA
}
```

So with SCO down: no media sink → `VOICE_HANDSET` (voice "by absence");
any media sink (A2DP headset, wired headphones, USB audio) → `MEDIA`.

Fallback mapping (`recompute`, around line 437):

```kotlin
val isFallback = scoRequested.get() && voice &&
    update.route == AppliedRoute.VOICE_HANDSET
if (isFallback && !firstCreate) Fallback(update.route, profile, "SCO unavailable")
else Ready(update.route, profile)
```

SCO failure (`handleScoFailed`, line 411):

```kotlin
_events.tryEmit(RouteEvent.ScoFailed(reason))
recompute("SCO failed", forceReload = true, isRecheck = false, firstCreate = false)
```

With an A2DP sink present, `recompute` decides `MEDIA`, `voice == false`,
so `isFallback == false` and the published state is `Ready(MEDIA, MEDIA)`.

Settle budget (`recompute`, lines ~462-485, and `armRouteSettle`, line
634): the 3 s recheck is armed only when `update.route == VOICE_HANDSET`
("voice by absence"), and its purpose is to re-check whether a media sink
has appeared (the log reason is `"media settle recheck"`). It is not an SCO
timer. The SCO connect timeout and retry live in `BluetoothScoManager`.

## Before the migration

The route decisions (`computeVoiceRoute`, the `VOICE_SCO`/`VOICE_HANDSET`/
`MEDIA` choice, and the media-sink settle recheck) are the same as in
`AudioRouteController` at `4459e745^` (see its `recompute`, around lines
768-850). The pre-migration controller had no `RouteState`; it signalled
`host.onRouteChanged()` and SCO failure separately. Step 4 added the state
and mapped "SCO requested but failed" to `Fallback` only for the
`VOICE_HANDSET` case.

## Impact

- **Audio behavior:** unchanged from pre-migration. After an SCO failure
  with an A2DP headset, audio goes to the media route, as before.
- **State contract:** the published `RouteState` cannot express "SCO was
  requested and failed; running on a degraded route" in the most common
  Bluetooth case. Event (`ScoFailed`) and state (`Ready(MEDIA)`) disagree
  for the same failure. Anything that follows `state` (the spec says
  screens and features should; step 5a.3's voice engine will) sees a
  normal `Ready`.
- The spec's "settle budget runs out → `Fallback`" row describes a timer
  that, in the code, is about media sinks and never produces `Fallback`
  when a sink exists.

## Decision needed

Decide together with [09](09-route-settling-unobservable.md), on `master`:

1. **Define `Fallback` by intent, not by route:** "SCO requested and not
   active" → `Fallback(route = whatever was decided, profile, reason)`,
   whether the decided route is `VOICE_HANDSET` or `MEDIA`. `ScoFailed`
   then always coincides with entering `Fallback`, as the spec says. Pure
   labeling change; audio behavior unchanged. Check `VoiceControl`
   treats `Fallback(MEDIA)` exactly like `Ready(MEDIA)` (it opens in both,
   and restarts only on route/profile change).
2. **Correct the spec** to say `Fallback` exists only for the
   voice-by-absence case, and that `ScoFailed` may accompany a `Ready`.
3. Separate the two timers in the spec: the SCO bring-up budget (owned by
   `BluetoothScoManager`: per-attempt timeout plus one retry) and the
   media-sink settle recheck (owned by `RouteControl`: 3 s, at most 2).

Option 1 is recommended: it makes the state honest at no behavioral cost.

## Acceptance

- `RouteControl` virtual-time tests: SCO requested, media sink present, SCO
  fails → published state is `Fallback(MEDIA, MEDIA, reason)` (option 1) or
  the spec documents `Ready(MEDIA)` (option 2); in both cases the test pins
  the event/state pairing.
- `route.md` transition table and events paragraph match the code.
- `./scripts/check.sh` passes.

## Related

- [09](09-route-settling-unobservable.md),
  [11](11-route-changed-suppressed-on-recheck.md)
