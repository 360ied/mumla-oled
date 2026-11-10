# Technical debt audit — Mumla OLED

Snapshot: audit tree `b282a601`, 2026-11-10 (`master`; verify with
`git log --oneline -1 b282a601`). Follow-up doc commits on `master` revise
wording only; code citations refer to the audit tree — re-search symbols
at `HEAD`.

Status: audit-only, no code changes.

This folder is a systematic technical debt audit of Mumla OLED on `master`.
It records factual findings with file evidence, ranks them by severity, and
proposes a paydown order. It does not land any code changes itself.

## Scope

- **Mumla OLED side:** the `app/` Android application plus the in-tree
  `:libraries:humla` core library (protocol engine, background service
  support, JNI audio pipeline over `AudioRecord`/`AudioTrack` with native
  Opus/RNNoise, no Oboe/AAudio), Gradle/Nix/scripts/CI, tests, docs, and
  licensing, as present on `master` at audit time.
- **Upstream side:** sampled only for protocol parity
  (`FrameValidator`, message-type coverage, permission sums). Upstream
  references below use canonical web URLs or plain-text `src/...` paths,
  never local filesystem paths.

## Method

Five parallel read-only reviewer lanes (top reasoning tier), each sweeping
its seam with exact `path` + symbol citations (no line pins; file paths are
the stable part of each citation — prefer symbol search; historic lane
`path:line` quotes are stale by design, so re-search the named symbol instead),
verified by five independent read-only evidence-auditor lanes that re-read every P0/P1
claim against primary sources and verdict it `VALID` / `PARTIAL` / `INVALID`.
No tests were run for this audit; it is a code-reading audit, not a behavioral
test report. File paths are stable; any `path:line` numbers quoted from lane
reports will drift — search for the named symbol instead.

| Report | Lane | Covers |
|---|---|---|
| [01-app-ui.md](01-app-ui.md) | App UI | `app/` activities, fragments, overlay, preferences, adapters, resources |
| [02-humla-core.md](02-humla-core.md) | Core protocol/service | `libraries/humla` TCP/UDP/TLS, certificates, service lifecycle, DB, logging |
| [03-native-audio.md](03-native-audio.md) | Native audio/JNI | `audio_engine/`, `crypto/`, JNI bridges, Opus/RNNoise, host test suite |
| [04-build-infra.md](04-build-infra.md) | Build/infra | Gradle, Nix flake, scripts, signing, versioning, CI |
| [05-crosscutting.md](05-crosscutting.md) | Cross-cutting | Tests, error handling, docs, licensing, naming, TODO backlog |
| [06-validity-check.md](06-validity-check.md) | Validity check | Per-claim re-verification verdicts, corrections, downgrades |
| [07-remediation-roadmap.md](07-remediation-roadmap.md) | Roadmap | Consolidated Top-10 paydown order plus quick-wins |

Severity vocabulary used throughout:

- **P0** — latent crash, data-loss/auth root, ANR vector, or cold-build
  breakage. Fix before next feature work in the affected seam (P0s are
  per-seam gates and parallelizable unless the roadmap notes a dependency;
  see [07](07-remediation-roadmap.md)).
- **P1** — major hardening: silent failures, lifecycle exposure, missing
  gates, deprecated APIs, accessibility gaps. Fix before release.
- **P2** — moderate: inefficiency, duplication, doc-rot, fragile scripts.
  Fix opportunistically.
- **P3** — minor/report-only: dead paths, tuning notes, style debt.

Effort (rough sizing only): `XS` ≤ half-day, `S` ≤ 2 days, `M` ≤ 1 week.
Ranges (`S/M`) span the endpoints; severity spans (`P0/P1`) mean the slice
touches both severities.

## Executive summary

Mumla OLED is functional but legacy-heavy in the UI layer, fragile in the
native wiring and build cold-path, and silent in several error paths.
Healthy spots: `compileSdk`/`targetSdk 36`, current AGP/Gradle, TCP framing
and message-type coverage matching upstream, consistent `48000/480`
sample-rate end to end, a hardened output JNI bridge, compliant license
headers and no math blocks present (hence no GFM-math violation), and no
absolute paths in docs.

