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
   The 48-method `IHumlaSession` facade stays frozen throughout (a role-interface split is post-migration follow-up work).
2. **Data layer → Room + DataStore + Hilt.** Hand SQL in
   [`MumlaSQLiteDatabase`](../../app/src/main/java/se/lublin/mumla/db/MumlaSQLiteDatabase.java),
   the 48-accessor
   [`Settings`](../../app/src/main/java/se/lublin/mumla/Settings.java)
   blob, and the pref→bundle→effect double dispatch become Room
   entities, a typed `ConfigDelta`, and an injected graph.
3. **UI → Compose, internals only.** Fragments, `RecyclerView` adapters,
   and the seven dialog fields of `MumlaActivity` become Compose screens
   with `ViewModel` state holders and Navigation. See "UI parity" below.

## Fences (do not touch)

- [`audio_engine`](../../libraries/humla/src/main/jni/audio_engine/)
  and its JNI facades: no behavior changes. The host native suite must
  stay green for every slice.
- [`crypto`](../../libraries/humla/src/main/jni/crypto/) (OCB2 `CryptState`, JNI bridge):
  same treatment — C++ frozen, Java facades convert syntactically only.
- Protocol parity with upstream Mumble schemas and behavior stays exact.
- FOSS flavor, Nix dev shell, and 3-ABI packaging stay as-is.
- Binder surface frozen: `IMumlaService`/`IHumlaService`/`IHumlaSession`
  stay compatible for the whole migration; refactoring happens strictly
  behind them.

## UI parity (hard guardrail)

The user-facing UI must look and feel the exact same after the Compose
rewrite:

- Same `res/` visuals (theme, colors, dimens, drawables, copy), same
  navigation structure and order, same dialog titles/buttons/semantics
  (including pause-dismissal and rapid-tap behavior).
- Theme ported once into a Compose **Material3** `MaterialTheme`
  (the view theme `Theme.Mumla`, via `Base.Theme.Mumla`, extends `Theme.Material3.DayNight.NoActionBar`) via tokens — no hardcoded Compose defaults (ripples, elevations, shapes) leaking in.
- Screenshot regression runs **Paparazzi** per component in the worktree loop;
  emulator/device shots cover only dialogs, overlay, drawer gestures, and rotation.
- `ChannelFragmentDensityTest` and the comment-dialog tests carry over;
  a test needing a rewrite is a smell that behavior moved.

## Proposed slice order (each an atomic commit, always green)

Concrete per-slice plan (file lists, pins, verification) lives in
[slices](slices.md). Summary:

1. minSdk 33 + legacy deletion (decided: Pixel 4a floor — deletes
   M/N/O/Q/R/S/S_V2/TIRAMISU gates including the SCO legacy path and the `ChannelChatFragment` storage branch).
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

## Agentic execution tactics (learned from Bun's Zig→Rust rewrite)

Bun's 11-day, ~535K-line agentic rewrite (see References) is the
closest public precedent for this migration. Its tactics apply directly;
adopt them from slice 1:

- **Porting guide first.** Before converting, write down the mechanical
  mapping (Java→Kotlin idioms, `Handler`→coroutine, prefs→DataStore,
  Views→Compose) as a doc in this directory and review it like code.
  Bun's `PORTING.md` plus a lifetimes table were reviewed before a
  single file was converted.
- **Trial slice is slice 1.** The minSdk-33 deletion is deletion-only with zero new dependencies: run implement + review + fix on it before fanning out. Calibrate the workflow on the trial,
  not on the whole tree.
- **Split implementer and reviewer contexts.** The agent that writes a
  slice must not be the one that reviews it; reviewers get the diff only
  and assume it is wrong. Review attribution goes in the commit message.
- **Fix the process, not the code.** When a class of defect recurs
  (parity drift, missed dismissal path), edit the porting guide or the
  slice checklist — not just the offending file.
- **Compiler and tests as work queues.** Group failures by slice/package
  and work them off in batches; `./scripts/check.sh` plus the screenshot
  gates are the merge oracle for every slice.
- **Workflow hygiene.** Agents in the migration worktree commit single
  files/slices only; no `git stash`, `git reset --hard`, or slow
  repo-wide commands inside the loop. Shard work across a bounded number
  of worktree checkouts; disk space caps the shard count.
- **Big-bang, not strangler, inside the branch.** Temporary compat shims
  that "will be deleted later" are the main way incremental migrations
  rot; prefer converting each slice completely.

## References

- Jarred Sumner, "Rewriting Bun in Rust" (Bun blog) — the primary
  source for the tactics above: mechanical port, prep docs, trial run,
  adversarial review, compiler-errors-as-work-queue.
  <https://bun.com/blog/bun-in-rust>
- oven-sh/bun `docs/rust-rewrite-plan.md` — strangler-fig plan and
  memory-safety motivation.
  <https://github.com/oven-sh/bun/blob/master/docs/rust-rewrite-plan.md>

## Verification per slice

- `./scripts/check.sh` (JVM + native suites) green.
- Screenshot before/after per screen and dialog for UI slices; any pixel
  diff fails the slice.
- Manual torture paths preserved: rotate mid-dialog, switch server
  mid-connect, deny permission mid-flow — same outcomes, new internals.
