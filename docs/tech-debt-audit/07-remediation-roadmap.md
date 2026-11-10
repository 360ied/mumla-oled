# Remediation roadmap — Mumla OLED debt paydown

Consolidated Top-10 order from [01](01-app-ui.md)–[05](05-crosscutting.md)
after [validity corrections](06-validity-check.md). P0 first, then
diagnosability, auth, contracts, gates, and UI modernization. Items are
independent except 2↔8 (native guards plus model/destroy gates move together),
4→10-settings (DataStore unblocks settings jank), and 5→diagnosability (logging
makes 6/7 debuggable); quick-wins at the end are safe to parallelize.

Do not implement from the `README.md` digest alone; the item text below plus
the linked `01`–`05` tables are the normative slices, each with its gate.

## Top-10

1. **Fix `modelHash` → `modelVersionOrDigest` (P0, XS).**
   One-line cold-build fix in `libraries/humla/build.gradle`. Unbreaks fresh
   clones, cache evictions, and digest rotations. Gate: cold-cache gate run
   passes. No dependency.
2. **Harden input JNI creation plus callbacks (P0/P1, S).**
   Mirror the output bridge in `NativeAudioInputEngineJni.cpp`: `nothrow`,
   null checks, `ExceptionClear`, `delete ctx; return 0`, `GetJavaVM` check,
   Kotlin zero-handle guard; then daemon attach plus per-upcall
   exception check/log/clear plus detach-only-if-attached. Gate: input-bridge
   failure injection returns `0` with cleanup; host suite compiles the touched
   shims. Blocks the next native-audio change (with item 8).
3. **Retire `AsyncTask` plus the 50-thread ping pool (P0, S/M).**
   `ServerConnectTask`, certificate-generate task (also drops the retained
   `Activity` leak), `ServerInfoTask` pool. Replacement pattern already
   in-tree. Highest deprecation plus thread-leak density. Gate: zero
   `AsyncTask` references; ping pool bounded (4–8) with `ViewModel` cache.
4. **Move DataStore off the UI thread (P0, M).**
   Repository-as-source with `Flow` plus `lifecycleScope` collection. Gates
   all settings-screen jank fixes behind it. Gate: zero `runBlocking` on the
   preference path; settings screens collect a flow. Depends on nothing; item 3 can
   proceed in parallel.
5. **Fail loud on network plus logging (P1, S).**
   Route TCP send `IOException` into `error()` with a drop counter; replace
   12 verified `printStackTrace` sites with tagged `Log.w/e`; append the
   throwable to `Log.e(+e.message)` sites; promote exception-in-callback
   `Log.d` to `Log.w`. Makes the next three network/lifecycle bugs
   diagnosable instead of silent. Gate: zero production `printStackTrace`
   (12 sites); send-failure counter present. No dependency.
6. **Harden client certificates (P1, S).**
   Random serials plus non-empty store password (or a documented threat
   model), larger keys and shorter validity on next rotation. Small,
   high-leverage auth work. Gate: random serials plus non-empty password (or
   documented threat model). No dependency.
7. **Settle the foreground-service contract (P1, S).**
   The app layer already promotes (`MumlaConnectionNotification`
   `startForeground` with microphone type; manifest
   `foregroundServiceType="microphone"`), so this is a docs-plus-states item:
   add the library contract comment plus a 4-row state table
   (connected/background, connecting/background, notification-denied on API
   33+, Doze vs locks plus `START_NOT_STICKY` loss) and extend the smoke tier
   with Doze + process-death cases. Gate: table landed; denied-notification
   path stated.
8. **Pin the native model contract plus destroy lifetime (P0/P1, M).**
   Layout asserts plus revision gate for the hand-mirrored `RNNModel`;
   registry/`shared_ptr` destroy discipline mirroring the crypto bridge;
   host-suite coverage for `RnnoiseProcessor.cpp` plus JNI shims; check/log/
   clamp every `opus_ctl` plus encode failures. Gate the next native-audio
   change (with item 2) and release (destroy race plus host coverage) on these.
   Gate: configure fails on layout/rev drift; `destroy()` blocks until
   callbacks quiesce; host suite loads a real model fixture.
9. **Add gates plus pin the toolchain (P1, S).**
   CI workflow (`check.sh` + `lintFossDebug` + `assembleFossDebug`); pin shell
   Gradle/`protoc` to wrapper/catalog with a `check.sh` assert; single-source
   the CMake pin like NDK; harden signing to `findByName` plus fail-closed
   plus example template; fail fast on tagless versions. Every other guard is
   advisory without CI. Gate: green CI on `check.sh` + `lintFossDebug` +
   `assembleFossDebug`; toolchain assert present.
10. **Modernize channel navigation plus dedup plus accessibility (P1, S/M).**
    `ViewPager2` + `FragmentStateAdapter` + `MenuProvider` across channel and
    server-list fragments; one talk-state mapper, one tint helper, one volume
    helper (~60 duplicated lines deleted); label roughly a dozen icons and
    convert preview `android:text` to `tools:text`. Zero-risk lint-enforceable
    tail with an immediate assistive-technology win. Gate: zero missing
    `contentDescription` on interactive icons; preview text uses `tools:text`;
    rotation rows state pager retention. No dependency on 1–9.

## Quick-wins (small, safe, independent)

- Append the throwable to `Log.e` sound/PTT calls; promote exception
  `Log.d` to `Log.w`.
- Fix base `app_name` to the full product name; batch user-facing prose.
- Bound any genuinely unbounded test waits; prefer virtual time where the
  unit under test allows; tag socket tests.
- Retarget true `Android.mk` hyperlinks to `CMakeLists.txt`; reword prose
  mentions; banner versioned reports as historical if links are kept.
- Annotate each `catch (ignored ...)` with a reason comment; no behavior change.
- Add `check.sh` lint rejecting new `printStackTrace`, `+ e.message`
  exception drops, short product strings in user prose, and `Android.mk`
  references in current docs.
- Deduplicate the kapt-to-KSP TODO to a single source with pointers.
- Add one-line DB-ownership, SRV-helper, and unhandled-type log/count tasks
  from the core P2 list; file the six network/whisper TODOs as issues.

## What not to do yet

- No God-class rewrite: extract reconnect-scheduler and lock ownership only.
- No bulk license-header rewrite until the dual-attribution convention is
  clarified.
- No `versionCode` derivation change until tag-fetch policy for CI is settled.
- No unbounded `androidTest/` program: start with the minimal service,
  overlay, and push-to-talk smoke tier on CI emulators.
