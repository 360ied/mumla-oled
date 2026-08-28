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

## Commit Strategy
- **Atomic Commits**: Single logical unit per commit. Separate automated code generation (e.g., `protoc`) from manual edits when feasible.
- **Working State**: Every commit must leave the codebase working and passing tests (`./scripts/check.sh` or `nix develop --command ./gradlew testFossDebugUnitTest`).
- **Commit Messages & Detailed Descriptions**:
  - **Commit Wrapper**: Use `python3 scripts/commit.py -m "<message>"` to automatically format to the 50/72 rule, validate, and execute `git commit`.
  - **Subject Line**: Concise and imperative with a scope prefix (e.g., `app:`, `ui:`, `humla:`, `audio:`, `proto:`, `build:`, `nix:`, `docs:`, `util:`), max 50 chars.
  - **Detailed Body**: Always include a descriptive body separated by a blank line from the subject. Explain:
    - **Context & Motivation**: Why the change is needed and what problem it solves.
    - **Technical Approach**: Architectural decisions, algorithmic details, and notable changes across components.
    - **Edge Cases & Impact**: Handled boundary conditions, defensive checks, or protocol parity considerations.
- **Forward-Only History**: Never rewrite, rebase, squash, or force-push existing git history.

## Build & Deployment
- **FOSS Flavor**: The project is configured exclusively for the `foss` product flavor (e.g., `./gradlew assembleFossDebug`).
- **ADB Launch**: Application ID is `se.lublin.mumla.oled15` (Java namespace is `se.lublin.mumla`). Launch with:
  ```bash
  adb shell am start -n se.lublin.mumla.oled15/se.lublin.mumla.app.MumlaActivity
  ```

## Upstream Reference (`../mumble`)
- Reference upstream C++ code and protocol schemas in `../mumble` (e.g., `../mumble/src/Mumble.proto`, `../mumble/src/MumbleUDP.proto`, connection/audio logic) to ensure exact behavioral and protocol parity.

## Versioning
- **Semantic Versioning**: Uses `0.X.X`. `versionName` is resolved dynamically via `git describe --tags --match "[0-9]*.[0-9]*.[0-9]*" --always`.
- **Release Tagging**: Tag releases using annotated Git tags: `git tag -a <version> -m "Release <version>"`.

## Licensing
- **Project License**: GNU General Public License v3.0 or later (`GPL-3.0-or-later`).
- **File Headers**: New source files must include the standard GPL-3.0-or-later header; do not use Apache, MIT, or other permissive/incompatible licenses.
