# Step 5a Retrospective

Snapshot: 2026-11-12, `master` at `71f96f81` (step 4 plus two docs
commits), after the step-5a merge was reset away. The spec edits proposed
here were applied in `ec9faf63` and `4e9474c7`; issue statuses and the
remaining-work queue were refreshed in `0746f5e4`.

The first attempt at [step 5a](../../migration.md#step-5a--engine-refactor-codec-network-threads)
of the [target architecture](../../README.md) was merged,
shipped as `v0.27.4`, and pulled: all voice was dead at runtime while the
gate (`check.sh`), the murmur interop suite, and the release build all
passed. `master` was hard-reset to its pre-merge state. This directory
records why and the spec changes proposed before 5a is attempted again.
The deeper audit of steps 1–4 that the investigation started now lives on
as the live [issue queue](../../issues/README.md).

| File | Contents |
|---|---|
| [what-went-wrong.md](what-went-wrong.md) | Post-mortem of the 5a attempt: how it failed, why no automated layer caught it, and how the design drifted |
| [whats-still-fine.md](whats-still-fine.md) | Design-conformance audit of steps 1–4 against the spec, and the small gaps it found |
| [../../issues/](../../issues/README.md) | Moved out: the deeper steps 1–4 audit, now the live issue queue |
| [proposed-spec-edits.md](proposed-spec-edits.md) | Edits to `migration.md`, `testing.md`, `open-items.md`, and `PROGRESS.md` (applied) |

## Do not reuse the attempt

The reset removed the attempt from `master`, and `origin/master` was
force-pushed to match. **Its code is not a reference for the redo and must
not be reused.** Copying from it is how the drift this directory describes
comes back: the attempt passed `check.sh`, the interop suite, and the
release build while shipping no working voice, so passing those gates says
nothing about whether its code is sound.

The spec plus fixtures generated from the Java-generated protobuf classes
is the source of truth for steps 5a.2 onward; port from those, not from a
recovered tree. No branch name, tag recipe, or commit list is kept here on
purpose, so this directory cannot be used to look the attempt up.

