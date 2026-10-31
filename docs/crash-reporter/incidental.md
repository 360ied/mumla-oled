# Crash reporter review: incidental / pre-existing findings

Findings surfaced by the `feat/crash-reporter` pedantic review that are
pre-existing, out of scope, or accepted trade-offs. Filed here so the
fix commits stay scoped to concurred defects, per review handoff.

## 1. Magic theme strings in `MumlaApplication`

[MumlaApplication.kt](../../app/src/main/java/se/lublin/mumla/app/MumlaApplication.kt) uses literals
`"forceLight"` / `"forceDark"` while `Settings.DEFAULT_THEME` /
`Settings.DEFAULT_THEME_SYSTEM` exist as constants. Pre-existing pattern,
visible in the crash-reporter slice only because `install()` sits in the
same `onCreate`. Prefer constants on next touch; no behavior change.

## 2. Base `app_name` violates the naming convention

[strings_notranslate.xml](../../app/src/main/res/values/strings_notranslate.xml) defines `app_name` as
"Mumla", violating the "ALWAYS Mumla OLED" convention. Currently masked by
[app/build.gradle](../../app/build.gradle) `resValue "Mumla OLED"` in both build types, so the
shipped label is correct. Clean the base value when convenient; no action
required in the crash-reporter slice.

## 3. `NativeAudioInputEngine` loads without a `try/catch`

[NativeAudioInputEngine.kt](../../libraries/humla/src/main/java/se/lublin/humla/audio/NativeAudioInputEngine.kt) loads `humlaaudio` in
`init` without a guard, so a missing library throws
`ExceptionInInitializerError` at class-load time. Sibling
`NativeAudioOutputEngine` and the new `NativeCrashTest` capture the error
in a fail-safe `loadError`. Inconsistency predates the crash reporter;
`NativeCrashTest` correctly chose the fail-safe pattern. Align on next
audio touch if desired.

## 4. `NativeCrashTest.isAvailable()` probes load only, not the symbol

`isAvailable()` reports `loadLibrary` success, not the presence of the
`nativeCrash` symbol (e.g. a dropped `Android.mk` entry or over-aggressive
R8). Callers gate on it before invoking the hook. A stale `.so` would
still throw `UnsatisfiedLinkError` — a Java crash on the wrong capture
path. Theoretical with the correct `Android.mk` + keep in tree; noted for
counterpart owners, no fix requested.

## 5. Accepted trade-offs (not defects)

- First-run suppresses the crash prompt with no catch-up: a crash that
  predates first-run completion waits an extra launch. Accepted to avoid
  overlaying the certificate guide; documented in code.
- `CrashReportStore` bounds use a `+ SIZE_SLOP_BYTES` slack with a
  check-then-read TOCTOU. Low risk (private app dir); bounds are a
  backstop, not a security gate.
- Share payload intentionally omits nothing debuggable now (triage headers
  included); device model / OS version remain visible wherever a report is
  posted, with a one-line public-posting warning on the share screen.
