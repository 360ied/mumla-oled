# Remediation roadmap — Mumla OLED debt paydown

Paydown plan for the findings in [01](01-app-ui.md)–[05](05-crosscutting.md)
after the [validity check](06-validity-check.md) (rounds 1–5). Round 5
restructured this file into phases with an explicit release bar, a
finding-by-finding disposition table, and a reconciliation with decisions
already recorded in [`docs/migration/`](../migration/status.md) and
[`docs/humlaservice-decomposition/`](../humlaservice-decomposition/PROGRESS.md).

Do not implement from the `README.md` digest alone; the item text below plus
the linked `01`–`05` rows are the normative slices. Item IDs (`R1`…) are
stable; earlier rounds' ordinal numbers (`item 1`…) are retired.

## How this plan is ordered

- **Phases, not one list.** Phase 0 unblocks and gates; Phase 1 is the
  release bar; Phase 2 is the next hardening cycle; Phase 3 happens only
  when the affected file is next touched for other reasons.
- **Release bar.** A finding blocks the next release tag only if it is
  user-facing, hides user-facing failures, or is a cheap accessibility fix.
  This narrows the README's "P1 — fix before release": P1s outside Phase 1
  are still real but ship in later phases without blocking a tag.
- **Within a phase:** severity first, user-facing over dev-time, cost as a
  tie-breaker.
- **One item, one branch.** Each `R` item is sized to land as a single
  worktree branch with its own gate, per [AGENTS.md](../../AGENTS.md).
- **Audio and lifecycle changes** get a quick manual smoke test on a
  device before merging; no formal checklist.

## Phase 0 — Unblock and gate (now)

- **R1 — Fix `modelHash` → `modelVersionOrDigest` (P0, XS).**
  Two uses in `libraries/humla/build.gradle` (`prepareRnnoiseModel`) name an
  undeclared variable; fresh clones and model rotations fail. Gate:
  `rm -rf libraries/humla/build/model_cache build/test-native && ./scripts/check.sh`
  passes. Source: [02](02-humla-core.md), [04](04-build-infra.md).
- **R2 — Add a CI workflow (P1, S).** `.github/workflows/` does not exist.
  Run `./scripts/check.sh`, `lintFossDebug`, and `assembleFossDebug` on push
  and pull request, plus one job from a cold model cache so R1-class breaks
  fail CI. Needs Nix in the runner; no release signing in CI. Gate: green
  run on `master` after R1; the cold-cache job is present. Source:
  [04](04-build-infra.md). Every other automated guard in this plan is
  advisory until R2 lands.

## Phase 1 — Release bar (blocks the next release tag)

- **R3 — Fail loud on network errors and logging (P1, S).**
  Route TCP send `IOException`s into `error()` with a drop counter; replace
  the 12 active `printStackTrace` sites (5 app + 7 core) with tagged
  `Log.w/e(TAG, msg, e)`; append the throwable to `Log.e(+e.message)` sites;
  promote exception-in-callback `Log.d` to `Log.w`. Gate: `grep -rn
  printStackTrace app/src/main libraries/humla/src/main` shows only the
  `CrashReporter` writer and the commented-out line; send-failure counter
  named in the commit. Source: [02](02-humla-core.md),
  [05](05-crosscutting.md).
- **R4 — Harden native input creation and callbacks (P1, S).**
  Mirror the output bridge in `NativeAudioInputEngineJni.cpp`: `nothrow`,
  null checks, `ExceptionClear`, `delete ctx; return 0` on every failure,
  `GetJavaVM` check, Kotlin zero-handle guard in `NativeAudioInputEngine`;
  daemon attach, per-upcall exception check/log/clear, detach only if
  attached. Gate: failure injection returns `0` with cleanup; host suite
  compiles the touched shims. Source: [03](03-native-audio.md).
- **R5 — Fix the certificate-generate `Activity` leak (P1, S).**
  `MumlaCertificateGenerateTask` holds a raw `Activity` across rotation and
  shows its dialog from a dead instance. Move to a coroutine plus
  `ViewModel`, `applicationContext` for DB work. Only this `AsyncTask` has a
  real defect; the other two wait for R23. Gate: no `Activity` reference
  survives rotation. Source:
  [01](01-app-ui.md).
- **R6 — Label unlabeled icons (P1, XS–S).**
  Add `android:contentDescription` to interactive `ImageView`/`ImageButton`s
  in `channel_row`, `channel_user_row`, `fragment_channel`, `fragment_chat`,
  `fragment_tokens`, `list_drawer_item`, `overlay_user_row`,
  `server_list_row`, and `token_row` (header logo and PTT corner are already
  labeled); enable lint `ContentDescription`. Gate: lint clean on
  interactive icons. Source: [01](01-app-ui.md).
