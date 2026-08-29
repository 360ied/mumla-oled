# Agent Guidelines

## Repository Architecture
- **Monorepo Layout**:
  - `app/` (`:app`): Android application UI, activities, fragments, overlay, preferences.
  - `libraries/humla/` (`:libraries:humla`): In-tree core library with Mumble protocol engine, background service, JNI audio pipeline (`rnnoise`, Oboe/AAudio), and codec bindings.
- **Third-Party Submodules**: External native codecs and crypto are direct 1st-level submodules defined in the root `.gitmodules`:
  - `libraries/humla/src/main/jni/{opus, celt-0.11.0-src, celt-0.7.0-src, speex, rnnoise}`
  - `libraries/humla/libs/humla-spongycastle`


## Branching Strategy
- **Dedicated Branch**: Always check out or create a dedicated branch (e.g., `feature/<name>`, `bugfix/<name>`) before making changes; never develop directly on `master`.
- **Exception**: Modifications to `AGENTS.md` itself may be made directly on the current branch.
- **Merging into Master**: If the branch contains exactly one commit and `master` can fast-forward, merge with `git merge --ff-only <branch>` and no merge commit. Otherwise always use a non-fast-forward merge commit (`git merge --no-ff <branch>`); never squash, rebase, or force-push branches into `master`. The merge commit message follows the Commit Strategy rules below (subject like `chore: merge branch '<branch>'` plus the tripartite body), created via `scripts/commit.py`. See the `mumla-merge` skill (`.agents/skills/mumla-merge/SKILL.md`) for the full procedure.

## Commit Strategy
- **Atomic Commits**: Single logical unit per commit. Separate automated code generation (e.g., `protoc`) from manual edits when feasible.
- **Working State**: Every commit must leave the codebase working and passing tests (`./scripts/check.sh` or `nix develop --command ./gradlew testFossDebugUnitTest`).
- **Commit Messages & Detailed Descriptions**:
  - **Commit Wrapper**: Use `python3 scripts/commit.py -m "<message>"` to automatically format to the 50/72 rule, validate the body format (the wrapper hard-fails if the body does not use the three labeled sections below, exactly and in order), and execute `git commit`.
  - **Subject Line**: Concise and imperative with a scope prefix (e.g., `app:`, `ui:`, `humla:`, `audio:`, `proto:`, `build:`, `nix:`, `docs:`, `util:`), max 50 chars (merge commits are exempt from the 50-character limit).
  - **Detailed Body**: Always include a descriptive body separated by a blank line from the subject. Explain:
    - **Context & Motivation**: Why the change is needed and what problem it solves.
    - **Technical Approach**: Architectural decisions, algorithmic details, and notable changes across components.
    - **Edge Cases & Impact**: Handled boundary conditions, defensive checks, or protocol parity considerations.
- **Forward-Only History**: Never rewrite, rebase, squash, or force-push existing git history.

## Verification
- **Pre-Completion Check**: Run `./scripts/check.sh` before completing any task.
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
- Reference upstream C++ code and protocol schemas in `../mumble` (e.g., `../mumble/src/Mumble.proto`, `../mumble/src/MumbleUDP.proto`, connection/audio logic) to ensure exact behavioral and protocol parity.

## Versioning
- **Semantic Versioning**: Uses `0.X.X`. `versionName` is resolved dynamically via `git describe --tags --match "[0-9]*.[0-9]*.[0-9]*" --always`.
- **Release Tagging**: Tag releases using annotated Git tags: `git tag -a <version> -m "Release <version>"`.

## Licensing
- **Project License**: GNU General Public License v3.0 or later (`GPL-3.0-or-later`).
- **File Headers**: New source files must include the standard GPL-3.0-or-later header; do not use Apache, MIT, or other permissive/incompatible licenses.
