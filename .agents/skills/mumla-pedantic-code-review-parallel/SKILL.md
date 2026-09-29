---
name: mumla-pedantic-code-review-parallel
description: >-
  Parallelized adversarial pedantic code review for the Mumla OLED
  repository: partition changed files into slices, fan out one independent
  reviewer subagent per slice in a single batch, then adversarially assess
  and deduplicate the findings. Use whenever the user asks for a "parallel
  pedantic review", "parallelized code review", or fan-out exhaustive
  line-by-line inspection.
---

# Mumla OLED: Parallel Pedantic Code Review

Fan out independent reviewer subagents over file slices in one batch,
then conduct an adversarial critical assessment of the merged findings.

## 1. Identify Review Target

Same scope rules as the single-reviewer skill:
- **Default scope**: Everything the active worktree touches against `master` (all branch commits against `master`, staged and unstaged modifications, and any untracked files). Identify the active worktree path (e.g., `.worktrees/<branch-name>`).
- **Narrowed scope**: A specific file list, diff, or commit range explicitly requested by the user.

## 2. Enumerate and Slice (Shared Prerequisite)

Before spawning reviewers, compute the changed-file set inside the worktree:

```bash
git diff master --name-only
git status --porcelain
git diff master --stat
```

Partition into slices:

- Group co-located files (same subsystem: `app/`, `libraries/humla/`, JNI, build scripts) so caller context stays local to a slice.
- Aim for 2–6 slices of roughly equal size (~3–5 files or ~400 diff lines each). Never exceed 8 slices unless the user approves; overlapping caller inspection already multiplies I/O.
- Exclude generated/binary output (e.g., `build/`, `*.apk`); include untracked source files.
- **Small-change fallback**: if the change is ≤2 files or ≤200 diff lines, skip the fan-out and run the single-reviewer `mumla-pedantic-code-review` flow instead — parallel overhead is not worth it.

## 3. Fan Out Reviewer Subagents (One Batch)

Launch one `task` call with a single `tasks[]` batch — one `reviewer`
per slice, all slices in the same batch. Shared contract across slices:
the report format below. Each task is self-contained (slice file list,
worktree path, read-only instructions). No builds, lint, or tests
mid-flight.

Per-slice prompt (replace bracketed placeholders):

```text
Perform an exhaustive, line-by-line pedantic code review of the following
slice of <target-description> in worktree <worktree-path>. Your slice owns
these files: <slice-file-list> (full diff: `git diff master -- <slice-files>`).

Instructions:
- Your slice owns the files above, but inspect enclosing files and callers
  regardless of slice ownership rather than viewing diff hunks in isolation.
  Other reviewers cover other slices; flag what you see even if the
  counterpart lives outside your slice, and note the cross-slice reference.
- "See something, say something": if you stumble upon pre-existing defects,
  latent bugs, or hazards in surrounding code (even if not caused by the
  current changes), flag them as incidental findings.
- Check both committed changes (`git diff master -- <slice-files>`) and
  untracked/modified working tree files in your slice.
- For Mumble protocol, audio pipeline, or connection changes, verify
  behavioral parity against upstream reference code in `../mumble`
  (or `../../mumble` from within a worktree).
- This is a strictly read-only review: do not edit files, stage commits, or
  attempt fixes; your sole purpose is to identify and report issues.
- Do not fabricate issues or report false positives. If no issues exist
  within a category, explicitly state that none were identified.

Report format:
- Group findings into two tiers:
  - [DEFECT]: Functional, behavioral, or safety issues (e.g., correctness,
    race conditions, resource leaks, protocol divergence, error handling).
  - [PEDANTIC]: Craftsmanship, standards, and stylistic issues (e.g., naming
    precision, conventions, visibility, dead code, documentation,
    micro-hygiene).
- Identify each issue with clickable markdown links in
  [`<file>:<line>`](file://<absolute-path>#L<line>) format, exact line
  numbers, and a clear description of the problem (note if
  incidental/pre-existing or cross-slice).
- Send your complete report back to the caller.
```

## 4. Deduplicate, Assess & Report

Do not merely concatenate the slice reports. Overlapping caller
inspection means slices will report the same defect twice — merge them:

1. **Deduplicate**: collapse identical or overlapping findings (same
   file:line, same root cause) into one entry; record which slices
   reported it as corroboration, not as separate issues.
2. **Evaluate Findings**:
   - **Concurred**: Validate legitimate defects and pedantic issues that warrant remediation.
   - **Contested / False Positives**: Provide technical rebuttals and rationale for findings that misinterpret design intent, reflect false positives, or involve intentional trade-offs.
   - **Incidental / Pre-existing**: Note whether any valid findings were pre-existing vs. introduced by the current worktree changes.
3. **Present to User**: Deliver the merged findings (with slice
   attribution) plus your critical assessment, identifying actionable
   next steps.
