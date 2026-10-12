# Migration Plan: Full Modern Stack (Kotlin + Data + Compose)

This is the working plan for the big-bang modernization of Mumla OLED.
It records scope, fences, slice order, guardrails, and verification so
that the migration worktree (once scaffolded) cannot quietly cheat.

## Scope

Three migrations, one branch:

1. **Service core → Kotlin + coroutines/Flow.** `HumlaService` becomes a
   thin lifecycle owner; connection state, reconnect, and audio route
   policy become tested state machines (`ConnectionManager`,
   `ReconnectController`, `AudioRoutePolicy`). The `HumlaNetworkThread`
   executor trio (including its dead receive executor), the
   stringly-typed `configureExtras` bundle, and the `AsyncTask`s go away.
   The ~40-method `IHumlaSession` facade is split into role interfaces.
2. **Data layer → Room + DataStore + Hilt.** Hand SQL in
   [`MumlaSQLiteDatabase`](../../../app/src/main/java/se/lublin/mumla/db/MumlaSQLiteDatabase.java),
   the 65-method
   [`Settings`](../../../app/src/main/java/se/lublin/mumla/Settings.java)
   blob, and the pref→bundle→effect double dispatch become Room
   entities, a typed `ConfigDelta`, and an injected graph.
3. **UI → Compose, internals only.** Fragments, `RecyclerView` adapters,
   and the six-dialog `MumlaActivity` bookkeeping become Compose screens
   with `ViewModel` state holders and Navigation. See "UI parity" below.

## Fences (do not touch)

- [`audio_engine`](../../../libraries/humla/src/main/jni/audio_engine/)
  and its JNI facades: no behavior changes. The host native suite must
  stay green for every slice.
- Protocol parity with upstream Mumble schemas and behavior stays exact.
- FOSS flavor, Nix dev shell, and 3-ABI packaging stay as-is.

## UI parity (hard guardrail)

The user-facing UI must look and feel the exact same after the Compose
rewrite:

- Same `res/` visuals (theme, colors, dimens, drawables, copy), same
  navigation structure and order, same dialog titles/buttons/semantics
  (including pause-dismissal and rapid-tap behavior).
- Theme ported once into a Compose `MaterialTheme` via tokens — no
  hardcoded Compose defaults (ripples, elevations, shapes) leaking in.
- `ChannelFragmentDensityTest` and the comment-dialog tests carry over;
  a test needing a rewrite is a smell that behavior moved.

## Proposed slice order (each an atomic commit, always green)

1. minSdk decision + legacy deletion (if approved).
2. Hilt + Room + DataStore data layer (mechanical, no UX).
3. Kotlin conversion of `libraries/humla` (syntax first).
4. Coroutines service core (the real debt kill).
5. Kotlin conversion of `:app`.
6. Compose theme tokens + one leaf screen behind a screenshot test.
7. Compose screens leaf-to-root, dialogs last.

## Execution

- One dedicated worktree + branch (e.g. `migration/full-modern`) created
  via `./scripts/worktree.py add`; root repository stays on `master`.
- Feature freeze on `master` for the migration period (accepted: the app
  is considered feature-complete).
- Every commit leaves the tree working and passing `./scripts/check.sh`;
  documentation-only commits are exempt per repository policy.
- Landing into `master` is a separate, explicitly requested action —
  never autonomous. No pushing, no worktree deletion without request.

## Verification per slice

- `./scripts/check.sh` (JVM + native suites) green.
- Screenshot before/after per screen and dialog for UI slices; any pixel
  diff fails the slice.
- Manual torture paths preserved: rotate mid-dialog, switch server
  mid-connect, deny permission mid-flow — same outcomes, new internals.