- **R7 — Accumulate short audio reads instead of zero-padding (P2 → bar, S).**
  Included because users hear it: `AudioInputEngine::processFrame` zero-pads
  any read shorter than 480 samples, injecting clicks and depressing
  RMS/VAD/leveler. Accumulate fragments into whole 480-sample quanta and
  expose a partial-read counter. Gate: host test feeding split frames
  produces output identical to whole frames. Source:
  [03](03-native-audio.md) P2.
- **R8 — Use the full product name in user-visible strings (P1, XS).**
  Base `strings.xml` connecting/disconnected/welcome/join/update strings and
  the base `app_name` (masked today by a build-type `resValue` override) use
  the short historical prefix. Batch-replace user-facing prose; keep style
  and username identifiers. Gate: grep of base and locale `strings.xml` for
  the short prefix in user prose is empty. Source: [05](05-crosscutting.md).

## Phase 2 — Next hardening cycle

Ordered; dependencies noted per item.

- **R9 — Move blocking bridges off the main thread (P1, M+).**
  `MumlaRoomDatabase` `io()` runs real SQLite queries on main via
  `runBlocking` and is the larger ANR surface; `DataStorePreferenceDataStore`
  and `SettingsRepository` `snapshotBlocking`/`updateBlocking` (18 call
  sites via `Settings`) block on DataStore writes. The Room KDoc defers its
  fix to "Slice 4 suspend-ifies the service core", but slice 4 landed on
  2026-10-13 without removing the bridge — fix the stale KDoc in the same
  branch. Gate: no main-thread `runBlocking` on the preference or Room
  paths; only the background `MumlaActivity` crash-check stays allowlisted.
  Unblocks
  settings-screen jank work. Source: [01](01-app-ui.md).
- **R10 — Minimal `androidTest/` smoke tier (P1, M).**
  No instrumented tests exist; `returnDefaultValues = true` masks
  `AudioRecord`/`AudioTrack`, overlay, and service lifecycle on the JVM.
  Add service start/bind, overlay add/remove, and push-to-talk broadcast
  tests on a CI emulator (extends R2). Do not block the JVM gate on it.
  Gate: three smoke tests green in CI. Source: [05](05-crosscutting.md).
- **R11 — Settle the foreground-service contract (P1, S). After R10.**
  The app already promotes (`MumlaConnectionNotification` `startForeground`
  with the microphone type). Add the library contract KDoc on
  `HumlaService` plus a 4-row state table (connected/background,
  connecting/background, notification denied on API 33+, Doze vs locks plus
  `START_NOT_STICKY` schedule loss), and add Doze and process-death cases to
  the R10 tier. Gate: table landed with expected behavior per row. Source: [02](02-humla-core.md).
- **R12 — Enforce native destroy lifetime (P1, M). After R4.**
  Today `AudioHandler.shutdown()` calls `AudioInput.shutdown()`, which joins
  capture with no timeout, before `destroy()` — correct, but by convention
  only. Replace with registry/`shared_ptr` discipline mirroring the crypto
  bridge so `destroy()` blocks until callbacks quiesce. Gate: host test
  races destroy against an in-flight callback without a use-after-free
  (ASan on host). Source: [03](03-native-audio.md).
- **R13 — Native host coverage and warnings (P1, M).**
  Build `RnnoiseProcessor.cpp`, the RNNoise sources, and both JNI shims into
  the host suite with a real-model-load test; add `-Wall -Wextra` to Android
  targets (promote to `-Werror` after cleanup). Gate: host suite loads a
  real model fixture; Android builds warning-clean. Source:
  [03](03-native-audio.md).
- **R14 — Check every `opus_ctl` and encode failure (P1, S).**
  Nine `opus_encoder_ctl` returns are discarded; `setBitrate` has no upper
  clamp; `flushAccumulatorLocked` drops encode errors silently. Check, log,
  and clamp; count encode failures. Gate: every `ctl` return checked; host
  test for an invalid bitrate. Source: [03](03-native-audio.md).
- **R15 — Gate the RNNoise layout mirror (P1, S). Only if Q1 has not landed.**
  Fail CMake configure when the RNNoise submodule revision differs from
  `d983458` (`v0.2-14-gd983458`) or when the `struct RNNModel` text in
  `rnnoise/src/denoise.c` changes. Do not use `static_assert(sizeof/offsetof)`:
  the upstream struct is in a `.c` file, so the mirror can only be compared
  with itself. Gate: configure fails on a changed revision or struct.
  Source: [03](03-native-audio.md).
