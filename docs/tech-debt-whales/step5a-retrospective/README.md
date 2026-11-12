# Step 5a Retrospective

Snapshot: 2026-11-12, `master` at `71f96f81` (step 4 plus two docs
commits), after the step-5a merge was reset away.

The first attempt at [step 5a](../target-architecture/migration.md#step-5a--engine-refactor-codec-network-threads)
of the [target architecture](../target-architecture/README.md) was merged,
shipped as `v0.27.4`, and pulled: all voice was dead at runtime while the
gate (`check.sh`), the murmur interop suite, and the release build all
passed. `master` was hard-reset to its pre-merge state. This directory
records why, what the investigation found about steps 1–4, and the spec
changes proposed before 5a is attempted again.

| File | Contents |
|---|---|
| [what-went-wrong.md](what-went-wrong.md) | Post-mortem of the 5a attempt: how it failed, why no automated layer caught it, and how the design drifted |
| [whats-still-fine.md](whats-still-fine.md) | Design-conformance audit of steps 1–4 against the spec, and the small gaps it found |
| [steps1-4-issues/](steps1-4-issues/README.md) | Deeper audit of steps 1–4: one file per issue (31), ready to hand off |
| [proposed-spec-edits.md](proposed-spec-edits.md) | Edits to `migration.md`, `testing.md`, `open-items.md`, and `PROGRESS.md` (applied) |

## Where the attempt lives

The reset removed the attempt from `master`, and `origin/master` was
force-pushed to match. The commits remain reachable only through the local
`bugfix/voice-load-library` (tip `db0733c4`) and
`bugfix/voice-jni-attach-ndk` branches, unless an archive tag is created
(for example `git tag archive/step5a-attempt1 db0733c4`). Key commits:

| Commit | What |
|---|---|
| `69155b90` | First 5a commit (single-threaded engines) |
| `524e5c1b` | Packet codec and voice infra tests |
| `58f3dd13` | Native voice I/O layer |
| `f2cdeea6` | Cut-over to native voice via `VoiceEngine` |
| `9dbd0ace` … `39e5ecfe` | Eight review-fix rounds |
| `2f4c09e2` | Merge of `target-arch-step5a-engine-codec-net` |
| `ad666579` | Merge of the NDK `AttachCurrentThread` build fix |

