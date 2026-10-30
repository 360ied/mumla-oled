# Crash reporter/recorder — design

Status: design (accepted direction: local-only record, user-shared export;
amended after pedantic review — see §10).
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
breadcrumb trails, and NDK symbolication on-device. Test-crash buttons *are*
in scope (see §4), hidden behind a five-tap gesture so they are available
on every build — including release, where R8-obfuscated traces can only be
field-tested — yet undiscoverable by accident.

## 2. Investigation findings

- No existing handler: nothing in `:app` or `:libraries:humla` sets a default
  uncaught-exception handler or consumes
  `ActivityManager.getHistoricalProcessExitReasons()`. Crash-adjacent matches
  are fail-soft log lines only (e.g. `HumlaTCP`, `HumlaUDP`), plus a five-table
  atomic-cleanup comment in
  [MumlaRoomDatabase](../app/src/main/java/se/lublin/mumla/db/MumlaRoomDatabase.kt).
- Single process: the
  [manifest](../app/src/main/AndroidManifest.xml) declares no
  `android:process`, so one
  [MumlaApplication](../app/src/main/java/se/lublin/mumla/app/MumlaApplication.kt)
  instance covers the UI and `MumlaService`. The install point is
  `MumlaApplication.onCreate()`, but it must be the *first* statement — ahead
  of the current `Settings.getInstance` / theme / DataStore work — so that no
  startup throw precedes the handler. Limits: per-thread handlers override the
  default, anything running before `Application.onCreate` (e.g. a future
  `ContentProvider`) is never covered, and native faults never pass through
  the Java handler at all. The handler adds same-process fidelity; the
  exit-info drain below is the backstop, not the other way around.
- `minSdk 30` makes `ApplicationExitInfo` universally available (added in API
  30; see [app/build.gradle](../app/build.gradle) and
  [humla/build.gradle](../libraries/humla/build.gradle), both `minSdkVersion
  30`, three ABIs, single `foss` flavor). Draining
  `getHistoricalProcessExitReasons()` on startup captures Java crashes,
  native crashes (`REASON_CRASH_NATIVE`, including the Opus/RNNoise JNI path),
  ANRs, and low-memory kills, with system-provided epoch-millis timestamps.
  Trace availability differs by reason: Java crashes and ANRs (all-threads
  dump) carry a `traceInputStream`; low-memory kills and normal exits carry
  none — and the stream may be null or throw even for crashes, so every read
  must handle null, catch `IOException`, close the stream, and stream with a
  byte cap rather than reading fully into memory. Timestamps are wall-clock
  (`getTimestamp()`), so clock changes are possible — the drain and dedup
  logic (§4) must not assume monotonicity.
- FOSS constraints: the project ships a single `foss` flavor aimed at F-Droid
  distribution. ACRA is GPL-compatible but brings a backend and upkeep;
  Firebase Crashlytics / Sentry bring proprietary dependencies and network
  exfiltration, which conflict with the project's privacy posture. Rejected —
  see §8.
- Release builds run R8 (`minifyEnabled = true`). Stack traces from release
  builds are obfuscated unless line numbers survive; the current
  [proguard-rules.pro](../app/proguard-rules.pro) keeps JNI entry points and
  parcelables but contains no `-keepattributes SourceFile,LineNumberTable`
  line. v1 adds it unconditionally (negligible size cost; see §7 step 0) —
  per-version `mapping.txt` is local-only and F-Droid builds differ, so
  shipping obfuscated traces with a "retrace later" note would leave
  maintainers with unusable field data.
- Settings live in a Preferences DataStore behind
  [Settings](../app/src/main/java/se/lublin/mumla/Settings.kt) /
  [SettingsRepository](../app/src/main/java/se/lublin/mumla/SettingsRepository.kt)
  (41 mirrored keys, `settings_*.xml` widgets). Recording is always on (§3.6
  explains why no toggle): the crash path must never touch DataStore
  (`snapshotBlocking()` uses `runBlocking`, which risks deadlock on a dying
  thread), so a toggle would need a volatile startup-cached flag — machinery
  unjustified for a recorder that performs no network I/O.
- No `FileProvider` is declared today. v1 adds one small `<provider>` entry:
  reports are shared as a `.txt` file attachment (`ACTION_SEND` +
  `EXTRA_STREAM` from the provider, plus a one-line `EXTRA_TEXT` summary),
  because a stack-trace payload does not fit inline text — Binder's ~1 MB
  shared transaction buffer makes a ~256 KB `EXTRA_TEXT` a
  `TransactionTooLargeException` risk. (The in-tree `ACTION_SEND` precedent in
  `FavouriteServerListFragment` sends a one-line URL and is not comparable.)

## 3. Requirements

