# Agent Guidelines

## Repository Architecture
- **Monorepo Layout**:
  - `app/` (`:app`): Android application UI, activities, fragments, overlay, preferences.
  - `libraries/humla/` (`:libraries:humla`): In-tree core library with Mumble protocol engine, background service, JNI audio pipeline (`rnnoise`, Oboe/AAudio), and codec bindings.
- **Third-Party Submodules**: External native codecs and processing libraries are submodules registered directly in the root `.gitmodules` (not nested inside another submodule):
  - `libraries/humla/src/main/jni/{opus, rnnoise}`


## Branching & Worktree Strategy
- **Mandatory Worktrees**: All feature, bugfix, and experimental development MUST be conducted in dedicated Git worktrees; never develop directly on `master`, and avoid switching branches in the root repository. The root repository remains permanently checked out on `master`.
- **Worktree Creation**: Create dedicated worktrees via `./scripts/worktree.py add <branch-name> [base-ref]`. This automatically sets up the working directory under `.worktrees/<branch-name>`, initializes all native Git submodules (`opus`, `rnnoise`) from the local cache, and copies over any existing pre-trained RNNoise model weights from the root repository.
- **Development & Verification**: Perform all code modifications, Gradle builds, and pre-completion verification (`./scripts/check.sh`) inside the dedicated worktree directory (from the repository root: `cd .worktrees/<branch-name>`).
- **Task Completion Boundary (NO AUTONOMOUS MERGING, PUSHING, OR DELETION)**: For code tasks, an agent's task is COMPLETE once changes are committed and verified (`./scripts/check.sh`) inside the dedicated worktree (for standalone documentation tasks on `master`, once committed via `scripts/commit.py` without pushing). Agents must **NEVER autonomously merge into `master`, push, or delete worktrees** upon completing a task. Always leave the branch and worktree intact and unpushed, report completion to the user, and wait for review.
- **Merging into Master (Explicit User Request Only)**: Merging a branch into `master` is a separate, user-initiated action that must be **explicitly requested by the user** (e.g., "merge into master", "land this branch"). Agents must never merge autonomously. When explicitly requested, follow the `mumla-merge` skill (`.agents/skills/mumla-merge/SKILL.md`).
- **Documentation Exception**: Standalone documentation changes (such as editing `AGENTS.md`, `README.md`, standalone documentation files under `docs/`, or adding or updating agent skills under `.agents/skills/`) do not need the worktree process and should be made directly on `master`. They are exempt from `./scripts/check.sh` verification. Commit them via `scripts/commit.py`; do not push — report for review. Documentation that directly accompanies active feature or bugfix code development should remain in the corresponding feature worktree.

## Commit Strategy
- **Atomic Commits**: Single logical unit per commit. Separate automated code generation (e.g., `protoc`) from manual edits when feasible.
- **Working State**: Every code commit must leave the codebase working and passing `./scripts/check.sh` (the gate). Standalone documentation commits on `master` are exempt from this gate. `nix develop --command ./gradlew testFossDebugUnitTest` runs the fast unit-test subset during development.
- **Commit Messages & Detailed Descriptions**:
  - **Commit Wrapper**: Use `python3 scripts/commit.py -m "<message>"` to automatically format to the 50/72 rule, validate the body format (the wrapper hard-fails if the body does not use the three labeled sections below, exactly and in order), and execute `git commit`. Stage changes with `git add` first — the wrapper runs plain `git commit` and only records what's staged.
  - **Subject Line**: Concise and imperative with a scope prefix (e.g., `app:`, `ui:`, `humla:`, `audio:`, `proto:`, `build:`, `nix:`, `docs:`, `util:`), max 50 chars (merge commits are exempt from the 50-character limit).
  - **Detailed Body**: Always include a descriptive body separated by a blank line from the subject. Separate the three sections from each other with blank lines so each `Label:` starts its own paragraph:
    ```
    Context & Motivation: <why this change is needed>

    Technical Approach: <how it is implemented>

    Edge Cases & Impact: <boundary conditions, blast radius>
    ```
    Explain:
    - **Context & Motivation**: Why the change is needed and what problem it solves.
    - **Technical Approach**: Architectural decisions, algorithmic details, and notable changes across components.
    - **Edge Cases & Impact**: Handled boundary conditions, defensive checks, or protocol parity considerations.
