# Technical debt audit — Mumla OLED

Snapshot: audit tree `b282a601`, 2026-11-09 (`master`; verify with
`git log --oneline -1 b282a601`). Follow-up doc commits on `master` revise
wording, severities, and remediations (round 4 in
[06](06-validity-check.md#round-4-severity-and-remediation-review)); code
citations refer to the audit tree — re-search symbols at `HEAD`.

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
| [07-remediation-roadmap.md](07-remediation-roadmap.md) | Roadmap | Consolidated Top-9 paydown order plus quick-wins |

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
Ranges (`S/M`) span the endpoints; `M+` means likely more than a week; severity spans (`P0/P1`) mean the slice
touches both severities.

## Executive summary

Mumla OLED is functional but legacy-heavy in the UI layer, fragile in the
native wiring and build cold-path, and silent in several error paths.
Healthy spots: `compileSdk`/`targetSdk 36`, current AGP/Gradle, TCP framing
and message-type coverage matching upstream, consistent `48000/480`
sample-rate end to end, a hardened output JNI bridge, compliant license
headers and no math blocks present (hence no GFM-math violation), and no
absolute paths in docs.

Debt concentrates in one P0 — the cold-build `modelHash` break, counted in
both [02](02-humla-core.md) and [04](04-build-infra.md) — plus a P1 tier led
by missing CI workflows ([04](04-build-infra.md)), silent TCP send failures
and 12-site logging silence ([02](02-humla-core.md), [05](05-crosscutting.md)),
unguarded native input creation and an unenforced RNNoise layout pin
([03](03-native-audio.md)), main-thread blocking bridges and a retained
`Activity` in certificate generation ([01](01-app-ui.md)), and JVM-masked
device blind spots ([05](05-crosscutting.md)).

The first validity check overturned no P0; it softened the tint 4th cite
(dropped), volume count (4 → `3+`), foreground-service wording (contract
gap), signing `hasProperty` wording (hardening stands), and timing-test scope
(unbounded waits only). A round-4 review against the P0 definition and
upstream ([06](06-validity-check.md#round-4-severity-and-remediation-review))
then:

- downgraded four former P0 clusters (input JNI guards, `RNNModel` mirror,
  `AsyncTask`, DataStore) to P1, because none is a latent crash, data loss,
  ANR, or build break in the shipped code;
- dropped the certificate-hardening item, because serial `1`, RSA-2048, and
  20-year validity match upstream Mumble exactly, and replaced it with the
  key-backup exposure it missed;
- replaced the infeasible `static_assert` layout gate with a configure-time
  revision/struct-text check (or removal of the mirror via an RNNoise patch);
- widened the main-thread bridge item to cover the Room `io()` helper, which
  runs real SQLite queries on the main thread.

### The P0 (ship order is [07](07-remediation-roadmap.md))

Do not implement from this summary; build order + done-criteria live in
[07-remediation-roadmap.md](07-remediation-roadmap.md), whose items plus the
linked `01`–`05` tables are the normative slices.

1. Cold-build crash: undefined `modelHash` in
   [`libraries/humla/build.gradle`](../../libraries/humla/build.gradle)
   (symbol `prepareRnnoiseModel` / `modelHash`) — breaks fresh clones and
   digest rotations. User impact: none at runtime (dev-only build breakage).
   Done when a cold-cache gate run passes. See `07` item 1.

### Former P0 clusters (P1 since round 4)

Each still has a roadmap item; the downgrade changes urgency, not validity.

- Input `nativeCreate` has no JNI failure guards in
  [`NativeAudioInputEngineJni.cpp`](../../libraries/humla/src/main/jni/audio_engine/NativeAudioInputEngineJni.cpp)
  (symbol `nativeCreate`). Facts hold, but `app/proguard-rules.pro` keeps
  both listener methods, so the missing-method path needs a code change to
  trigger, and a failed `new` aborts on Android anyway. Cheap parity with the
  output bridge. See `07` item 4.
- Hand-mirrored `RNNModel` in
  [`RnnoiseProcessor.cpp`](../../libraries/humla/src/main/jni/audio_engine/RnnoiseProcessor.cpp)
  (symbol `RNNModel`). The submodule is gitlink-pinned, so drift needs a
  deliberate bump; the hazard is an unguarded bump, not shipped code. The
  upstream struct lives in `rnnoise/src/denoise.c`, not a header, so a
  `static_assert` has nothing to compare against. See `07` item 7 and the
  RNNoise quick-win.
- `AsyncTask` in `app/` (deprecated API 30, still functional on API 36).
  Only [`MumlaCertificateGenerateTask.kt`](../../app/src/main/java/se/lublin/mumla/preference/MumlaCertificateGenerateTask.kt)
  (`mContext`) has a real defect (retained `Activity` across rotation, P1);
  [`ServerConnectTask.kt`](../../app/src/main/java/se/lublin/mumla/app/ServerConnectTask.kt)
  already uses `applicationContext`, and the
  [`ServerInfoTask.kt`](../../app/src/main/java/se/lublin/mumla/servers/ServerInfoTask.kt)
  ping pool is shut down on every rebuild (both P2). See `07` item 5.
- Main-thread blocking bridges (`runBlocking(Dispatchers.IO)`):
  [`DataStorePreferenceDataStore.kt`](../../app/src/main/java/se/lublin/mumla/preference/DataStorePreferenceDataStore.kt)
  (`read` / `write`), `SettingsRepository` `snapshotBlocking` /
  `updateBlocking` (18 call sites via the legacy `Settings` facade), and
  `MumlaRoomDatabase` `io()`. DataStore reads hit its in-memory cache after
  first load and only writes wait on disk; the Room bridge runs real SQLite
  queries on main and is the larger ANR surface. The `MumlaActivity`
  crash-check `runBlocking` runs on a background thread and stays
  allowlisted. See `07` item 6.

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
| Rotation (phone/tablet, pager position, poller generation) | `ChannelFragment` phone `ViewPager` vs tablet dual-pane branch; 2-page adapter titles; `ServerInfoFragment` 1 s poller surviving view destruction until `onDestroy` | Partial (rotation covered only for `AsyncTask` cert dialog) | Per-fragment rotation rows; `ViewPager2` remediation states adjacent-page retention and restores pager position plus cert-dialog state (`07` item 9) |
| Background / Doze | Partial wake + wifi locks, `WAKELOCK_GRACE_MS`, `START_NOT_STICKY` losing the reconnect schedule on process death | Partial (P2 note only) | 5-row lifecycle table mirroring the backoff-table style (killed-while-connected reposts notification and reschedules; killed-while-reconnecting resumes backoff or documents loss as expected; Doze holds locks only inside the grace window); Doze + process-death cases in the smoke tier |
| Process death (connected vs reconnecting) | Disconnect-state resets; duplicate-connect guard; backoff truth table | Partial | Same lifecycle table; killed-while-connected vs killed-while-reconnecting rows |
| Empty / error conditions | Server-grid `emptyView`; empty-channel hiding default; null/empty release fallback | Partial | Empty/denied/revoked rows in `01` P1/P2 |
| Permission denied / dismissed / revoked | `RECORD_AUDIO` / `BLUETOOTH_CONNECT` / `POST_NOTIFICATIONS` flows incl. dismissed-not-denied; overlay revoke-while-shown (`checkAndRequestOverlayPermission`, `updateOverlayVisibility`); Bluetooth SCO settle window | Partial (overlay P2 bullet only) | Permission-denied states gate icon-label work (`07` icon-label quick-win); revoke-while-shown in `androidTest/` smoke tier |
| Adjacent tabs / concurrent workers | Poller firing while the ping pool shuts down; dual-tab observers of talk-state; `TalkBroadcastReceiver` vs media-session dispatch | Listed in isolation, not modeled jointly | One concurrent-service-callback row (`repeatOnLifecycle` + shared `Flow` criterion) |
| Main-thread bridges (ANR surface) | `DataStorePreferenceDataStore` `read`/`write`, `SettingsRepository` `snapshotBlocking`/`updateBlocking`, `MumlaRoomDatabase` `io()`, `MumlaActivity` crash-check `runBlocking` (background thread) | Partial (P1 covers preference path plus Room) | `07` item 6 gate: zero main-thread `runBlocking` on the preference and Room paths; only the background crash-check stays allowlisted |

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
- Certificate parity (round 4): upstream `src/SelfSignedCertificate.cpp`
  sets serial `1` (`ASN1_INTEGER_set(serialNumber, 1)`), 2048-bit RSA, and
  `X509_gmtime_adj(notAfter, 60 * 60 * 24 * 365 * 20)` — the same values as
  `HumlaCertificateGenerator`. The app database stores any PKCS#12 password
  next to the blob (`MumlaDatabase.addCertificate(name, certificate,
  password)`). The manifest sets `android:allowBackup="true"` with no
  `dataExtractionRules`/`fullBackupContent`.
- RNNoise layout (round 4): `struct RNNModel` is defined in
  `rnnoise/src/denoise.c`; `rnnoise.h` only forward-declares it.
  `rnnoise_model_from_buffer` never sets `file`, which is why the mirror
  exists.
- R8 (round 4): `app/proguard-rules.pro` keeps `onAudioPacketEncoded` and
  `onTalkingStateChanged` on input-listener implementations.

## Limitations

- Each lane lists its own scope explicitly. Claims are scoped to what was
  read plus targeted symbol searches; bodies only sample-checked are noted.
- Absence claims (e.g. no `startForeground` in the library, zero
  `androidTest/` coverage) are corroborated by scoped reads and probes, not
  proven by exhaustive enumeration unless stated.
- No builds or on-device runs were executed for this audit.
