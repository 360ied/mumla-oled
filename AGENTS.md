# Agent Guidelines

## Branching Strategy
- **Dedicated Branch**: Always check out or create a dedicated branch (e.g., `feature/<name>`, `bugfix/<name>`) before making changes; never develop directly on `master`.
- **Exception**: Modifications to `AGENTS.md` itself may be made directly on the current branch.

## Commit Strategy
- **Atomic Commits**: Single logical unit per commit. Separate automated code generation (e.g., `protoc`) from manual edits when feasible.
- **Working State**: Every commit must leave the codebase working and passing tests (`nix develop --command ./gradlew test`).
- **Commit Messages**: Concise and imperative with a scope prefix (e.g., `proto:`, `nix:`, `core:`, `docs:`).
- **Forward-Only History**: Never rewrite, rebase, squash, or force-push existing git history.

## Build & Deployment
- **FOSS Flavor**: Always build the `foss` product flavor (e.g., `./gradlew assembleFossDebug`). Never build `goog`, `beta`, or `donation` flavors unless requested.
- **ADB Launch**: Application ID is `se.lublin.mumla.oled15` (Java namespace is `se.lublin.mumla`). Launch with:
  ```bash
  adb shell am start -n se.lublin.mumla.oled15/se.lublin.mumla.app.MumlaActivity
  ```

## Upstream Reference (`../mumble`)
- Reference upstream C++ code and protocol schemas in `../mumble` (e.g., `../mumble/src/Mumble.proto`, `../mumble/src/MumbleUDP.proto`, connection/audio logic) to ensure exact behavioral and protocol parity.

## Versioning
- **Semantic Versioning**: Uses `0.X.X`. `versionName` is resolved dynamically via `git describe --tags --match "[0-9]*.[0-9]*.[0-9]*" --always`.
- **Release Tagging**: Tag releases using annotated Git tags: `git tag -a <version> -m "Release <version>"`.
