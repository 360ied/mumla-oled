---
name: mumla-merge
description: >-
  Merge a feature/bugfix branch into master for the Mumla OLED client:
  pre-merge verification with scripts/check.sh, non-fast-forward merge with
  a commit.py-formatted tripartite merge commit, and post-merge status
  report. Use when the user asks to merge a branch into master, land a
  branch, or integrate work into master.
---

# Mumla OLED: Merge a Branch into Master

Merges into `master` are always **non-fast-forward merge commits** created
with plain `git merge --no-ff` — never squash, never rebase, never
force-push (AGENTS.md "Forward-Only History"). The merge commit message is
formatted through `scripts/commit.py` so it follows the 50/72 rule and the
tripartite body convention.

## 1. Pre-flight

- Identify the branch: current `git branch --show-current`, or the branch
  named by the user. It must not be `master`.
- Confirm the working tree is clean: `git status --porcelain`. Refuse to
  merge with uncommitted changes (or stash only if the user asks).
- Review what is being merged: `git log master..<branch> --oneline`. If the
  log contains anything beyond what the user described, flag it before
  proceeding.

## 2. Verify the feature branch

Run the pre-completion check **on the feature branch** before merging:

```bash
./scripts/check.sh
```

If it fails, stop. The branch must pass before it lands on `master`.
(Do not merge first and "fix forward" — every commit on `master` must leave
the tree working.)

## 3. Update master

```bash
git checkout master
git fetch origin
git merge --ff-only origin/master
```

`--ff-only` guarantees master is only fast-forwarded to origin — no local
merge commits, no rebases.

## 4. Merge (no fast-forward, two-step)

Stage the merge without committing, so the message goes through the
wrapper:

```bash
git merge --no-ff --no-commit <branch>
```

If conflicts appear, resolve them, `git add` the results, and continue.
If the user wants out instead: `git merge --abort`.

Then create the merge commit via the wrapper (this runs `git commit` with
the formatted message; `MERGE_HEAD` present makes it a merge commit):

```bash
python3 scripts/commit.py -m "<subject>

Context & Motivation: <why this branch is landing on master>
Technical Approach: <summary of the branch's commits and how they fit together>
Edge Cases & Impact: <how it was verified, e.g. check.sh, unit tests, manual device test>"
```

Message rules:

- **Subject**: `chore: merge branch '<branch>'` (matches recent history,
  e.g. `chore: merge branch 'feature/remove-orbot'`). Must fit the 50-char
  limit — branch names longer than ~34 chars need a shorter phrasing, e.g.
  `chore: merge branch '<short-name>'`.
- **Body**: full tripartite format (labels exact, in that order). Because
  the subject does not start with `Merge `, `commit.py` enforces the body
  check — do not use a bare `Merge branch '...'` subject, and do not pass
  `--no-body-check`.
- Distill the branch's individual commit bodies into the merge message; do
  not paste them verbatim.

## 5. Verify and report — nothing automatic after this

- `git log -1` — confirm the merge commit has two parents and the
  tripartite body.
- `git status` — clean tree, nothing left staged.

Do **not** push or delete branches on your own. Instead, report success and
offer the commands for the user to run (or ask before running them):

```bash
git push origin master
git branch -d <branch>            # local cleanup
git push origin --delete <branch> # remote cleanup, if pushed
```

## Notes

- Merging `master` into a feature branch mid-work is allowed (plain
  `git merge --no-ff master` from the feature branch) but is not this
  skill's job — this skill is only for landing a branch on `master`.
- The AGENTS.md exception permitting direct edits on the current branch
  applies only to `AGENTS.md` itself; everything else goes through a
  dedicated branch and this merge flow.
