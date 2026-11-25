# 33 — `RouteControl`'s `ConfigDelta` bridge API has no callers

| | |
|---|---|
| Status | Open; small. Scheduled for [step 8](../steps/8-clean-up.md) (commit 6) |
| Category | Benign drift |
| Severity | Benign (dead code; misleading audit record, corrected) |
| Verified | Yes, by search of `app/src/main` and `libraries/humla/src/main` |
| Area | Route |
| Spec | [route.md § API](../route.md#api) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/route/RouteControl.kt` (`setScoRequested`, `setHandsetStream`, `isScoRequested`, `decision()`, `handsetStream()`) |
| Gates | Not gating |

## Summary

`RouteControl` has five public members that nothing in production calls:
the setters `setScoRequested` and `setHandsetStream`, and the getters
`isScoRequested`, `decision()`, and `handsetStream()`. The step 4 cutover
(`ec3f882d`) moved route preferences to the `prefs` flow and removed the
setters' only callers in `HumlaService`. The [steps 1–4
conformance audit](../history/step5a-retrospective/whats-still-fine.md)
still described the setters as "the permitted `ConfigDelta` bridge"; that
line now carries a correction.

`scoRequested` and `handsetStream` are atomics only because these setters
read them on the calling thread; every other access is on the route
thread.

## Fix

Delete the five members. `scoRequested` and `handsetStream` can then be
plain fields. On the issue 01 branch, `RouteThreadConfinementTest` calls
`setScoRequested`; drop those calls with the setters.
