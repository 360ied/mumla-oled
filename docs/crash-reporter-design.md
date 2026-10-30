# Crash reporter/recorder — design

Status: design (accepted direction: local-only record, user-shared export).
Scope: Mumla OLED on the `foss` flavor only. No network upload, no third-party
crash SDK.

## 1. Goal

Today Mumla OLED has no crash capture at all: an uncaught Java throw, a JNI
fault in the audio pipeline, or an ANR leaves nothing on the device for the
user (or a maintainer) to inspect. Field reports are therefore "it crashed"
with no stack trace.

The goal is a small, FOSS-friendly crash recorder:

- record Java crashes, native crashes, and ANRs to app-private storage,
- let the user view, share (via the Android share sheet), and delete them,
- send nothing anywhere by itself — every byte that leaves the device does so
  through an explicit user share action.

Non-goals for v1: automatic upload to any backend, analytics/telemetry,
breadcrumb trails, NDK symbolication on-device, and a hidden "test crash"
button in release builds.

## 2. Investigation findings

- No existing handler: nothing in `:app` or `:libraries:humla` sets a default
  uncaught-exception handler or consumes
  `ActivityManager.getHistoricalProcessExitReasons()`. Crash-adjacent matches
  are fail-soft log lines only (e.g. `HumlaTCP`, `HumlaUDP`), plus a five-table
  atomic-cleanup comment in `MumlaRoomDatabase`.
- Single process: the manifest declares no `android:process`, so one
  `MumlaApplication` instance covers the UI and `MumlaService`. One install
  point in `MumlaApplication.onCreate()` (before `HumlaService.init()`) sees
  every thread's uncaught throw.
- `minSdk 30` makes `ApplicationExitInfo` universally available (added in API
  30). Draining `getHistoricalProcessExitReasons()` on startup captures Java
  crashes, native crashes (`REASON_CRASH_NATIVE`, including the Opus/RNNoise
  JNI path), ANRs, and low-memory kills — with system-provided UTC timestamps
  and (for crashes) a `traceInputStream`. No polling service is needed.
- FOSS constraints: the project ships a single `foss` flavor aimed at F-Droid
  distribution. ACRA is GPL-compatible but brings a backend and upkeep;
  Firebase Crashlytics / Sentry bring proprietary dependencies and network
  exfiltration, which conflict with the project's privacy posture. Rejected —
  see §8.
- Release builds run R8 (`minifyEnabled = true`). Stack traces from release
  builds are obfuscated unless line numbers survive; the current
  `proguard-rules.pro` keeps JNI entry points and parcelables but says nothing
  about `-keepattributes SourceFile,LineNumberTable`. v1 must confirm what the
  default `proguard-android-optimize.txt` retains and, if needed, add an
  explicit keepattributes line so traces carry line numbers.
- Settings live in a Preferences DataStore behind `Settings` /
  `SettingsRepository` (41 mirrored keys, `settings_*.xml` widgets). A recorder
  toggle, if added, follows that existing pattern.
- No `FileProvider` is declared today. v1 avoids needing one: reports are
  shared as inline text (`ACTION_SEND` + `EXTRA_TEXT`), which comfortably fits
  a stack trace plus metadata. File attachment (and its provider entry) is
  deferred to v2 if reports grow a log tail.

## 3. Requirements

1. Capture Java crashes (all threads), native crashes, and ANRs on every
   supported device (API 30–36, three ABIs).
2. Persist reports in app-private storage only (`filesDir/crash-reports/`);
   no new permissions, no network calls, no Play Services.
3. Never exfiltrate: the only outbound path is an explicit user tap on Share.
4. Scrub server-identifying and user-identifying data before persisting
   (hostnames, IPs, usernames, chat text); the report preview must show exactly
   what would be shared.
5. Bounded storage: cap count and per-file size; recording must never itself
   crash the app or fill the disk (every I/O path is guarded and fail-silent).
6. Survive process death: the crash-time write path uses only synchronous,
   allocation-light file I/O (no DataStore, no Hilt, no coroutines).
7. GPL-3.0-or-later clean: no new proprietary or network-service dependencies.

## 4. Architecture

New code lives in `:app` (the library must stay UI- and storage-policy-free):

- `util/CrashReporter.kt` — installable singleton. `install(context)` runs
  first in `MumlaApplication.onCreate()`:
  1. chains the previous default uncaught-exception handler, writes the
     current throw's stack trace + header synchronously to a new report file,
     then delegates to the previous handler (preserving the system's crash
     dialog / process kill behavior);
  2. drains `getHistoricalProcessExitReasons()` for exit reasons since the
     last-seen timestamp (persisted alongside the reports), converting
     `REASON_CRASH`, `REASON_CRASH_NATIVE`, and `REASON_ANR` entries —
     including their `traceInputStream` — into report files; dedupes against
     the handler-written file for the same event.
- `util/CrashReportStore.kt` — pure file store: list / read / delete /
  delete-all, rotation (keep newest 8, drop larger than ~256 KB at write
  time), atomic write-then-rename, all I/O wrapped in try/catch returning
  fallbacks.