Debt concentrates in five P0 clusters (eight P0 table rows across lanes,
seven unique defects — `modelHash` counted in both [02](02-humla-core.md) and
[04](04-build-infra.md): one core, two native, four app, one build-infra
duplicate — clustered by fix unit) plus 12-site logging silence
([05](05-crosscutting.md)), missing CI workflows ([04](04-build-infra.md)),
and JVM-masked device blind spots ([05](05-crosscutting.md)). No P0 was
overturned by the validity check; softened items were the tint 4th cite
(dropped), volume count (4 → `3+`), foreground-service wording (contract gap
pending app-layer check), signing `hasProperty` wording (hardening stands),
and timing-test scope (unbounded waits only), plus citation corrections (see
[06-validity-check.md](06-validity-check.md)).

### The five P0 clusters (unordered — ship order is [07](07-remediation-roadmap.md))

Do not implement from this summary; build order + done-criteria live in
[07-remediation-roadmap.md](07-remediation-roadmap.md), whose items plus the
linked `01`–`05` tables are the normative slices. Each cluster below
links its normative roadmap item.

1. Cold-build crash: undefined `modelHash` in
   [`libraries/humla/build.gradle`](../../libraries/humla/build.gradle)
   (symbol `prepareRnnoiseModel` / `modelHash`) — breaks fresh clones and
digest rotations. User impact: none at runtime (dev-only build breakage).
   Done when a cold-cache gate run passes. See `07` item 1.
2. Input `nativeCreate` has no JNI failure guards in
   [`NativeAudioInputEngineJni.cpp`](../../libraries/humla/src/main/jni/audio_engine/NativeAudioInputEngineJni.cpp)
   (symbol `nativeCreate`) — silent dead engine or crash on OOM/missing-method.
   User impact: voice capture silently dead (no audio sent) or native crash.
   Done when every failure path returns `0` with cleanup and Kotlin rejects
   the zero handle. See `07` items 2 and 8.
3. Hand-mirrored `RNNModel` layout in
   [`RnnoiseProcessor.cpp`](../../libraries/humla/src/main/jni/audio_engine/RnnoiseProcessor.cpp)
   (symbol `RNNModel`) with branch/tag-unpinned submodules and no
   configure-time rev gate (gitlink-pinned but unenforced) — submodule drift
   silently breaks model load/create/free (crash/freeze/memory unsafety).
   User impact: denoiser silently bypassed or native crash on model change.
   Done when a layout assert plus rev gate fails configure on drift. See `07`
   item 8.
4. Three `AsyncTask` sites in `app/` (deprecated API 30):
   [`ServerConnectTask.kt`](../../app/src/main/java/se/lublin/mumla/app/ServerConnectTask.kt)
   (`AsyncTask`), [`MumlaCertificateGenerateTask.kt`](../../app/src/main/java/se/lublin/mumla/preference/MumlaCertificateGenerateTask.kt)
   (`mContext`), [`ServerInfoTask.kt`](../../app/src/main/java/se/lublin/mumla/servers/ServerInfoTask.kt)
   + `ServerAdapter.kt` (`MAX_ACTIVE_PINGS` pool) —
   one retains an `Activity` context across rotation. User impact: rotation
   loses the certificate dialog or leaks it; server-list pings waste threads.
   Done when no `AsyncTask` remains and pings use a bounded coroutine pool.
   Detail in [01](01-app-ui.md); see `07` item 3.
5. Synchronous DataStore (`runBlocking(Dispatchers.IO)`) on the UI thread in
   [`DataStorePreferenceDataStore.kt`](../../app/src/main/java/se/lublin/mumla/preference/DataStorePreferenceDataStore.kt)
   (symbols `read` / `write`) — the principal P0 ANR vector (other `runBlocking`
   bridges exist in `SettingsRepository` snapshot/update helpers, the database
   `io()` helper, and the `MumlaActivity` crash-check background thread
   (allowlisted — off the UI thread); disposition in `07` item 4 gate). User impact: settings
   binds block main; visible jank or ANR under disk pressure; after the fix
   settings load asynchronously with no main-thread block. Done when no
   `runBlocking` remains on the preference path. See `07` item 4.

## How to cite evidence