- **R16 — Single-source the CMake version (P1, S).** `4.1.2` is synced by
  comment across `libraries/humla/build.gradle`, `flake.nix`, and
  `scripts/test_native_audio.sh`; single-source it like `gradle/ndk.version`.
  Source: [04](04-build-infra.md).
- **R17 — Pin shell Gradle and `protoc` (P1, S).** Pin to wrapper `9.1.0`
  and catalog protobuf `4.36.2`, or drop shell Gradle from `PATH`; assert
  the match in `check.sh`. Source: [04](04-build-infra.md).
- **R18 — Harden release-signing detection (P1, S).** `findByName` instead
  of `hasProperty`, fail closed instead of the debug fallback, ship a
  `signing.gradle.example`. Gate: `assembleFossRelease` without
  `signing.gradle` fails with a message naming the example. Source:
  [04](04-build-infra.md).
- **R19 — Fail fast on tagless `versionName` (P1, S).** Stop shipping a
  bare short SHA from `git describe --always`; CI fetches tags. Source:
  [04](04-build-infra.md), [05](05-crosscutting.md).
- **R20 — Replace the 1 s `ServerInfoFragment` poll with a flow (P1, S).**
  `repeatOnLifecycle(STARTED)` collecting a service `StateFlow`, as the
  file's own comment proposes. Source: [01](01-app-ui.md).

## Phase 3 — Opportunistic (when the file is next touched)

The Compose rewrite was killed on 2026-10-15 and XML UI stays indefinitely
([status](../migration/status.md)), so these are worth doing — but only
alongside other work in the same files.

- **R21 — Modernize fragment APIs (P1, S/M per file).** `ViewPager2` +
  `FragmentStateAdapter` and `MenuProvider` in `ChannelFragment`; per-
  fragment `MenuProvider` and `RecyclerView` instead of `GridView` in
  `ChannelListFragment`, `ChannelChatFragment`, `FavouriteServerListFragment`;
  `FragmentResultListener` instead of `setTargetFragment`
  (`MumlaPreferenceFragment`); `registerForActivityResult` instead of
  `startActivityForResult` (`CertificateImportActivity`); `tools:text` for
  preview placeholders.
  Source: [01](01-app-ui.md).
- **R22 — Deduplicate UI helpers (P1, S).** One talk-state mapper (with a
  unit test), one tint helper (3 sites), one volume-stream helper (3+
  sites). Source: [01](01-app-ui.md).
- **R23 — Retire the remaining `AsyncTask`s (P2, S/M).** `ServerConnectTask`
  (already `applicationContext`) and the `ServerInfoTask` 50-thread ping
  pool (shut down on each rebuild). Bounded coroutine pool (4–8) with a
  `ViewModel` cache. Gate: `grep -rn AsyncTask app/src` empty. Source:
  [01](01-app-ui.md).

## Quick wins (any time, independent)

- **Q1 — Fix RNNoise upstream (XS).** Send the one-line `model->file = NULL`
  fix for `rnnoise_model_from_buffer`; once in the pinned revision, call it
  directly and delete the mirrored `RNNModel`, which retires R15.
- **Q2 — Decide the certificate key backup policy (P2, XS).**
  `android:allowBackup="true"` with no `dataExtractionRules` puts private
  keys in backups and device transfers. Either document backup as intended
  (identity survives a phone change) or exclude the database.
- **Q3 —** Retarget the two true `Android.mk` hyperlinks to `CMakeLists.txt`;
  banner versioned reports as historical.
- **Q4 —** Add timeouts plus `assertTrue` to `SettingsListenerTest` and
  `IChatMessageTest` wall-clock waits; prefer virtual time.
- **Q5 —** Add a `check.sh` lint rejecting new `printStackTrace`,
  `+ e.message` exception drops, and short product strings in user prose
  (after R3 and R8 land).
- **Q6 —** Annotate `catch (ignored ...)` sites with a one-line reason
  (tally unverified — grep first).
- **Q7 —** File the six network/whisper TODOs as issues; add the one-line
  DB-ownership KDoc on `Server`.

## Reconciliation with recorded decisions

