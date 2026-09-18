---
name: mumla-pedantic-code-review
description: >-
  Execute an adversarial pedantic code review of changes, diffs, branches, or
  files in the Mumla OLED repository: launch an independent reviewer subagent
  to identify issues, then have the parent agent provide a critical assessment
  of the findings. Use whenever the user asks for a "pedantic code review",
  "pedantic review", or exhaustive line-by-line inspection of code.
---

# Mumla OLED: Pedantic Code Review

Launch an independent subagent to conduct an exhaustive, line-by-line review of changes, followed by an adversarial critical assessment by the parent agent.

## 1. Identify Review Target

Identify the target directory and scope:
- **Default scope**: Everything the active worktree touches against `master` (all branch commits against `master`, staged and unstaged modifications, and any untracked files). Identify the active worktree path (e.g., `.worktrees/<branch-name>`).
- **Narrowed scope**: A specific file, diff, or commit range explicitly requested by the user.

## 2. Launch Reviewer Subagent

Launch an independent reviewer subagent (with full inspection capabilities), replacing `<target-description-and-path>` with the concrete worktree path (e.g., `.worktrees/<branch-name>`) and review scope:

```text
Perform an exhaustive, line-by-line pedantic code review of <target-description-and-path>.

Instructions:
- Inspect enclosing files and callers rather than viewing diff hunks in isolation.
- "See something, say something": if you stumble upon pre-existing defects, latent bugs, or hazards in surrounding code (even if not caused by the current changes), flag them as incidental findings.
- Check both committed changes (`git diff master`) and untracked/modified working tree files.
- For Mumble protocol, audio pipeline, or connection changes, verify behavioral parity against upstream reference code in `../mumble` (or `../../mumble` from within a worktree).
- This is a strictly read-only review: do not edit files, stage commits, or attempt fixes; your sole purpose is to identify and report issues.
- Do not fabricate issues or report false positives. If no issues exist within a category, explicitly state that none were identified.

Report format:
- Group findings into two tiers:
  - [DEFECT]: Functional, behavioral, or safety issues (e.g., correctness, race conditions, resource leaks, protocol divergence, error handling).
  - [PEDANTIC]: Craftsmanship, standards, and stylistic issues (e.g., naming precision, conventions, visibility, dead code, documentation, micro-hygiene).
- Identify each issue with clickable markdown links in [`<file>:<line>`](file://<absolute-path>#L<line>) format, exact line numbers, and a clear description of the problem (note if incidental/pre-existing).
- Send your complete report back to the caller.
```

## 3. Critical Assessment & Reporting

Do not merely relay the subagent's report. Conduct an adversarial assessment of the findings:

1. **Evaluate Findings**:
   - **Concurred**: Validate legitimate defects and pedantic issues that warrant remediation.
   - **Contested / False Positives**: Provide technical rebuttals and rationale for findings that misinterpret design intent, reflect false positives, or involve intentional trade-offs.
   - **Incidental / Pre-existing**: Note whether any valid findings were pre-existing vs. introduced by the current worktree changes.
2. **Present to User**: Deliver both the subagent's findings and your critical assessment, identifying actionable next steps.
