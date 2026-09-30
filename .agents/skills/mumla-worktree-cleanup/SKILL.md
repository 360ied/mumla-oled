---
name: mumla-worktree-cleanup
description: >-
  Safely clean up and teardown Git worktrees for the Mumla OLED repository
  via scripts/worktree.py cleanup: preview with --dry-run, remove only
  clean worktrees merged into master by default, opt in to unmerged or dirty
  removal with explicit flags. CRITICAL: ONLY use when the user EXPLICITLY
  asks to clean up or remove worktrees. NEVER invoke this skill autonomously
  or as part of task completion.
---

# Mumla OLED: Worktree Cleanup

This skill covers safe teardown and cleanup of Git worktrees in the Mumla OLED
repository via the non-interactive `cleanup` subcommand.

> [!CAUTION]
> **DO NOT RUN AUTONOMOUSLY.** Worktree teardown must be **explicitly requested by the user** (e.g., "clean up worktrees", "remove the worktree for branch X"). Agents must NEVER autonomously delete worktrees upon completing a feature, bugfix, or test suite. Task completion ends when commits and verification (`./scripts/check.sh`) are done inside the dedicated worktree; always leave the branch and worktree intact, report completion, and wait for review.

Worktrees allow isolated development on dedicated branches without touching
the primary repository tree (which stays permanently checked out on `master`).
Worktree removal never deletes underlying Git branches. The primary root
worktree (`master`) must **never** be removed.

## 1. Preview (always run first)

```bash
./scripts/worktree.py cleanup --dry-run
```

This inventories all secondary worktrees (typically `.worktrees/<branch-name>`)
and classifies each as merged/unmerged and clean/dirty without deleting anything.
If no secondary worktrees exist it reports that only the root worktree exists.
Include the preview output in your report to the user.

## 2. Remove (flag-driven, non-interactive)

There is no interactive prompt. Destructive scope is controlled entirely by
explicit flags (`-n` is short for `--dry-run`, `-f` for `--force`):

```bash
./scripts/worktree.py cleanup                                  # clean and merged only (default; safe w.r.t. tracked work)
./scripts/worktree.py cleanup --include-unmerged               # also remove clean worktrees with unmerged commits
./scripts/worktree.py cleanup --force                          # also remove dirty-but-merged worktrees
./scripts/worktree.py cleanup --include-unmerged --force       # remove dirty AND unmerged (requires both flags)
./scripts/worktree.py cleanup --dry-run <branch-or-path>...    # preview specific worktrees
./scripts/worktree.py cleanup <branch-or-path>...              # target specific worktrees
```

Rules:

- Default removes only **clean worktrees whose branch is fully merged** into local `master` (`git merge-base --is-ancestor <branch> master`). Safe w.r.t. tracked commits and changes; ignored build artifacts inside the worktree directory are discarded with it.
- Dirty means staged, unstaged, or untracked (`??`) changes; ignored files do not count.
- Detached-HEAD worktrees have no branch to test, so they count as unmerged: clean detached needs `--include-unmerged`, dirty detached needs both flags.
- A dirty AND unmerged worktree requires **both** `--include-unmerged` and `--force`.
- An explicitly named worktree that is skipped under the given flags exits non-zero (except in `--dry-run`, which only previews and exits zero); bulk mode preserves skipped worktrees and exits zero unless a removal itself fails.
- Locked worktrees are always preserved (unlock first). The worktree you are currently inside is never removed.
- Local branches are always preserved; delete merged ones afterwards with `git branch -d <branch-name>`.
- The command prunes worktree metadata and sweeps leftover empty directories under `.worktrees/` automatically.

## 3. Verify and report

```bash
./scripts/worktree.py list
git status
```

Report which worktrees were removed, which were intentionally preserved (and why), and note that local branches remain in history.