1. Capture Java crashes (all threads), native crashes, and ANRs on every
   supported device (API 30–36, three ABIs).
2. Persist reports in the no-backup app-private directory
   (`getNoBackupFilesDir()/crash-reports/` — persistent across launches,
   excluded from Auto Backup; `getCacheDir()` is *not* acceptable because the
   system may evict it, and plain `filesDir/` *is* backed up while
   `allowBackup="true"` has no exclusion rules).
3. Never exfiltrate: the only outbound path is an explicit user tap on Share.
   Backup exclusion (§3.2) is what makes this hold — it is a v1 requirement,
   not an open question.
4. Scrub server-identifying and user-identifying data before persisting
   (hostnames, IPs, usernames, credentials, chat text); the report preview
   must show exactly what would be shared.
5. Bounded storage: cap count (newest 8) and per-file size (~256 KB, enforced
   during a byte-capped streaming copy — never read-fully-then-drop);
   recording must never itself crash the app or fill the disk (every I/O path
   is guarded and fail-silent).
6. Survive process death: the crash-time write path uses only synchronous,
   allocation-light file I/O (no DataStore, no Hilt, no coroutines, no
   `runBlocking`). Handler work is capped (pre-sized buffer, truncated stack,
   byte-capped stream copy) because on an `OutOfMemoryError` path the
   handler's own allocation can throw. Delegation to the previous default
   handler happens in a `finally` block, never as a post-write call.
7. GPL-3.0-or-later clean: no new proprietary or network-service dependencies.

## 4. Architecture

New code lives in `:app` (the library must stay UI- and storage-policy-free) —
the sole exception is the §4 JNI hook, whose C++ source lives in
`:libraries:humla`'s `audio_engine`:

- `util/CrashReporter.kt` — installable singleton. `install(context)` runs as
  the first statement of `MumlaApplication.onCreate()` (before
  `HumlaService.init()` and before `Settings` access):
  1. chains the previous default uncaught-exception handler, writes the
     current throw's stack trace + header synchronously to a new report file,
     then delegates to the previous handler in a `finally` block (preserving
     the system's crash dialog / process kill behavior on every path,
     including `Error`/`OutOfMemoryError`);
  2. drains `getHistoricalProcessExitReasons()` for exit reasons newer than
     the persisted last-seen watermark, converting `REASON_CRASH`,
     `REASON_CRASH_NATIVE`, and `REASON_ANR` entries into report files via a
     byte-capped streaming copy of `traceInputStream` (null → trace-less
     report; `IOException` → abandon that entry; always closed; truncation
     recorded in the `truncated` field). The watermark advances past *all*
     observed reasons, including filtered-out low-memory kills and normal
     exits — otherwise every launch re-scans the bounded system history
     window, and a crash loop could evict entries before they are consumed.
     Watermark I/O uses an atomic write-then-rename (tmp file + `renameTo`)
     on the normal path only; the handler never touches it.
- `util/CrashReportStore.kt` — pure file store rooted at
  `getNoBackupFilesDir()/crash-reports/`: list / read / delete / delete-all,
  rotation (keep newest 8), atomic write-then-rename, all I/O wrapped in
  try/catch returning fallbacks. A corrupt file is skipped, never parsed
  leniently.
- `util/CrashReportScrubber.kt` — pure function `scrub(String): String`
  (unit-testable on the JVM). Redaction list: IPv4/IPv6 literals, `user@host`
  tokens, `mumble://` URLs, **bare DNS hostnames** (e.g. `Server.host` values
  appearing as `Failed to connect to voice.example.com` with no URL wrapper),
  TLS identity strings (verified host, SAN names, fingerprints — as surfaced
  by the certificate dialogs in `MumlaActivity`), usernames / passwords /
  tokens, file system paths, and email-shaped tokens. Exception *message*
  transform ("message skeleton"): keep the exception class name and the first
  stack frame; run the redaction list over the message text and, if any
  redaction fires or the message contains `=`/`:`-delimited key material,
  replace the message body with `<redacted>` — messages are guilty until
  proven innocent. Kept verbatim: exception class, class/method/file/line
  frames, thread name (unless it matches a redaction pattern). Note:
  `deviceModel` + `osVersion` in the schema are fingerprintable when pasted
  into a public issue, so the share screen carries a one-line
  public-posting warning.
- Stored schema (one JSON file per incident, UTF-8) vs. shared payload (a
  rendered `.txt` attachment + one-line summary — never the raw JSON):
  `id` (`<exitTimestamp>-<pid>`, unique under crash loops),
  `exitTimestamp` (system wall-clock, used for drain/dedup joins),
  `capturedAtUtc` (when this device wrote the file),
  `source` (`uncaught-handler` | `exit-info`), `reason` (mapped `REASON_*`
  string), `appVersion` (`BuildConfig.VERSION_NAME` / `VERSION_CODE`; note
  `VERSION_NAME` is `git describe` output and is a commit hash, not semver,
  on untagged dev builds), `osVersion`, `deviceModel`, `abi`,
  `processName`, `pid`, `truncated` (bool), `scrubbedTrace`, and
  `rssKb`/`pssKb` (`0` means unknown — `ApplicationExitInfo` zeroes them when
  unavailable).