| Prior decision | Where | Treatment here |
|---|---|---|
| `AsyncTask`s stay until the slice-7 Compose rewrite | [porting guide](../migration/porting-guide.md) | Premise gone: slice 7 was killed. R5 fixes the one real defect now; R23 retires the rest opportunistically. |
| XML UI stays indefinitely | [status](../migration/status.md) | Justifies R21/R22 instead of waiting for Compose; kept opportunistic. |
| Room bridge lasts "until slice 4 suspend-ifies the service core" | `MumlaRoomDatabase` KDoc | Slice 4 landed without removing it; R9 owns the bridge and the stale KDoc. |
| Power-hold extraction skipped until those sites next change | [decomposition progress](../humlaservice-decomposition/PROGRESS.md) | Kept. No large-class rewrite item; the reconnect-lock P2 in [02](02-humla-core.md) rides with that extraction. |
| kapt → KSP and Paparazzi re-evaluation deferred | [status](../migration/status.md) outstanding debt | Not duplicated; tracked there. |
| Crash-test buttons ship in all builds | [crash-reporter](../crash-reporter/README.md) | By design (field-testable release traces, confirmation dialog, disabled during calls). Audit P1 closed. |

## Disposition of every P0/P1 finding

Plus the P2s pulled forward or closed in round 5.

| Finding | Source | Severity | Disposition |
|---|---|---|---|
| Undefined `modelHash` | 02, 04 | P0 | R1 |
| No CI workflows | 04 | P1 | R2 |
| Silent TCP send failures | 02 | P1 | R3 |
| 12 `printStackTrace` sites | 05 | P1 | R3 |
| `Log.e(+e.message)` / `Log.d` for exceptions | 05 | P1 | R3 |
| Input `nativeCreate` guards | 03 | P1 | R4 |
| Input callback lambdas | 03 | P1 | R4 |
| Certificate-generate `Activity` leak | 01 | P1 | R5 |
| Unlabeled icons | 01 | P1 | R6 |
| Short reads zero-padded | 03 | P2 | R7 (pulled into the bar: audible) |
| Short product strings | 05 | P1 | R8 |
| DataStore / settings / Room blocking bridges | 01 | P1 | R9 |
| Zero `androidTest/` | 05 | P1 | R10 |
| Foreground-service contract | 02 | P1 | R11 |
| Native destroy lifetime | 03 | P1 | R12 |
| Host-suite blind spots, Android `-Wall` | 03 | P1 | R13 |
| Unchecked `opus_ctl` | 03 | P1 | R14 |
| `RNNModel` mirror | 03 | P1 | Q1, else R15 |
| CMake tri-sync | 04 | P1 | R16 |
| Floating Nix toolchain | 04 | P1 | R17 |
| Release-signing detection | 04 | P1 | R18 |
| Non-hermetic `versionName` | 04 | P1 | R19 |
| `ServerInfoFragment` 1 s poll | 01 | P1 | R20 |
| `ChannelFragment` deprecated APIs | 01 | P1 | R21 |
| Options/context menus, `GridView` | 01 | P1 | R21 |
| `setTargetFragment` | 01 | P1 | R21 |
| `startActivityForResult` | 01 | P1 | R21 |
| Talk-state, tint, volume duplication | 01 | P1 | R22 |
| `ServerConnectTask` `AsyncTask` | 01 | P2 | R23 |
| `ServerInfoTask` ping pool | 01 | P2 | R23 |
| Stale `Android.mk` links | 05 | P1 | Q3 |
| Timing-sensitive tests | 05 | P1 | Q4 |
| Certificate key backup exposure | 02 | P2 | Q2 |
| `MumlaActivity` crash-check thread | 01 | P1 → P3 | Deferred: correct today; rewrite when the file next changes. |
| `MumbleImageGetter` thread pool | 01 | P1 → P3 | Deferred: leaks only if a new caller skips `shutdown()`; revisit when adding a caller. |
| `CrashReportsFragment` 15 s freeze test | 01 | P1 → P3 | Closed: by design (see reconciliation). |
| Certificate hardening (serial, password, key, validity) | 02 | dropped | Closed: matches upstream `src/SelfSignedCertificate.cpp`. |
| `MAX_OPUS_BUFFER_BYTES 1024` | 03 | P2 → P3 | Closed: upstream's encode buffer is 960 bytes (`EncodingOutputBuffer` in `src/mumble/AudioInput.h`); Opus constrains packet size to fit. Note only: 192 kbps is unreachable at 60 ms packets, as upstream. |

## What not to do yet

- No large-class rewrite of `HumlaService`/`HumlaConnection`/`AudioHandler`;
  follow the decomposition plan's "only when those sites next change" rule.
- No bulk license-header rewrite until the dual-attribution convention is
  clarified ([05](05-crosscutting.md)).
- No `versionCode` derivation change until R19 settles the CI tag-fetch
  policy.
- No broad `androidTest/` program beyond R10's three smoke tests until they
  run reliably in CI.
- No Compose revival from this plan; a revival needs its own plan per
  [status](../migration/status.md).
