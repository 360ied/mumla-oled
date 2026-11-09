# `HumlaService` Decomposition: Implementation Progress

Tracks implementation of the plan in [`README.md`](README.md) on branch
`feature/humla-route-slice1`. Each slice below is a separate commit;
`./scripts/check.sh` is green at every row.

## Slices

| slice | status | commit | notes |
|---|---|---|---|
| 1 — shrink route state in place | done | `fb8006ca` | `ModeHold` replaces held/claimed/saved; `mSettlePending` and `mSettleFired` deleted; settle becomes an explicit `applyAudioRoute` parameter; stale threading/ownership comments fixed. −4 fields. |
| 2 — Toast → observer event | done | `70885d0f` | `onBluetoothScoFailed` added to `IHumlaObserver` (no-op adapter default), fanned out through `HumlaCallbacks`; `MumlaService` renders the same two strings from the app package; `HumlaService` no longer imports `android.widget.Toast`. |
| 3 — ports in place | done | `48bfe553` | New `audio/AudioPlatform.kt` (interface + `AndroidAudioPlatform`) and `audio/ScoLink.kt` (`BluetoothScoManager` retrofitted, constructor/scope untouched); every route call site switched one for one. |
| 4 — extract `AudioRouteController` | done | `6f53d175` | New `audio/AudioRouteController.kt` (controller + `Host` + `ModeHold`); route monitor group moved atomically onto a private lock; four choreography copies kept divergent for a mechanical diff. No route field under the service monitor. Truth-table test moved to the controller. |
| 5 — route inputs and pipeline handoff | done | `86d5ffff` | `AppliedRoute` + `decision()`; `createAudioHandler()` pushes the decision into the builder at the single consumption point; `apply()` keeps the `suppressReload` rule and passes `SettingsChanged(reload)`; builder removed from the controller. |
| 6 — tests | done | `dcf7f29e` | New `AudioRouteControllerTest` (fakes + `TestScope` virtual time, no Robolectric): settle budget, lagging-sink catch-up, debounce, mode claim/restore guards, foreign-SCO clear-before-create ordering, teardown-race suppression, retry re-validation, platform fail-closed edges, device matrix. 24 tests; `mockito-core` added for `AudioDeviceInfo` doubles. |
| 7 — unify the choreography | done | `fca067bc` | `RouteEvent` + single `onEvent` funnel; `SessionStarted` runs funnel ordering (fixes the counter-after-pipeline drift); `AppliedRoute` replaces both route booleans; `connect()` ends the old session explicitly (pipeline shutdown + `SessionEnded`, no `onDisconnected`). |
| 8 — main-thread confinement | done | `812c0926` | Off-main `onEvent` posted to `scope` (main); private lock removed, volatile visibility retained; JVM tests stay synchronous (no looper). FIFO ordering holds via main-confined `connect()` + service generation filter. |

## Configuration track

| item | status | commit | notes |
|---|---|---|---|
| `ConnectionParams` snapshot | done | `33e365bf` | Eleven parameters frozen at the `apply()` boundary under the service monitor, published through one volatile; `connect()` / `onConnectionEstablished()` read one snapshot. Server travels by reference, excluded from value equality; secrets redacted in `toString`. Reconnect stays presence-based. |
| Audio settings applier + `InputModes` | done | `33e365bf` | `protocol/AudioSettings.kt`: five service fields (three mode instances, transmit mode, half duplex) become one holder; knob mapping moves beside `AudioHandler.Builder`. `apply()` is a five-step dispatcher. Unknown transmit mode still throws from `apply()`. |
| Power-hold policy | skipped (optional) | — | Per the plan, do only when those sites next change. |

## Verification

`./scripts/check.sh` green at every commit (native suite + JVM unit tests,
including the 24 controller tests). Slice oracles held: no `synchronized(this)`
remains in `HumlaService`; no `mSco*`/`mAppliedVoiceRoute`/`ModeHold` field
outside the controller; builder route flags written only in the
`createAudioHandler()` decision push.

## End state (this branch)

- `HumlaService`: 52 → 23 fields, 2,442 → 1,719 lines.
- Route state lives in `AudioRouteController` behind `AudioPlatform`/`ScoLink`
  ports; the service owns one instance and forwards three API members.
- Reachability caveats from the plan still apply: the CONNECT-while-CONNECTED
  path is unreachable from the in-app UI (dialog disconnects first) and the
  service is not exported, so the slice-7 session-end fix wants an in-process
  trigger to reproduce on device.