- Dedup between the handler-written file and the exit-info entry for the same
  event uses an exact join key — exit `timestamp` + `pid` + `reason`, falling
  back to a content hash of the two traces — never timestamp proximity. (A
  "same minute" window would collapse genuine crash loops and defeat the
  rotation test in §7.)
- UI:
  - [MumlaActivity](../app/src/main/java/se/lublin/mumla/app/MumlaActivity.kt)
    checks the store on startup off the main thread. When at least one
    *unseen* report exists it shows a single-action Snackbar ("Mumla OLED
    closed unexpectedly", action: View) that opens the Crash reports screen;
    swipe/timeout is the dismissal. "Unseen" is a DataStore-backed
    last-viewed report id, read/written on the normal path only, single-shot
    per report — coordinated with the activity's existing pause-dismissal
    discipline so rotation cannot re-prompt or leak.
  - [AboutSettingsFragment](../app/src/main/java/se/lublin/mumla/preference/AboutSettingsFragment.kt)
    gains a "Crash reports" preference opening a **new fragment** (list:
    timestamp + reason; per-report View / Share / Delete; Delete-all). This
    needs [SettingsActivity](../app/src/main/java/se/lublin/mumla/preference/SettingsActivity.kt)
    `ALLOWED_FRAGMENTS` plus
    [preference_headers.xml](../app/src/main/res/xml/preference_headers.xml)
    wiring — a full screen, not a preference tweak.
  - The detail view renders the exact scrubbed `.txt` that Share would send
    (preview = payload, no hidden fields). Share uses the new `<provider>`
    (`EXTRA_STREAM`, `FLAG_GRANT_READ_URI_PERMISSION`) with a one-line
    `EXTRA_TEXT` summary.
  - Crash-test buttons (all builds, hidden): a "Crash test" category
    (`KEY_CRASH_TEST`, inside `CrashReportsFragment`) on the Crash reports
    screen, unlocked by tapping the About version row five times. The tap
    counter lives in the fragment's `onSaveInstanceState` (unlock is
    session-only: process death relocks, which is the safe default); each
    tap still fires the row's existing news-reset, so unlocking re-arms the
    news dialogs once — accepted as harmless. The fifth tap shows a "Crash
    test unlocked" toast. One button per capture path: Java crash (throws an
    uncaught `RuntimeException` on the main thread), native crash (calls
    `se.lublin.mumla.util.NativeCrashTest.crash()`, an `:app` Kotlin object
    that loads the existing `humlaaudio` library — `loadLibrary` is
    idempotent — and binds
    `Java_se_lublin_mumla_util_NativeCrashTest_crash`, implemented with
    `abort()` in a new `audio_engine/NativeCrashTestJni.cpp` added to
    `Android.mk` `LOCAL_SRC_FILES`; `abort()` rather than a null deref
    because `-O3` makes a null write fragile UB, and `JNIEXPORT` already
    overrides `-fvisibility=hidden`), and ANR (posts a 15 s main-thread
    sleep — well past the ~5 s ANR threshold — to guarantee the system ANR
    dialog). The hook is never invoked during normal operation — reachable
    only via the hidden gesture plus confirmation — so audio and cold paths
    are unchanged. Every button shows a confirmation dialog first ("This
    will crash/stall the app and drop any live call. Continue?") that is
    *dismissed before* the crash or block is issued (dismiss, then
    throw/call for crashes; dismiss, then `post` the sleep to the main queue
    for ANR — the ANR dialog would otherwise overlay a frozen confirmation
    and `onPause` dismissal could never run). Buttons are disabled while
    connected (`isConnected()`; the summary explains "Disconnect to
    enable") so no confirmation tap can kill a live voice session. Each
    button exercises the real production paths end to
    end — handler
    write, exit-info drain, exact-key dedup (a button-induced crash must
    yield exactly one report), prompt, and share — which is precisely what
    makes the recorder testable without `adb` induction. ANR pass criterion:
    answer the system dialog with Close (kill), relaunch, exactly one
    `REASON_ANR` report; answering Wait recovers the process with no
    exit-info entry, so Wait is not a pass.

## 5. Privacy

- Local-only by construction: the recorder performs zero network I/O, and
  the report directory lives under `getNoBackupFilesDir()`, which Auto Backup
  never uploads — crash traces cannot ride device backup (§3.2–§3.3).
- Recording itself needs no consent gate (nothing leaves the device), but the
  About screen documents that in one sentence, and Delete-all is one tap away.
- Share preview = share payload: the detail screen shows the scrubbed file
  verbatim, so there is no hidden-field surprise; a one-line warning notes
  that device model / OS version will be visible wherever the report is
  posted.

## 6. Edge cases

- Crash during early startup (before `install()` runs): covered on the *next*
  launch by the `ApplicationExitInfo` drain, which is the real backstop — the
  handler only adds same-process fidelity.
- Crash inside the handler or store: every write path catches `Throwable` and
  gives up silently; the previous default handler is always reached via
  `finally`.
- R8-obfuscated frames: handled by the unconditional `-keepattributes
  SourceFile,LineNumberTable` (§7 step 0), so release traces carry line
  numbers by design rather than by checking defaults.
- Low-memory kills (`REASON_LOW_MEMORY`) and normal exits advance the
  watermark but create no report files — only crash/ANR reasons create files.
- Duplicate events (handler file + exit-info entry for one crash) are merged
  by the exact join key in §4, never by time window.
- Storage pressure: rotation runs on every write; a corrupt file is skipped,
  never parsed leniently.
- System history eviction: because the watermark advances on every drain and
  the drain runs on every startup, a crash loop can only lose entries if the
  app is never relaunched between system-side evictions — accepted and
  documented.

## 7. Implementation plan (for the follow-up worktree branch)

0. Add `-keepattributes SourceFile,LineNumberTable` to
   [proguard-rules.pro](../app/proguard-rules.pro) — determines whether any
   release trace is debuggable, so it precedes all other work.
1. `CrashReportScrubber` + JVM unit tests (redaction cases per §4 list,
   frame preservation, message-skeleton behavior).
2. `CrashReportStore` + JVM unit tests (rotation newest-8, corrupt-file
   tolerance, atomic-write behavior).
3. `CrashReporter.install()` wiring in `MumlaApplication` (first statement),
   exit-info drain with watermark, exact-key dedup.
4. No-backup placement + `<provider>` manifest entry.
5. Crash-reports fragment (list/detail/delete-all), startup Snackbar,
   unseen-state, strings (en source; other locales fall back per existing
   `resourceConfigurations` — including the "Crash test unlocked" toast and
   the crash/stall confirmation text), `ALLOWED_FRAGMENTS` + headers wiring.
6. Manual verification matrix, driven primarily by the §4 test buttons
   (each must yield exactly one report after relaunch): Java throw, native
   fault, ANR, relaunch
   prompt, share-payload inspection (attachment opens, summary line correct),
   rotation under 9+ induced crashes *within one minute* (dedup must not
   merge them), backup-exclusion check (Auto Backup inspector shows no
   report files), `TransactionTooLarge`-free sharing on a low-RAM device.
7. `./scripts/check.sh` green in the worktree before review; no push/merge
   without explicit request. Archive the release `mapping.txt` alongside
   each release build as a matter of hygiene (defense in depth, not a
   substitute for step 0).

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

- Whether the Crash reports screen should also surface the last-seen
  non-crash exits (e.g. low-memory kills) as informational rows — currently
  out of scope; watermark-only.

## 10. Review amendments

Pedantic review of the original version returned 12 defects and 3 pedantic
findings; all were accepted and folded in above. Material changes versus the
first version: storage moved from `filesDir/` to `getNoBackupFilesDir()`
(backup exfiltration); share transport changed from inline `EXTRA_TEXT` to
`FileProvider` attachment (Binder limits); scrubber pattern list extended
with bare hostnames, TLS identities, and credentials plus a defined message
transform; exact drain/dedup/watermark semantics specified; UI re-specified
around Snackbar's single action and a new fragment with allowlist wiring;
recording fixed as always-on; `-keepattributes` made unconditional step 0;
handler delegation specified as `finally` with capped allocation; schema
gained exit-timestamp/pid/truncation fields; in-tree references converted to
portable relative links.
- Crash-test buttons added (one per capture path: Java, native
  via a minimal `audio_engine` JNI hook, ANR), hidden behind a five-tap
  About-version gesture with per-button confirmation — available on all
  builds so release (R8) traces are field-testable — resolving the former
  test trigger open question.
- Review fixes (second pass): tap-counter persistence (`onSaveInstanceState`,
  session-only unlock) and the news-re-arm side effect documented; JNI hook
  fully specified (`audio_engine/NativeCrashTestJni.cpp`, `abort()`,
  `Android.mk` entry, `:app`-side `se.lublin.mumla.util.NativeCrashTest`
  wrapper); confirmation ordered dismiss-then-act; buttons disabled while
  connected; ANR oracle defined (Close, not Wait).
