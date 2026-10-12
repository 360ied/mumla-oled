# Mumla OLED Modernization Migration

Status: planning. No code yet — this directory is the scratch pad for
shaping the migration before any worktree is scaffolded.

## Goal

Kill tech debt in Mumla OLED: the god classes, the
`Handler`/`volatile`/`synchronized` folk discipline, the deprecated
`AsyncTask`s, the hand-rolled SQLite and the `Settings`
`SharedPreferences` blob, and the Fragments/`RecyclerView`/adapter
machinery. See [plan](plan.md) for execution.

## Decisions so far

- **Scope is 1+2+3:** Kotlin + coroutines/Flow service core, Room +
  DataStore + Hilt data layer, Compose UI rewrite. Details in
  [plan](plan.md).
- **Native audio is fenced off.** The C++ DSP engines under
  [`audio_engine`](../../../libraries/humla/src/main/jni/audio_engine/)
  and their JNI boundary are brand-new, well-tested code the author is
  satisfied with. No RIIR, no behavior touch. The native suite stays a
  gate for every slice. (The earlier Rust-core option is explicitly
  rejected.)
- **Compose rewrite is internals-only.** The user-facing UI of Mumla OLED
  must look and feel exactly the same: same `res/` visuals, same
  navigation structure, same dialogs and copy. Internals get gutted,
  pixels stay frozen. Parity gates are defined in [plan](plan.md).
- **Solo-dev big-bang.** One long-lived migration branch in a dedicated
  worktree. `master` drift is controlled by choice, and a feature freeze
  for the migration period is accepted: the app is considered
  feature-complete with echo cancellation as the cherry on top.
- **minSdk 33, permanent.** The oldest fleet device is a Pixel 4a
  (Android 13 max), and the userbase is three people — so the floor
  costs nothing and deletes every version gate except `UPSIDE_DOWN_CAKE`.
  Revisit only if the fleet changes.
- **Services become `LifecycleService`.** No attachment to the current
  `Service` base; `lifecycleScope` is the service coroutine scope.
- **Dependencies: latest stable at scaffold, pinned in a version catalog.**
  Kotlin 2.1+ (2.2 if stable), matching coroutines, Compose BOM (single
  BOM, no hand-pinned artifacts), Hilt 2.5x via KSP (not kapt), current
  Room, AndroidX Lifecycle. A new `libs.versions.toml` holds every pin so
  the next bump is a one-line diff.
- **Trial slice is the minSdk-33 deletion.** Deletion-only, zero new
  dependencies — it proves the worktree plus `check.sh` loop before
  anything irreversible. Leaf Kotlin conversion goes second.
- **DataStore flavor is Preferences.** The 65 key-value getters map 1:1
  onto Preferences keys plus a typed wrapper; Proto schemas would add a
  serialization layer for no gain at this scale.
- **`Server` stays one class.** Single `@Entity @Parcelize` class serving
  as DB row, parcel, and UI model — separate layers would tax every
  call site for a second concept that will never arrive.
- **Binder surface frozen.** `IMumlaService`/`IHumlaService`/`IHumlaSession`
  stay compatible for the whole migration; refactoring happens strictly
  behind them. Improvements to the surface are post-migration work.
- **Compose M2 (`androidx.compose.material`).** Its defaults descend from
  the same tokens as the AppCompat/Material-1.13 views being matched;
  M3 would fight the screenshot gate on every component.
- **Screenshots: Paparazzi primary, on-device checklist for dialogs.**
  Paparazzi (JVM, no emulator) runs per-component regression inside the
  worktree loop; emulator/device shots cover only what Paparazzi cannot
  (dialogs, overlay, drawer gestures, rotation). Existing JVM tests
  (`ChannelFragmentDensityTest` et al.) backstop numerically.
- **Forward-only history, no autonomous landing.** Per repository policy,
  the migration branch is merged into `master` only on explicit request,
  never autonomously.

## Open questions

- **Slice granularity** inside the big-bang branch (see [plan](plan.md)
  for the proposed order) — decided slice-by-slice as the migration
  proceeds.

## Files

- [plan](plan.md) — scope, fences, slice order, guardrails, verification.
- [porting-guide](porting-guide.md) — the mechanical Java→Kotlin/coroutines/Room/Compose mapping converters follow literally.
