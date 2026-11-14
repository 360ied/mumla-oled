# 32 — The route decision cached the SCO link state, so leaving SCO stranded the call route

| | |
|---|---|
| Status | Resolved (`1089ad05`). `RouteControl` samples both decision inputs at decision time and applies a preference change to the link before deciding. |
| Category | Confirmed regression (step 4 port) |
| Severity | Harmful, user-visible: turning Bluetooth SCO off mid-session left the VOICE profile and `MODE_IN_COMMUNICATION` in place until the session ended |
| Verified | Yes — device logcat of a reproduced session, plus all four new tests failing against the previous code |
| Area | Route |
| Spec | [route.md § RouteState](../route.md#routestate) (`Ready(route, profile)` semantics, transition table) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/route/RouteControl.kt` (`onPrefsChanged`, `updateScoRoute`, `onScoLinkChanged`, `updateRoute`, `readFacts`) |
| Gates | None; detected and fixed after the step-4 audit queue closed |

Symbols only; this issue was found on a device, not at the audit snapshot,
so line numbers would be misleading.

## Summary

With a Bluetooth headset connected, entering SCO worked and leaving it did
not: the published state stayed `Ready(VOICE_SCO, VOICE)` for the rest of
the session. The decision read the SCO link from a `scoRouteActive` field
fed **only** by the manager's connect/drop callbacks, while
`ScoLink.stop()` reports nothing back by contract. Every stop the
controller performs itself — preference off, `SessionEnded` — was therefore
invisible to the decision, which kept re-deriving `VOICE_SCO` from the
stale flag on every later recompute (device callbacks, the 300 ms debounce,
the 3 s settle rechecks). Only `SessionStarted` reset it, so a reconnect
appeared to fix it.

A second, independent ordering defect sat in the same path:
`onPrefsChanged` recomputed **before** applying the preference to the link,
so even a live read would have published one frame of the route being left.

## What the spec says

`route.md` § RouteState:

> `Ready(route, profile)` means a route has been **decided** ... `VOICE_SCO`
> when the SCO link is active; otherwise `VOICE_HANDSET` when no media
> output device is present ("voice by absence"); otherwise `MEDIA`.

and the transition table:

> `Ready` ↔ `Ready` (different route) | A device change ..., a preference
> change (applied at once, as today), the SCO link coming up or going down ...

"`VOICE_SCO` when the SCO link is active" is a statement about the link,
not about the last event received from it.

## What the code did

- `updateRoute` read the media input live (`queryMediaDevicesPresent()`) but
  took the SCO input from the cached `scoRouteActive`. The controller
  already read the link live for outsiders — `isScoActive()` backs
  `HumlaService.isBluetoothScoActive()` — so the asymmetry was local to the
  decision.
- `onScoLinkChanged` was the only writer inside a session, and
  `BluetoothScoManager.stop()` deliberately fires nothing (its generation
  bump exists to drop stale callbacks, so a teardown cannot be raced).
- `onPrefsChanged` called `recompute` and then `updateScoRoute`, so the
  decision ran while the link was still up.
- `stopScoAndClearFlag` did clear the field, but only `onSessionEnded`
  called it.

Device evidence, one reproduced session (route controller and SCO manager
logs only):

```
SCO connected  -> Ready(VOICE_SCO, VOICE)
settings changed -> Ready(VOICE_SCO, VOICE)     toggle off: no drop, no fallback
settings changed -> Ready(VOICE_SCO, VOICE)     toggle on
SCO connected                                    the link did restart
settings changed -> Ready(VOICE_SCO, VOICE)     toggle off: stuck again
```

Two connects and no disconnect confirm the link was stopped and restarted
while the controller never learned either way.

## What the pre-migration code did

`AudioRouteController.stopScoWithNotify()` (commit `6f53d175`) stopped the
link, set `scoRouteActive = false`, and posted `host.onScoTeardown()`; the
service called it explicitly on the disable path ("Synchronous SCO teardown
with notifications (explicit disconnect, toggle disable)"). Step 4
(`75ea67b2`) replaced the reload with published state, which is why the
notify disappeared — and the flag clear went with it, leaving
`stopScoWithNotify` as a session-end-only stub whose dead
`if (scoRouteActive)` read as if the path were covered.

## Impact

Behaviour the user sees: audio keeps the call profile (VOICE_COMMUNICATION
capture with hardware AEC/NS, `MODE_IN_COMMUNICATION` held) instead of
returning to media, the SCO toggle reads off while the route still reports
SCO, and `onBluetoothScoChanged` never fires because the published state's
SCO-ness never flips. Nothing recovers it inside the session.

## Fix

`1089ad05`, minimal and structural:

- `readFacts()` samples both inputs at decision time (media query plus
  `ScoLink.isActive()`, fail-closed `false`); `updateRoute` decides from
  that sample and `RouteUpdate` carries it, so the `Fallback` gate and
  `counterUnrequestedSystemSco` use the same values.
- `scoRouteActive` is deleted. Link callbacks only trigger a decision;
  `onScoLinkChanged` no longer carries a route and drops its redundant
  "unchanged, skipping reload" early return, since `recompute` already
  gates publishing on a real change.
- `onPrefsChanged` applies the request to the link first, then decides.
- `stopScoAndClearFlag` becomes `stopScoLink(link)`, shared with the
  preference path and idle-guarded.

Making `BluetoothScoManager.stop()` notify was rejected: it breaks the
documented never-callbacks contract that the generation bump depends on,
and a synchronous callback would re-enter `recompute` from inside
`updateScoRoute`.

## Acceptance

Detectors (`libraries/humla/src/test/java/se/lublin/humla/route/RouteControlTest.kt`),
each failing against the previous code:

- `routeDecisionFollowsLinkStateNotEvents` — active SCO, a media sink, the
  link dropped with **no** callback delivered, then an unrelated device
  event: the decision must publish `Ready(MEDIA, MEDIA)`.
- `scoPreferenceOffWhileActiveFallsBackToMedia` — the reported case:
  `Ready(MEDIA, MEDIA)`, the link stopped once, the mode hold released.
- `scoPreferenceOffWithoutMediaSinkKeepsHandsetVoice` —
  `Ready(VOICE_HANDSET)` (voice by absence), not `Fallback`.
- `scoPreferenceOffOnOffRepublishesScoThenMedia` — the device-log sequence.

`./scripts/check.sh` passes.

## Related

- [09](09-route-settling-unobservable.md), [10](10-route-fallback-unreachable.md),
  [11](11-route-changed-suppressed-on-recheck.md),
  [12](12-route-prefs-skip-debounce-and-settling.md) — the same
  `RouteControl` decision path; all four are decisions about *what* is
  published, this one is about *where the input comes from*.
