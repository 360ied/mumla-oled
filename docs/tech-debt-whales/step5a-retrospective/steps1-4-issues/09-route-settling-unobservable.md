# 09 — `Settling` is never observable; `Ready` is published before the route is confirmed

| | |
|---|---|
| Category | Spec/behavior mismatch (behavior preserved from before the migration; the spec describes something never implemented) |
| Severity | Harmful **for step 5a.3's design**, not a regression: daily behavior is unchanged from pre-migration |
| Verified | Yes, against the current tree and the pre-step-4 tree |
| Area | Route |
| Spec | [route.md § RouteState](../../target-architecture/route.md#routestate), [route.md § Session start](../../target-architecture/route.md#session-start), [voice.md](../../target-architecture/voice.md) ("Voice follows route state") |
| Code | `libraries/humla/src/main/java/se/lublin/humla/route/RouteControl.kt` (`onSessionStarted`, `recompute`), `voice/VoiceControl.kt` (`onRouteState`, `tryCreate`) |
| Gates | **Gates step 5a.3**: decide whether to implement the spec's `Settling` semantics or correct the spec, before the native voice path is built to follow `RouteState` |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

The spec's session start has voice wait in `Settling` while SCO comes up,
and open streams only when the route is confirmed (`Ready`) or given up on
(`Fallback`). In the code, `onSessionStarted` writes `Settling(target)` and
then, **in the same synchronous call**, `recompute(..., firstCreate = true)`
overwrites it with `Ready(route, profile)` for whatever the route is
*right now*, before SCO has had any chance to connect. No collector can
observe `Settling`. `VoiceControl` therefore builds the pipeline
immediately on the unconfirmed route (handset or media), then rebuilds it
when SCO connects. This is exactly what the pre-migration controller did,
so it is not a regression; but the published state does not mean what the
spec says it means.

## What the spec says

`route.md` § RouteState transition table:

> | `Idle` → `Settling` | `SessionStarted` |
> | `Settling` → `Ready` | The decided route is confirmed (SCO link up, or no SCO wanted) |
> | `Settling` → `Fallback` | SCO fails, or the settle budget runs out (today's 3 s recheck, at most 2 rechecks) |

`route.md` § Session start (sequence diagram): `RouteState = Settling(VOICE_SCO)`,
then "SCO bring-up (up to the settle budget)", then
`RouteState = Ready(VOICE_SCO, VOICE)`, then "open capture and render with
the VOICE profile".

Rules:

> - **Voice follows route state.** The `VoiceEngine` control thread collects
>   `RouteState`. It opens capture and render only in `Ready` or `Fallback`,
>   using that state's profile. While `Settling`, it keeps whatever streams
>   are open (none at session start). On a new `Ready`/`Fallback` with a
>   different route or profile, it restarts the streams. On `Idle`, it stops
>   them.

And the closing remark:

> The cost is a short silence at connect while SCO settles, bounded by the
> existing settle budget. This matches today, where pipeline creation also
> waits for the route decision.

## What the code does

`RouteControl.onSessionStarted()` (line 255):

```kotlin
val target = when {
    scoRequested.get() -> AppliedRoute.VOICE_SCO
    !mediaPresent -> AppliedRoute.VOICE_HANDSET
    else -> AppliedRoute.MEDIA
}
_state.value = RouteState.Settling(target)
hasSession = true
recompute("sync", forceReload = false, isRecheck = false, firstCreate = true)
updateScoRoute()
```

`recompute()` (line 422) computes `shouldPublish = hasSession && (firstCreate
|| forceReload || update.changed)`, which is true because `firstCreate`.
It then builds `next`:

- if `scoRouteActive` is false (always, at session start: it was just reset
  to false), `isFallback = scoRequested && voice && route == VOICE_HANDSET`,
  and `if (isFallback && !firstCreate) Fallback else Ready` → always
  `Ready` on the first pass;
- `_state.value = next`.

`updateScoRoute()` is called **after** this, so SCO bring-up starts only
after `Ready` has been published. The `else if (hasSession && _state.value
is RouteState.Idle)` branch that would publish `Settling` from `recompute`
cannot run here because the state is not `Idle`.

`MutableStateFlow` conflates: a collector suspended on another dispatcher
never sees the intermediate `Settling` value written and overwritten within
one synchronous block on the route thread.

`VoiceControl.onRouteState` (line 138) then sees `Ready(VOICE_HANDSET or
MEDIA, ...)`, and `tryCreate`/`maybeReconfigure` build an `AudioHandler`
for that route. When SCO connects, `onScoLinkChanged(true)` →
`recompute` publishes `Ready(VOICE_SCO, VOICE)`, and `VoiceControl`
recreates the pipeline for the new route.

## Before the migration

At `4459e745^`, `AudioRouteController.onSessionStarted()` was
`recompute("sync", forceReload = false, settle = false, firstCreate = true)`,
and `recompute` called `host.onRouteChanged()` whenever
`firstCreate || (pipeline live && (forceReload || changed))`. That is, the
old controller also built the pipeline immediately on the first route
decision and rebuilt it when SCO connected. The spec's sentence "pipeline
creation also waits for the route decision" is true only in the sense of
the *decision* (handset vs media vs SCO-if-already-up), not *confirmation*
of SCO. Step 4 faithfully ported the old behavior and then labeled the
pre-existing intermediate moment as a `Settling` that nobody can see.

## Impact

- **Today:** none beyond what already existed: on a Bluetooth connect, the
  pipeline is built on handset/media and rebuilt on SCO, which can produce
  a short audible route switch. Daily use with Bluetooth has been fine.
- **Design:** `RouteState` is the contract that step 5a.3's native voice
  engine (and later features and screens) are specified to follow. If 5a.3
  is implemented against the spec ("open only in `Ready`/`Fallback`; hold
  while `Settling`"), it will behave identically to today only by accident.
  If someone later "fixes" `RouteControl` to publish `Settling` properly,
  voice will start waiting up to the SCO budget at every connect, a
  user-visible behavior change that nobody decided on.
- Consumers that read `state` to show route status (the spec intends
  screens and features to) would never show "settling".

## Decision needed

Pick one and record it on `master` before 5a.3 (per the migration ground
rules):

1. **Correct the spec to match behavior.** `Settling` is published only
   for the instant before the first decision (or drop it from session
   start entirely); `Ready` means "decided", not "confirmed"; voice opens
   on the first decision and restarts when SCO connects. Update the
   sequence diagram, the transition table, and the "cost" paragraph.
2. **Implement the spec.** When SCO is requested and not yet up, publish
   `Settling(VOICE_SCO)` and keep it until SCO connects (`Ready(VOICE_SCO)`)
   or fails/times out (`Fallback`); start SCO bring-up before publishing.
   This changes behavior: connect-time silence while SCO comes up (bounded
   by the SCO manager's connect timeout and retry, about 2 × the per-attempt
   budget), in exchange for no double pipeline build. Requires the
   [10](10-route-fallback-unreachable.md) fix too, otherwise `Fallback` is
   unreachable in the common case.

Either way, the 3 s / 2-recheck "settle" in the code is a **media-sink**
settle (waiting for an A2DP sink to enumerate while voice is chosen "by
absence"), not an SCO budget; see [10](10-route-fallback-unreachable.md).
The spec conflates the two.

## Acceptance

- `route.md` and the code agree on when `Settling`, `Ready`, and
  `Fallback` are published.
- A virtual-time `RouteControl` test pins session start with SCO requested:
  either "first published state is `Ready(handset/media)`, then
  `Ready(VOICE_SCO)` after link up" (option 1) or "`Settling(VOICE_SCO)`
  until link up, then `Ready(VOICE_SCO)`" (option 2).
- `./scripts/check.sh` passes.

## Related

- [10](10-route-fallback-unreachable.md),
  [11](11-route-changed-suppressed-on-recheck.md),
  [12](12-route-prefs-skip-debounce-and-settling.md)
- [01](01-sco-manager-two-thread-mutation.md)