Mumla OLED paths are portable relative links into this repository (e.g.
[HumlaConnection](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.kt)).
Upstream paths are plain-text `src/...` references resolving to
[`github.com/mumble-voip/mumble`](https://github.com/mumble-voip/mumble)
(e.g. `src/MumbleProtocol.h`), with
[`src/Mumble.proto`](https://github.com/mumble-voip/mumble/blob/master/src/Mumble.proto)
linked where the protobuf schema is the authority. Code identifiers that
contain the historical product prefix (e.g. `MumlaActivity`) appear only in
backticks or path text; prose always uses the full `Mumla OLED` name.

## State × verification appendix (rows the audit implies but does not fully cover)

| State | Implied code path | Audit coverage | Planned gate |
|---|---|---|---|
| Rotation (phone/tablet, pager position, poller generation) | `ChannelFragment` phone `ViewPager` vs tablet dual-pane branch; 2-page adapter titles; `ServerInfoFragment` 1 s poller surviving view destruction until `onDestroy` | Partial (rotation covered only for `AsyncTask` cert dialog) | Per-fragment rotation rows; `ViewPager2` remediation states adjacent-page retention and restores pager position plus cert-dialog state (`07` item 10) |
| Background / Doze | Partial wake + wifi locks, `WAKELOCK_GRACE_MS`, `START_NOT_STICKY` losing the reconnect schedule on process death | Partial (P2 note only) | 5-row lifecycle table mirroring the backoff-table style (killed-while-connected reposts notification and reschedules; killed-while-reconnecting resumes backoff or documents loss as expected; Doze holds locks only inside the grace window); Doze + process-death cases in the smoke tier |
| Process death (connected vs reconnecting) | Disconnect-state resets; duplicate-connect guard; backoff truth table | Partial | Same lifecycle table; killed-while-connected vs killed-while-reconnecting rows |
| Empty / error conditions | Server-grid `emptyView`; empty-channel hiding default; null/empty release fallback | Partial | Empty/denied/revoked rows in `01` P1/P2 |
| Permission denied / dismissed / revoked | `RECORD_AUDIO` / `BLUETOOTH_CONNECT` / `POST_NOTIFICATIONS` flows incl. dismissed-not-denied; overlay revoke-while-shown (`checkAndRequestOverlayPermission`, `updateOverlayVisibility`); Bluetooth SCO settle window | Partial (overlay P2 bullet only) | Permission-denied states gate icon-label work (`07` item 10); revoke-while-shown in `androidTest/` smoke tier |
| Adjacent tabs / concurrent workers | Poller firing while the ping pool shuts down; dual-tab observers of talk-state; `TalkBroadcastReceiver` vs media-session dispatch | Listed in isolation, not modeled jointly | One concurrent-service-callback row (`repeatOnLifecycle` + shared `Flow` criterion) |
| Main-thread bridges (ANR surface) | `DataStorePreferenceDataStore` `read`/`write`, `SettingsRepository` `snapshotBlocking`/`updateBlocking`, `MumlaRoomDatabase` `io()`, `MumlaActivity` crash-check `runBlocking` (background thread) | Partial (P0 covers the preference path) | `07` item 4 gate: zero `runBlocking` on the preference path; sibling bridges allowlisted with justification |

## Evidence transcripts (scoped, `master` at snapshot)

- `startForeground` in the library: no hits under `libraries/humla/src`
  (sampled reads); app-layer promotion exists in
  `app/.../service/MumlaConnectionNotification.kt` (`startForeground` with
  `FOREGROUND_SERVICE_TYPE_MICROPHONE`) plus manifest
  `foregroundServiceType="microphone"` and the `FOREGROUND_SERVICE_MICROPHONE`
  permission — hence the foreground item is a contract/state-table gap, not
  a definite kill.
- `androidTest/`: `find app/src libraries/humla/src -path "*androidTest*"`
  returns no files and neither `src` tree holds an `androidTest/` dir, while
  `testInstrumentationRunner` is still declared in both modules — zero
  on-device coverage corroborated by `find` + dir listing (no device run).
- `printStackTrace`: 6 app lines (5 active + 1 commented-out) + 7 core
  sites = 12 active production sites, plus 1 intentional reporter use
  (`throwable.printStackTrace(pw)` to a report writer) — corrected total
  12+1.
- Submodules: `.gitmodules` holds two URL-only entries (gitlink-pinned, no
  branch pin); Opus pin recorded in `CMakeLists.txt` comments, RNNoise pin
  in the processor header — neither machine-enforced.
- Toolchain versions: AGP `9.0.1`, Gradle `9.1.0`, protobuf `4.36.2`, NDK
  `27.2.12479018`, CMake `4.1.2` (tri-synced by comment).

## Limitations

- Each lane lists its own scope explicitly. Claims are scoped to what was
  read plus targeted symbol searches; bodies only sample-checked are noted.
- Absence claims (e.g. no `startForeground` in the library, zero
  `androidTest/` coverage) are corroborated by scoped reads and probes, not
  proven by exhaustive enumeration unless stated.
- No builds or on-device runs were executed for this audit.