- **Public Repository & Sensitive Information**: This is a publicly accessible repository. Never commit sensitive information, secrets, credentials, API tokens, personal data, or physical hardware identifiers (such as device serial numbers, MAC addresses, or private IPs) to Git history or documentation.
- **Forward-Only History**: Never rewrite, rebase, squash, amend, or force-push commits — pushed or not. Fix mistakes with a new commit.

## Verification
- **Pre-Completion Check**: Run `./scripts/check.sh` inside the dedicated worktree before completing any code task. Passing verification signifies that the branch is ready for user review—it does NOT trigger or authorize merging into `master`. Standalone documentation-only changes (committed on `master`) are exempt from `./scripts/check.sh`.
- **Fast Unit Tests**: FOSS debug unit tests via `nix develop --command ./gradlew testFossDebugUnitTest`.
- **Full Test Suite** (when required): `nix develop --command ./gradlew test`.

## Build & Deployment
- **FOSS Flavor**: The project is configured exclusively for the `foss` product flavor. Build with the Nix dev shell:
  - Debug APK: `nix develop --command ./gradlew assembleFossDebug`
  - Release APK: `nix develop --command ./gradlew assembleFossRelease` (output: `app/build/outputs/apk/foss/release/mumla-foss-release.apk`)
- **ADB Launch**: Application ID is `se.lublin.mumla.oled15` (Java namespace is `se.lublin.mumla`). Launch with:
  ```bash
  adb shell am start -n se.lublin.mumla.oled15/se.lublin.mumla.app.MumlaActivity
  ```

## Nix Environment
- **No `/nix/store/` Scavenging**: Never search, glob, or grep through `/nix/store/` to locate tools or binaries (e.g. hunting for `gradle`, `sdkmanager`, or a JDK path). The store contains millions of paths and such searches waste enormous time.
- **Correct Alternatives**:
  - Run tools inside the dev shell: `nix develop --command <tool> <args>`.
  - If a tool is missing from the dev shell, use `nix run nixpkgs#<package> -- <args>` as a stopgap, or (preferred) add it to the dev shell `packages` in `flake.nix`.

## Upstream Reference (`../mumble`)
- Reference upstream C++ code and protocol schemas in `../mumble` (sibling of the repository root — from inside a worktree, bare `../mumble` resolves elsewhere; e.g., `../mumble/src/Mumble.proto`, `../mumble/src/MumbleUDP.proto`, connection/audio logic) to ensure exact behavioral and protocol parity.

## Versioning
- **Semantic Versioning**: Uses `0.X.X`. `versionName` is resolved dynamically via `git describe --tags --match "[0-9]*.[0-9]*.[0-9]*" --always`.
- **Release Tagging**: Tag releases using annotated Git tags: `git tag -a <version> -m "Release <version>"`.

## Documentation & Markdown Standards
- **GitHub Flavored Markdown (GFM)**: All documentation (`docs/`, `README.md`, skills, guidelines) must target GFM as rendered by GitHub's web interface (KaTeX math engine).
- **Math Block Delimiters**: Use GitHub's fenced ```` ```math ```` code block syntax for display equations; avoid ambiguous `$$ ... $$` delimiters.
- **Blank Line Isolation**: Always isolate math blocks with blank lines before and after. Never place math blocks directly adjacent to text paragraphs or within lists without blank line separation (CommonMark paragraph rules will fold them into inline text, unescaping LaTeX `\\` newlines into `\` and corrupting KaTeX parsing).
- **Inline Math**: Use `$ ... $` with no internal padding whitespace (e.g., `$x$` rather than `$ x $`).
- **List Compatibility**: Use inline math expressions inside bullet points or numbered lists, or place math blocks at the top level between list items to prevent list enumeration splitting.

## Licensing
- **Project License**: GNU General Public License v3.0 or later (`GPL-3.0-or-later`).
- **File Headers**: New source files must include the standard GPL-3.0-or-later header with `Copyright (C) <current year> Brian Zhu` — copy the full block from a recent file in the same component and keep existing third-party attributions (e.g., upstream Mumble authors) intact; do not use Apache, MIT, or other permissive/incompatible licenses.

