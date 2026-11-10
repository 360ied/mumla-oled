# Remediation roadmap — Mumla OLED debt paydown

Consolidated Top-10 order from [01](01-app-ui.md)–[05](05-crosscutting.md)
after [validity corrections](06-validity-check.md), reordered in
[round 4](06-validity-check.md#round-4-severity-and-remediation-review): the
one P0 first, then the CI gate that would have caught it, diagnosability,
native hardening, app lifecycle, main-thread bridges, contracts, and UI
modernization. Items are independent except 4↔7 (native input guards, model
pin, and destroy lifetime move together), 6→9-settings (removing blocking
bridges unblocks settings jank), and 3→diagnosability (logging makes 7/8
debuggable); quick-wins at the end are safe to parallelize.

Do not implement from the `README.md` digest alone; the item text below plus
the linked `01`–`05` tables are the normative slices, each with its gate.

## Top-10

1. **Fix `modelHash` → `modelVersionOrDigest` (P0, XS).**
   One-line cold-build fix in `libraries/humla/build.gradle`
   (symbols `prepareRnnoiseModel` / `modelHash`). Unbreaks fresh
   clones, cache evictions, and digest rotations. Gate: cold-cache run passes —
   `rm -rf libraries/humla/build/model_cache build/test-native && ./scripts/check.sh`.
   No dependency.
2. **Add gates plus pin the toolchain (P1, S).**
   CI workflow (`check.sh` + `lintFossDebug` + `assembleFossDebug`), including
   one run from a cold model cache so item-1-class breaks fail CI; pin shell
   Gradle/`protoc` to wrapper/catalog with a `check.sh` assert; single-source
   the CMake pin like NDK; harden signing to `findByName` plus fail-closed
   plus example template; fail fast on tagless versions. Every other guard is
   advisory without CI. Gate: green CI on `check.sh` + `lintFossDebug` +
   `assembleFossDebug`; toolchain assert present. Workflow path:
   `.github/workflows/` (missing today — created by this item); assert snippet
   compares wrapper/catalog versions (Gradle `9.1.0`, protobuf `4.36.2`).
   Best landed after item 1 so the first run is green.
3. **Fail loud on network plus logging (P1, S).**
   Route TCP send `IOException` into `error()` with a drop counter; replace
   12 verified `printStackTrace` sites with tagged `Log.w/e`; append the
   throwable to `Log.e(+e.message)` sites; promote exception-in-callback
   `Log.d` to `Log.w`. Makes later network/lifecycle bugs diagnosable instead
   of silent. Gate: zero production `printStackTrace` (12 sites — 5 app
   active + 7 core; `grep -rn printStackTrace` shows only the intentional
   reporter use and no active app/core calls); send-failure counter present
   (name, location, and level stated in the fix). No dependency.
4. **Harden native input creation plus pin the RNNoise model contract (P1, S/M).**
   Input bridge: mirror the output bridge in `NativeAudioInputEngineJni.cpp` —
   `nothrow`, null checks, `ExceptionClear`, `delete ctx; return 0`,
   `GetJavaVM` check, Kotlin zero-handle guard; then daemon attach plus
   per-upcall exception check/log/clear plus detach-only-if-attached. Model
   contract: preferably remove the hand-mirrored `RNNModel` by getting
   `rnnoise_model_from_buffer` to set `file = NULL` (upstream PR, or a patch
   applied at configure time) and calling it directly; otherwise fail CMake
   configure when the RNNoise submodule revision differs from `d983458`
   (`v0.2-14-gd983458`, per processor header comment) or when the
   `struct RNNModel` text in `rnnoise/src/denoise.c` changes. Do not use
   `static_assert(sizeof/offsetof)`: the upstream struct is in a `.c` file,
   so there is nothing to compare the mirror with. Gate: input-bridge failure
   injection returns `0` with cleanup; host suite compiles the touched shims;
   configure fails on a changed RNNoise revision or struct (or the mirror is
   gone). Files: `audio_engine/NativeAudioInputEngineJni.cpp` (`nativeCreate`,
   both lambdas), `audio/NativeAudioInputEngine.kt` (constructor zero-handle
   guard), `audio_engine/RnnoiseProcessor.cpp`, `CMakeLists.txt`.
5. **Fix the certificate-generate leak plus retire `AsyncTask` (P1/P2, S/M).**
   P1: certificate-generate task retains an `Activity` across rotation —
   move to a coroutine plus `ViewModel`, `applicationContext` for DB work.
   P2 in the same slice: `ServerConnectTask` (already `applicationContext`)
   and the `ServerInfoTask` 50-thread ping pool (shut down on each rebuild,
   but fragile). Replacement pattern already in-tree. Gate: certificate
   dialog survives rotation without a retained `Activity`; zero `AsyncTask`
   references (`grep -rn AsyncTask app/src` empty); ping pool bounded (4–8)
   with `ViewModel` cache. Files: `preference/MumlaCertificateGenerateTask.kt`,
   `preference/CertificateGenerateActivity.kt`, `app/ServerConnectTask.kt`,
   `servers/ServerInfoTask.kt`, `servers/ServerAdapter.kt` (`MAX_ACTIVE_PINGS`).
6. **Move blocking bridges off the main thread — DataStore and Room (P1, M+).**
   Repository-as-source with `Flow` plus `lifecycleScope` collection for
   settings; suspend DAO calls for the database. Room `io()` runs real SQLite
   queries on main and is the larger ANR surface; DataStore reads hit its
   in-memory cache after first load and only writes wait on disk. Effort may
   exceed `M`: `SettingsRepository` `snapshotBlocking`/`updateBlocking` have
   18 call sites through the legacy `Settings` facade, and the Room KDoc ties
   its fix to the planned service-core suspend slice. Gate: zero main-thread
   `runBlocking` on the preference path
   (`grep -rn runBlocking app/src/main/java/se/lublin/mumla/preference`
   empty; `SettingsRepository.kt` helpers non-blocking) and on the Room path
   (`MumlaRoomDatabase.kt` `io()` gone or provably off-main); settings screens
   collect a flow. Only the `MumlaActivity` crash-check `runBlocking`
   (background thread) stays allowlisted. Files:
   `preference/DataStorePreferenceDataStore.kt` (`read`/`write`),
   `SettingsRepository.kt`, `Settings.kt`, `db/MumlaRoomDatabase.kt` (`io`).
   Can proceed in parallel with item 5.
7. **Native destroy lifetime, host coverage, and Opus error checks (P1, M).**
   Registry/`shared_ptr` destroy discipline mirroring the crypto bridge;
   host-suite coverage for `RnnoiseProcessor.cpp` plus JNI shims;
   check/log/clamp every `opus_ctl` plus encode failures. Gate release on
   these. Gate: `destroy()` blocks until callbacks quiesce; host suite loads
   a real fixture; every `opus_ctl` return checked. Revs: Opus `v1.6.1`
   (`22244de5` per `CMakeLists.txt` comment). Files:
   `audio_engine/NativeAudio*Jni.cpp`, `audio_engine/OpusVoiceEncoder.cpp`,
   `audio_engine/OpusVoiceDecoder.cpp`, `CMakeLists.txt`,
   `scripts/test_native_audio.sh`. Moves with item 4.
8. **Settle the foreground-service contract (P1, S).**
   The app layer already promotes (`MumlaConnectionNotification`
   `startForeground` with microphone type; manifest
   `foregroundServiceType="microphone"`), so this is a docs-plus-states item:
   add the library contract comment plus a 4-row state table
   (connected/background, connecting/background, notification-denied on API
   33+, Doze vs locks plus `START_NOT_STICKY` loss) and extend the smoke tier
   with Doze + process-death cases. Gate: table landed (rows: connected/bg,
   connecting/bg, notification-denied, Doze — each with expected behavior);
   denied-notification path stated. Files: `libraries/humla/HumlaService.kt`
   (contract KDoc), `app/.../service/MumlaConnectionNotification.kt`,
   `app/src/main/AndroidManifest.xml` (microphone type + permission).
9. **Modernize channel navigation plus dedup plus accessibility (P1, S/M).**
    `ViewPager2` + `FragmentStateAdapter` + `MenuProvider` across channel and
    server-list fragments; one talk-state mapper, one tint helper, one volume
    helper (~60 duplicated lines deleted); label roughly a dozen icons and
    convert preview `android:text` to `tools:text`. Zero-risk lint-enforceable
    tail with an immediate assistive-technology win. Gate: zero missing
    `contentDescription` on interactive icons (11 layout files hold
    `ImageView`/`ImageButton` today — `channel_row`, `channel_user_row`,
    `fragment_channel`, `fragment_chat`, `fragment_tokens`,
    `list_drawer_headerlogo`, `list_drawer_item`, `overlay_user_row`,
    `ptt_corner`, `server_list_row`, `token_row`; only the header logo and PTT
    corner are labeled); preview text uses `tools:text`;
    rotation rows state pager retention. No dependency on 1–8.
10. **Decide certificate key backup policy (P2, XS).**
    Replaces the former certificate-hardening item, which was dropped in
    round 4: `HumlaCertificateGenerator` values (serial `1`, RSA-2048,
    20-year validity) match upstream `src/SelfSignedCertificate.cpp`, Mumble
    keys users by certificate hash, and a PKCS#12 password stored next to
    the blob adds nothing. The open question is `android:allowBackup="true"`
    with no `dataExtractionRules`, which puts private keys in backups and
    device transfers. Gate: either backup is documented as an intended
    feature, or `dataExtractionRules` plus `fullBackupContent` exclude the
    database. Files: `app/src/main/AndroidManifest.xml`, `res/xml/`
    (new rules file if excluding). No dependency.

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