- `util/CrashReportScrubber.kt` — pure function `scrub(String): String`
  (unit-testable on the JVM): redacts IPv4/IPv6 literals, `user@host`-shaped
  tokens, and `mumble://` URLs; keeps exception class, message skeleton,
  class/method/file/line frames, and thread name. Anything uncertain is
  redacted, not kept.
- Report schema (one JSON file per incident, UTF-8):
  `id` (timestamp-based), `capturedAtUtc`, `source` (`uncaught-handler` |
  `exit-info`), `reason`, `appVersion` (`BuildConfig.VERSION_NAME` /
  `VERSION_CODE`), `osVersion`, `deviceModel`, `abi`, `processName`,
  `scrubbedTrace`, and `rssKb`/`pssKb` when provided by `ApplicationExitInfo`.
  No server list, certificate, username, chat, or logcat in v1.
- UI:
  - `MumlaActivity` checks the store on startup (off the main thread) and
    shows a non-blocking Snackbar — "Mumla OLED closed unexpectedly" with
    View / Share / Dismiss — only when at least one unseen report exists.
  - `AboutSettingsFragment` gains a "Crash reports" preference opening a
    simple list (timestamp + reason) with per-report View / Share / Delete
    and a Delete-all action. The detail view renders the exact scrubbed text
    that Share would send.
  - Share uses `ACTION_SEND` text/plain with the report body; no FileProvider
    in v1.

## 5. Privacy

- Local-only by construction: the recorder performs zero network I/O, and the
  report directory is app-private (`MODE_PRIVATE` files, excluded from
  `allowBackup` consideration — crash traces must not ride device backup; set
  `android:fullBackupContent` exclusion or store under `cacheDir`-adjacent
  no-backup path — implementation detail to confirm).
- Recording itself needs no consent gate (nothing leaves the device), but the
  About screen documents that in one sentence, and Delete-all is one tap away.
- Share preview = share payload: the detail screen shows the scrubbed file
  verbatim, so there is no hidden-field surprise.

## 6. Edge cases

- Crash during early startup (before `install()` runs): covered on the *next*
  launch by the `ApplicationExitInfo` drain, which is the real backstop — the
  handler only adds same-process fidelity.
- Crash inside the handler or store: every write path catches `Throwable` and
  gives up silently; the previous default handler is always delegated to.
- R8-obfuscated frames: readable class names are not guaranteed in release
  traces; line numbers are the priority (§2). If the keepattributes check
  fails, v1 ships with obfuscated-but-symbolicatable traces and documents how
  to retrace with the matching `mapping.txt`.
- Low-memory kills (`REASON_LOW_MEMORY`) and normal exits are recorded in the
  drain filter-out list, not as reports — only crash/ANR reasons create files.
- Duplicate events (handler file + exit-info entry for one crash) are merged
  by timestamp proximity (same minute, same process) rather than shown twice.
- Storage pressure: rotation runs on every write; a corrupt file is skipped,
  never parsed leniently.

## 7. Implementation plan (for the follow-up worktree branch)

1. `CrashReportScrubber` + JVM unit tests (redaction cases, frame preservation).
2. `CrashReportStore` + JVM unit tests (rotation, corrupt-file tolerance).
3. `CrashReporter.install()` wiring in `MumlaApplication`, exit-info drain
   with last-seen watermark.
4. R8 line-number check; add `-keepattributes` if missing.
5. About-screen list/detail/delete UI + startup Snackbar + strings (en source;
   other locales fall back per existing `resourceConfigurations`).
6. Manual verification matrix: Java throw (debug trigger via `adb`), native
   fault in test worktree only, ANR via blocked main thread, relaunch prompt,
   share payload inspection, rotation under 9+ induced crashes.
7. `./scripts/check.sh` green in the worktree before review; no push/merge
   without explicit request.

## 8. Alternatives considered

- Firebase Crashlytics: proprietary SDK + Play Services + automatic upload;
  incompatible with the FOSS flavor and the no-exfiltration stance.
- Sentry (self-hosted): still a network client in the app plus server upkeep;
  disproportionate for a client with no backend today.
- ACRA with a self-hosted collector: the least-objectionable third party
  (open source), but adds a dependency, a backend to run, and annotation/API
  surface for what `ApplicationExitInfo` + ~300 lines of first-party code
  already cover on `minSdk 30`. Revisit only if symbolication-at-scale or
  upload-consent flows outgrow the local design.
- Logcat ring buffer / breadcrumbs: deferred to v2; continuous logging costs
  battery and risks capturing chat/server text that v1 deliberately excludes.

## 9. Open questions for implementation time

- Exact no-backup placement for the report directory (confirm against the
  current `allowBackup="true"` manifest setting).
- Whether to add a DataStore-backed "crash recording on/off" toggle in
  General settings, or ship always-on-local (recommended: always-on, since
  nothing leaves the device).
- Long-press-version dev trigger for a test crash (debug builds only) versus
  `adb`-only induction.
