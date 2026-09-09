---
name: mumla-worktree-cleanup
description: >-
  Safely clean up and teardown Git worktrees for the Mumla OLED repository:
  inventory active worktrees, inspect uncommitted changes and branch merge status,
  solicit explicit clarification via ask_question before discarding any unmerged
  or uncommitted work, execute worktree teardown via scripts/worktree.py, prune
  stale metadata, and remove leftover directories. Use when the user asks to
  clean up, remove, or prune worktrees.
---

# Mumla OLED: Worktree Cleanup

This skill guides safe teardown and cleanup of Git worktrees in the Mumla OLED
repository.

> [!CAUTION]
> **PREVENT UNINTENDED DATA LOSS.**
> Never autonomously force-remove worktrees containing uncommitted modifications,
> untracked changes, or unmerged branch commits. Always inspect the status of each
> worktree first and prompt the user for clarification before discarding work.

Worktrees allow isolated development on dedicated branches without touching
the primary repository tree (which stays permanently checked out on `master`).
Worktree removal never deletes underlying Git branches.

---

## 1. Inventory Active Worktrees

List all current worktrees from the repository root:

```bash
./scripts/worktree.py list
```

Alternatively inspect porcelain output:

```bash
git worktree list --porcelain
```

Identify all secondary worktrees (typically located in `.worktrees/<branch-name>`).
The primary root worktree (`master`) must **never** be removed.

If no secondary worktrees are present, report that only the root worktree exists
and terminate early.

---

## 2. Inspect Status & Changes

For each secondary worktree discovered:

### A. Identify Checked-Out Branch
```bash
git -C "<worktree-path>" rev-parse --abbrev-ref HEAD
```

### B. Check Merge Status against `master`
Check if the branch has already landed on `master`:
```bash
git merge-base --is-ancestor "<branch>" master
```
- If exit code is `0`, the branch is **merged**.
- If exit code is non-zero, the branch has unmerged commits. Review them:
  ```bash
  git log master..<branch> --oneline
  ```

### C. Check Working Tree Status
Inspect whether the worktree has staged, unstaged, or untracked changes:
```bash
git -C "<worktree-path>" status --porcelain
```
- Check whether diffs are in tracked project source code or submodules:
  ```bash
  git -C "<worktree-path>" diff
  ```

---

## 3. Categorize & Prompt for Clarification

Classify each secondary worktree into one of three states:
1. **Clean & Merged**: Branch is fully merged into `master`, with no uncommitted source changes.
2. **Clean & Unmerged**: Working tree is clean, but branch has commits not yet merged into `master`.
3. **Dirty / In-Progress**: Working tree contains uncommitted edits or untracked files.

### Clarification Gate

- **If ALL secondary worktrees are Clean & Merged**:
  Proceed directly to Step 4 (safe removal).

- **If ANY worktree is Unmerged or Dirty**:
  **STOP.** Do NOT run `./scripts/worktree.py remove --force` autonomously.
  Call the `ask_question` tool to solicit explicit guidance from the user:
  - Mention specific worktree branches and link modified files using Markdown links (e.g. `[AudioDeviceManager.java](file:///path/to/...)`).
  - Outline which worktrees are merged vs. unmerged.
  - Provide distinct user response options formatted from the user's perspective, such as:
    - `(Recommended) Remove only merged worktrees, preserving unmerged/in-progress worktrees`
    - `Commit changes in in-progress worktree first, then remove all worktrees`
    - `Force-remove both worktrees (discards uncommitted working tree changes)`

Wait for the user's decision before proceeding with destructive actions.

---

## 4. Teardown Worktrees

Execute removal using the project worktree manager for each confirmed worktree:

```bash
./scripts/worktree.py remove "<branch-or-path>"
```

### Handling Submodules and Force Flags
- Git prohibits removing worktrees with submodules unless `--force` is passed.
  `./scripts/worktree.py remove` internally checks `git status --porcelain` to ensure
  safety before invoking `git worktree remove --force`.
- If dirty submodules or uncommitted changes were reviewed and explicitly approved
  for deletion by the user, pass `--force` to the script:
  ```bash
  ./scripts/worktree.py remove "<branch-or-path>" --force
  ```

### Prune Metadata & Residual Directories
After removing worktrees:

1. Prune dangling worktree metadata:
   ```bash
   git worktree prune
   ```

2. Clean up leftover build artifacts or empty directories:
   Tools like Gradle may leave behind empty directory structures (e.g., `.gradle/`)
   inside `.worktrees/` that Git does not track. If `.worktrees/` only contains
   empty directories, remove them:
   ```bash
   find .worktrees -type d -empty -delete 2>/dev/null || true
   rmdir .worktrees 2>/dev/null || true
   ```
   If any non-worktree files remain, inspect them before deletion.

---

## 5. Verification & Reporting

1. Verify remaining worktrees:
   ```bash
   ./scripts/worktree.py list
   ```
   Ensure only the root `master` worktree remains active (or any worktrees intentionally preserved).

2. Confirm root working tree cleanliness:
   ```bash
   git status
   ```

3. Report status to the user:
   - Detail which worktrees were removed.
   - Note which local Git branches remain preserved in history.
   - Provide the command to delete local branches if they are merged and no longer needed:
     ```bash
     git branch -d <branch-name>
     ```
