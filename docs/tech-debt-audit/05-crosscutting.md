# Cross-cutting debt — Mumla OLED

Scope: JVM/C++ tests, error handling, docs/GFM, licensing, naming, TODO
backlog, submodules, versioning on `master`. Verified compliant baselines:
no absolute local paths in versioned docs, GFM math uses fenced blocks with
isolation and no padded inline math, upstream refs use canonical web URLs,
`LICENSE` present with correct per-file headers on sampled new and legacy
files, `.gitmodules` holds two direct root entries over `https://`, and the
unit-test inventory is substantial (dozens of JVM tests per module plus
eleven C++ suites).

Quoted user-visible strings below that show the historical short product
prefix are verbatim buggy values cited as evidence; prose in this report
uses the full `Mumla OLED` name.

## P1

| Location | Issue | Effort | Remediation |
|---|---|---|---|
| Stale native-build doc links (`docs/apk-size-analysis/*`, `docs/crash-reporter/*`) | `Android.mk`/`Application.mk` files are deleted (only `CMakeLists.txt` remains) but versioned docs still reference them. Verified: at least two true markdown-link breaks plus several stale prose mentions; remaining versioned lines follow the same pattern but need a grep to lock the total. `cmake-native-build/` is explicitly historical and correctly excluded. | S | Retarget current-docs links to `CMakeLists.txt` (and the weights-file equivalent); pin versioned reports as historical with a banner or fix their links too. |
| 12 verified `printStackTrace()` sites in production paths | `HumlaTCP.kt` (4: teardown, two send overloads, scope close), `HumlaConnection.kt` (3: fatal handler, protobuf catch, UDP error), app channel/server UI (5: server-info, list fragment, three adapter catches). Report claimed 13 production; enumerated plus re-read sites sum to 12 plus one intentional reporter use — one app site unresolved without grep. `printStackTrace` bypasses tags and crash-report hooks. | XS | Replace each with `Log.w/e(TAG, "<op> failed", e)`; resolve the send-path TODO explicitly. |
| Inconsistent logging (`MumlaService.kt`, overlay/HotCorner) | Five `Log.e(TAG, msg + e.message)` sites drop type plus stacktrace; several exception-in-callback logs use `Log.d` with interpolation. HotCorner already passes the throwable (priority only is wrong); overlay interpolates (object lost). Correct tagged-with-throwable pattern exists nearby. | XS | Append `, e` to the `Log.e` calls; promote exception-in-callback logs to `Log.w`. |
| Zero `androidTest/` coverage with JVM framework masking | No `androidTest/` tree found by probe (needs a full `find` to prove zero); `testOptions { returnDefaultValues = true }` means `AudioRecord`/`AudioTrack`, `SoundPool`, overlay `WindowManager`, service lifecycle, and permission paths return defaults on JVM. JNI wiring, overlay interaction, and the Bluetooth route matrix have no on-device exercise. | M | Add a minimal `androidTest/` smoke tier (service start/bind, overlay add/remove, push-to-talk broadcast) gated to CI emulators; document JVM blind spots; do not block the JVM gate on it. |
| User-visible short product strings | Base `app_name` value uses the short prefix (masked today by a build-type `resValue` override); base `strings.xml` connecting/disconnected/welcome/join/update strings use the short prefix with locale mirrors. Style identifiers containing the prefix are exempt. Preferences-file and default-username/locale-mirror lines need a grep to lock. | S | Batch-replace user-facing prose to the full `Mumla OLED` name; fix base `app_name` so the override is defense-in-depth, not a mask. Keep style/username identifiers unless product decides otherwise. |
| Timing-sensitive tests (bounded subset) | `SettingsListenerTest` wall-clock deadline plus sleep; `IChatMessageTest` wall-clock assert. Genuinely unbounded waits were not found where claimed: UDP `await(3s/5s)` uses the correct bounded `assertTrue(await(...))` pattern, and the user-remove test start-gate is bounded by outer `awaitTermination(5s)+assertTrue`. Downgraded per validity check — target only unbounded waits and wall-clock asserts. | S | Add timeouts plus `assertTrue` where missing; prefer virtual time (`runTest` + `advanceTimeBy`, already the in-tree good pattern); keep socket tests but tag/retry-document them. |

## P2

| Location | Issue | Effort | Remediation |
|---|---|---|---|
| TODO/FIXME backlog, 25 sites (submodules excluded) | Load-bearing items cluster in network plus UI: whisper-to-user gap, TCP send/UDP refresh/stats/UDP-thread TODOs, model-handler logging/bitmap/crash-guard TODOs, service notification/certificate TODOs, list-adapter efficiency FIXMEs. Build KSP TODOs are tracked and benign. | S | File the six network/whisper items as issues; leave build TODOs; link tracker IDs or delete the rest. |
| 40+ `catch (ignored ...)` without rationale | Spot-checked cases look correct (teardown, SCO cleanup, timeouts) but carry no comment, so future reviewers cannot distinguish intentional-negligible from swallowed-signal. Tallies need a grep to lock. | XS | Require a one-line reason comment on each; convert non-teardown swallows to `Log.w`. |
| Versioning fragility (`--always` fallback + hardcoded `versionCode`) | Tagless CI yields a bare hash as the version with manual `NEWS_ITEMS` sync — drift-prone and ordering-breaking. Legacy bare-tag match retained deliberately per project versioning rules. | S | Fail closed or append a dev suffix when `describe` falls back; derive or lint-check `versionCode` against tags. |
| Dual-attribution question on heavily reworked legacy files | Major rewrites retain only the 2014 header. Project rules mandate the current header for new files and preservation of existing attributions; dual-attribution on rewrites is implied but unstated. | XS | Clarify the convention; do not bulk-rewrite headers until then. |

## P3 — coverage gaps by area

Well covered: settings slices, certificate import/export/generate, chat
sanitizer/crash triad, reconnect backoff, audio-route policy/controller,
`CryptState`, TLS identity, certificate generator, ping interval,
model-handler topology/mute/remove, protocol tests, DSP C++ suite. Thin or
absent: full service lifecycle (only slices), Java-side audio input (self-noted
unguarded native load vs fail-safe output pattern), overlay/HotCorner
interaction, pinned-TLS/trust-store paths, server-info polling, Room migration
beyond one hop.
