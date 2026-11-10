# `HumlaService` Decomposition: Implementation Progress

Tracks implementation of the plan in [`README.md`](README.md) on branch
`feature/humla-route-slice1`. Each slice below is a separate commit;
`./scripts/check.sh` is green at every row.

## Slices

| slice | status | commit | notes |
|---|---|---|---|
| 1 — shrink route state in place | done | `fb8006ca` | `ModeHold` replaces held/claimed/saved; `mSettlePending` and `mSettleFired` deleted; settle becomes an explicit `applyAudioRoute` parameter; stale threading/ownership comments fixed. −4 fields. |
| 2 — Toast → observer event | done | `70885d0f` | `onBluetoothScoFailed` added to `IHumlaObserver` (no-op adapter default), fanned out through `HumlaCallbacks`; `MumlaService` renders the app-package strings (intentional copy tweak, not a pure move: the generic string now reads "using phone speaker or connected audio" instead of the library's "using phone audio", covering the A2DP fallback) and posts the Toast to Main when off-main via a cached handler; `libraries/humla` `HumlaService` no longer imports `android.widget.Toast` (app `MumlaService` still does for its own Toasts). |
| 3 — ports in place | done | `48bfe553` | New `audio/AudioPlatform.kt` (interface + `AndroidAudioPlatform`) and `audio/ScoLink.kt` (`BluetoothScoManager` retrofitted, constructor/scope untouched); every route call site switched one for one. |
| 4 — extract `AudioRouteController` | done | `6f53d175` | New `audio/AudioRouteController.kt` (controller + `Host` + `ModeHold`); route monitor group moved atomically onto a private lock; four choreography copies kept divergent for a mechanical diff. No route field under the service monitor. Truth-table test moved to the controller. |
| 5 — route inputs and pipeline handoff | done | `86d5ffff` | `AppliedRoute` + `decision()`; `createAudioHandler()` pushes the decision into the builder at the single consumption point; `apply()` keeps the `suppressReload` rule and passes `SettingsChanged(reload)`; builder removed from the controller. |
| 6 — tests | done | `dcf7f29e` + `fc8ac19c` | `AudioRouteControllerTest` (fakes + `TestScope` virtual time, no Robolectric) plus `InputModesValidationTest`: settle budget, lagging-sink catch-up, debounce, mode claim/restore guards, foreign-SCO clear-before-create ordering on both legacy and S+ paths, teardown-race suppression, retry re-validation, platform fail-closed edges, device matrix, transmit-mode validation; 28 + 4 tests; `mockito-core` for `AudioDeviceInfo` (route) + `AudioHandler.Builder` (input-modes) doubles. App layer: `ScoFailureMessageTest` pins the Toast reason bucketing. |
| 7 — unify the choreography | done | `fca067bc` | `RouteEvent` + single `onEvent` funnel; `SessionStarted` runs funnel ordering (fixes the counter-after-pipeline drift); `AppliedRoute` replaces both route booleans; `connect()` ends the old session explicitly (pipeline shutdown + `SessionEnded`, no `onDisconnected`). |
| 8 — main-thread confinement | done | `812c0926` | Off-main `onEvent` posted to `scope` (main) with the session token captured; the posted body re-checks it (`shouldDropStaleEvent`, JVM-pinned) so a stale teardown/Toast can never run against a newer session. `stopScoWithNotify` funnels its funnel-owned flag flip when off-main; `onScoFailed` funnels the whole failure. JVM tests stay synchronous (no looper). |

## Configuration track

| item | status | commit | notes |
|---|---|---|---|
| `ConnectionParams` snapshot | done | `33e365bf` | Eleven parameters frozen at the `apply()` boundary under the service monitor, published through one volatile; `connect()` / `onConnectionEstablished()` read one snapshot. Server travels by reference, excluded from value equality; secrets redacted in `toString`. Reconnect stays presence-based. |
| Audio settings applier + `InputModes` | done | `33e365bf` | `protocol/AudioSettings.kt`: five service fields (three mode instances, transmit mode, half duplex) become one holder; knob mapping moves beside `AudioHandler.Builder`. `apply()` is a five-step dispatcher. Unknown transmit mode still throws from `apply()`. |
| Power-hold policy | skipped (optional) | — | Per the plan, do only when those sites next change. |

## Verification

`./scripts/check.sh` green at every commit (native suite + JVM unit tests,
including the controller, validation, and Toast-mapping tests). Slice oracles held: no route field
remains under the service monitor (per the plan, no *route fields* under
the monitor; `connectionParams` is published via one volatile); no `mSco*`/`ModeHold`
route field outside the controller (`AudioHandler.mScoActive` is pipeline
state, out of scope); builder route flags written only in the
`createAudioHandler()` decision push.

## End state (this branch)

- `HumlaService`: 52 → ~26 fields, 2,442 → ~1,880 lines (approximate; recount with `grep -c 'private var|val|lateinit'` and `wc -l`; drift from config-track additions).
- Route state lives in `AudioRouteController` behind `AudioPlatform`/`ScoLink`
  ports; the service owns one instance and forwards three API members.
- Reachability caveats from the plan still apply: the CONNECT-while-CONNECTED
  path is unreachable from the in-app UI (dialog disconnects first) and the
  service is not exported, so the slice-7 session-end fix wants an in-process
  trigger to reproduce on device.
