# Technical debt audit — Mumla OLED

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
its seam with exact `path:line` citations, followed by five independent
read-only evidence-auditor lanes that re-read every P0/P1 claim against
primary sources and verdict it `VALID` / `PARTIAL` / `INVALID`. No tests were
run for this audit; it is a code-reading audit, not a behavioral test report.
Line numbers refer to the tree state at audit time and will drift; file paths
are the stable part of each citation.

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
  breakage. Fix before next feature work in the affected area.
- **P1** — major hardening: silent failures, lifecycle exposure, missing
  gates, deprecated APIs, accessibility gaps. Fix before release.
- **P2** — moderate: inefficiency, duplication, doc-rot, fragile scripts.
  Fix opportunistically.
- **P3** — minor/report-only: dead paths, tuning notes, style debt.

## Executive summary

Mumla OLED is functional but legacy-heavy in the UI layer, fragile in the
native wiring and build cold-path, and silent in several error paths.
Healthy spots: `compileSdk`/`targetSdk 36`, current AGP/Gradle, TCP framing
and message-type coverage matching upstream, consistent `48000/480`
sample-rate end to end, a hardened output JNI bridge, compliant license
headers and math formatting, and no absolute paths in docs.

Debt concentrates in five P0s plus silent-failure logging plus missing CI
plus test blind spots. No P0 was overturned by the validity check; two
P1 sub-claims were downgraded and several citations corrected (see
[06-validity-check.md](06-validity-check.md)).

### The five P0s

1. Cold-build crash: undefined `modelHash` in
   [`libraries/humla/build.gradle`](../../libraries/humla/build.gradle) —
   breaks fresh clones and digest rotations.
2. Input `nativeCreate` has no JNI failure guards in
   [`NativeAudioInputEngineJni.cpp`](../../libraries/humla/src/main/jni/audio_engine/NativeAudioInputEngineJni.cpp) —
   silent dead engine or crash on OOM/missing-method.
3. Hand-mirrored `RNNModel` layout in
   [`RnnoiseProcessor.cpp`](../../libraries/humla/src/main/jni/audio_engine/RnnoiseProcessor.cpp)
   with unpinned submodules — submodule drift equals heap corruption.
4. Three `AsyncTask` sites in `app/` (deprecated API 30), one retaining an
   `Activity` context across rotation.
5. Synchronous DataStore (`runBlocking`) on the UI thread in
   [`DataStorePreferenceDataStore.kt`](../../app/src/main/java/se/lublin/mumla/preference/DataStorePreferenceDataStore.kt) —
   the only P0 ANR vector.

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

## Limitations

- Each lane lists its own scope explicitly. Claims are scoped to what was
  read plus targeted symbol searches; bodies only sample-checked are noted.
- Absence claims (e.g. no `startForeground` in the library, zero
  `androidTest/` coverage) are corroborated by scoped reads and probes, not
  proven by exhaustive enumeration unless stated.
- No builds or on-device runs were executed for this audit.
