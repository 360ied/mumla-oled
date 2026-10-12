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
- **Forward-only history, no autonomous landing.** Per repository policy,
  the migration branch is merged into `master` only on explicit request,
  never autonomously.

## Open questions

- **Slice granularity** inside the big-bang branch (see [plan](plan.md)
  for the proposed order).
- **Screenshot-regression harness** choice for the Compose parity gate.

## Files

- [plan](plan.md) — scope, fences, slice order, guardrails, verification.
- [porting-guide](porting-guide.md) — the mechanical Java→Kotlin/coroutines/Room/Compose mapping converters follow literally.
