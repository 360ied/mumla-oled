# Mumla OLED Modernization Migration

Status: landed. Slices 0-5 plus post-landing follow-ups (disconnect fix,
AGP 9 DSL migration, R8 verification) are on `master` and released as
v0.26.0 (see [status](status.md) for the progress log); slices 6-7
(Compose rewrite) remain DEFERRED to post-landing follow-up. The migration
branch and all worktrees were removed after landing.

## Goal

Kill tech debt in Mumla OLED: the god classes (`HumlaService`, `MumlaService`, `MumlaActivity` dialog/permission/reconnect bookkeeping), the
`Handler`/`volatile`/`synchronized` folk discipline, the deprecated
`AsyncTask`s, the hand-rolled SQLite and the `Settings`
`SharedPreferences` blob, and the Fragments/`RecyclerView`/adapter
machinery. See [plan](plan.md) for execution.

## Decisions so far

- **Scope is 1+2 (Compose DEFERRED 2026-10-13):** Kotlin +
  coroutines/Flow service core, Room + DataStore + Hilt data layer. The
  Compose UI rewrite (old item 3) is explicitly deferred to post-landing
  follow-up work, not part of landing. Details in [plan](plan.md).
- **Native audio is fenced off.** The C++ DSP engines under
  [`audio_engine`](../../libraries/humla/src/main/jni/audio_engine/)
  and their JNI boundary are recent, natively tested code. No rewrite-it-in-Rust (RIIR), no behavior touch. The native suite stays a
  gate for every slice. (The earlier Rust-core option is explicitly
  rejected.)
- **Crypto JNI fenced with audio.** [`crypto`](../../libraries/humla/src/main/jni/crypto/)
  (OCB2 `CryptState`, JNI bridge) gets the same treatment: C++ frozen,
  Java facades convert syntactically only.
- **Compose rewrite DEFERRED 2026-10-13 (was internals-only).** If revived
  post-landing, the user-facing UI of Mumla OLED must look and feel exactly
  the same: same `res/` visuals, same navigation structure, same dialogs
  and copy. Internals get gutted, pixels stay frozen. Parity gates are
  defined in [plan](plan.md).
- **Solo-dev big-bang.** One long-lived migration branch in a dedicated
  worktree. `master` drift is controlled by choice, and a feature freeze
  for the migration period is accepted: the app is considered
  feature-complete with echo cancellation already shipped.
- **minSdk 30 (lowered from 33 in v0.26.1).** The migration deleted every
  version gate below 33 except `UPSIDE_DOWN_CAKE`, but the v0.26.1 release
  walked the floor back to API 30 to restore Android 11–12 users —
  reintroducing a legacy SCO backend, `BundleCompat`/`IntentCompat`
  backports, and S+/Tiramisu permission guards behind version dispatch.
- **Services become `LifecycleService`.** No attachment to the current
  `Service` base; `lifecycleScope` is the service coroutine scope.
- **Dependencies: pinned in [slices](slices.md) Slice 0** (verified 2026-10-12; re-verify at scaffold). Single modern-stack `libs.versions.toml`, single Compose BOM, kapt + TODO (no stable KSP for Kotlin 2.4 at scaffold — see slices.md forks for the KSP/Hilt/Paparazzi outcomes).
- **Toolchain: AGP 9.0.1 + Gradle 9.1.0.** Adopted at the end of slice 0 (proven in the `spike/agp9` worktree) to unblock Hilt 2.60; new DSL + built-in Kotlin adopted on `build/agp9-new-dsl`, R8 verified on `build/r8-release` (8.0MB APK + modern-device pass).
- **Trial slice is the minSdk-33 deletion.** Deletion-only, zero new
  dependencies — it proves the worktree plus `check.sh` loop before
  anything irreversible. Afterwards, follow the slice order in [plan](plan.md) (data layer next).
- **DataStore flavor is Preferences.** The 46 read accessors over 41 keys (counting rule in slices.md) map per-key
  onto Preferences keys plus a typed wrapper; Proto schemas would add a
  serialization layer for no gain at this scale.
- **`Server` stays one class.** Single `@Entity @Parcelize` class serving
  as DB row, parcel, and UI model — separate layers would tax every
  call site with no foreseeable second consumer.
- **Binder surface frozen.** `IMumlaService`/`IHumlaService`/`IHumlaSession`
  stay compatible for the whole migration; refactoring happens strictly
  behind them. Improvements to the surface are post-migration work.
- **Compose Material3 — DEFERRED with slices 6-7.** The view theme
  (`Theme.Mumla`, via `Base.Theme.Mumla`, extends `Theme.Material3.DayNight.NoActionBar`), so M3 is the
  parity-correct choice; M2 would fight the screenshot gate.
- **Screenshots — DEFERRED with slices 6-7.** Paparazzi primary was planned:
  Paparazzi (JVM, no emulator) per-component regression inside the
  worktree loop; emulator/device shots cover only what Paparazzi cannot (overlay system windows, drawer gestures, rotation). On the primary path (Gradle 9.x + Paparazzi 2.x) dialogs are Paparazzi-gated; only if the 1.3.x fallback somehow works are dialogs on-device-only. Existing JVM tests
  (`ChannelFragmentDensityTest` et al.) backstop numerically. Spike proven
  2026-10-14 on `spike/paparazzi` (landed): `-PwithPaparazzi` opt-in with
  Paparazzi-only `--tests` filter; see [status](status.md).
- **Forward-only history, no autonomous landing.** Per repository policy,
  the migration branch is merged into `master` only on explicit request,
  never autonomously.

## Open questions

- **Slice granularity** inside the big-bang branch: slices 0-5 are the
  landing spine; slices 6-7 are explicitly deferred (see [status](status.md)).
  Granularity *within* a slice is decided as it proceeds.

## Files

- [plan](plan.md) — scope, fences, slice order, guardrails, verification.
- [porting-guide](porting-guide.md) — the mechanical Java→Kotlin/coroutines/Room/Compose mapping converters follow literally.
- [slices](slices.md) — the concrete per-slice work plan (inventories, pins, verification).
- [status](status.md) — progress log: what landed, fork outcomes, spike record, outstanding debt.
