# Mumla OLED Modernization Migration

Status: planning. No code yet — this directory is the scratch pad for
shaping the migration before any worktree is scaffolded.

## Goal

Kill tech debt in Mumla OLED: the god classes (`HumlaService`, `MumlaService`, `MumlaActivity` dialog/permission/reconnect bookkeeping), the
`Handler`/`volatile`/`synchronized` folk discipline, the deprecated
`AsyncTask`s, the hand-rolled SQLite and the `Settings`
`SharedPreferences` blob, and the Fragments/`RecyclerView`/adapter
machinery. See [plan](plan.md) for execution.

## Decisions so far

- **Scope is 1+2+3:** Kotlin + coroutines/Flow service core, Room +
  DataStore + Hilt data layer, Compose UI rewrite. Details in
  [plan](plan.md).
- **Native audio is fenced off.** The C++ DSP engines under
  [`audio_engine`](../../libraries/humla/src/main/jni/audio_engine/)
  and their JNI boundary are recent, natively tested code. No rewrite-it-in-Rust (RIIR), no behavior touch. The native suite stays a
  gate for every slice. (The earlier Rust-core option is explicitly
  rejected.)
- **Crypto JNI fenced with audio.** [`crypto`](../../libraries/humla/src/main/jni/crypto/)
  (OCB2 `CryptState`, JNI bridge) gets the same treatment: C++ frozen,
  Java facades convert syntactically only.
- **Compose rewrite is internals-only.** The user-facing UI of Mumla OLED
  must look and feel exactly the same: same `res/` visuals, same
  navigation structure, same dialogs and copy. Internals get gutted,
  pixels stay frozen. Parity gates are defined in [plan](plan.md).
- **Solo-dev big-bang.** One long-lived migration branch in a dedicated
  worktree. `master` drift is controlled by choice, and a feature freeze
  for the migration period is accepted: the app is considered
  feature-complete with echo cancellation already shipped.
- **minSdk 33, permanent.** The oldest fleet device is a Pixel 4a
  (Android 13 max), and the userbase is three people — so the floor
  costs nothing for this fleet and deletes every version gate except `UPSIDE_DOWN_CAKE`.
  Revisit only if the fleet changes.
- **Services become `LifecycleService`.** No attachment to the current
  `Service` base; `lifecycleScope` is the service coroutine scope.
- **Dependencies: pinned in [slices](slices.md) Slice 0** (verified 2026-10-12; re-verify at scaffold). Single `libs.versions.toml`, single Compose BOM, KSP (not kapt).
- **Trial slice is the minSdk-33 deletion.** Deletion-only, zero new
  dependencies — it proves the worktree plus `check.sh` loop before
  anything irreversible. Afterwards, follow the slice order in [plan](plan.md) (data layer next).
- **DataStore flavor is Preferences.** The 46 read accessors (counting rule in slices.md) map 1:1
  onto Preferences keys plus a typed wrapper; Proto schemas would add a
  serialization layer for no gain at this scale.
- **`Server` stays one class.** Single `@Entity @Parcelize` class serving
  as DB row, parcel, and UI model — separate layers would tax every
  call site with no foreseeable second consumer.
- **Binder surface frozen.** `IMumlaService`/`IHumlaService`/`IHumlaSession`
  stay compatible for the whole migration; refactoring happens strictly
  behind them. Improvements to the surface are post-migration work.
- **Compose Material3 (`androidx.compose.material3`).** The view theme
  (`Theme.Mumla`, via `Base.Theme.Mumla`, extends `Theme.Material3.DayNight.NoActionBar`), so M3 is the
  parity-correct choice; M2 would fight the screenshot gate.
- **Screenshots: Paparazzi primary, on-device checklist for dialogs.**
  Paparazzi (JVM, no emulator) runs per-component regression inside the
  worktree loop; emulator/device shots cover only what Paparazzi cannot (overlay system windows, drawer gestures, rotation). On the primary path (Gradle 9.x + Paparazzi 2.x) dialogs are Paparazzi-gated; only if the 1.3.x fallback somehow works are dialogs on-device-only. Existing JVM tests
  (`ChannelFragmentDensityTest` et al.) backstop numerically.
- **Forward-only history, no autonomous landing.** Per repository policy,
  the migration branch is merged into `master` only on explicit request,
  never autonomously.

## Open questions

- **Slice granularity** inside the big-bang branch: the 8-slice spine in [plan](plan.md)/[slices](slices.md) is fixed; granularity *within* a slice is decided as it proceeds.

## Files

- [plan](plan.md) — scope, fences, slice order, guardrails, verification.
- [porting-guide](porting-guide.md) — the mechanical Java→Kotlin/coroutines/Room/Compose mapping converters follow literally.
- [slices](slices.md) — the concrete per-slice work plan (inventories, pins, verification).
