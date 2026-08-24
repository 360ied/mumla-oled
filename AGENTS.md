# Agent Guidelines

## Branching Strategy

### Dedicated Branch Before Changes
- **ALWAYS** check out or create a dedicated branch (e.g., `feature/<name>` or `bugfix/<name>`) before making any code or project modifications.
- **NEVER** make development changes directly on `master`.
- **Exception**: Modifications to `AGENTS.md` itself may be made directly on the current branch without creating or switching to a new branch.

## Commit Strategy & Rules

### 1. Practice Atomic Commits
- Every commit must represent a single, logical, self-contained unit of change.
- Never mix unrelated changes (e.g. refactoring, dependency updates, and feature implementations) into a single commit.
- Keep commits granular so they can be easily reviewed, cherry-picked, or reverted if necessary.

### 2. Maintain Working Build State
- Each commit must leave the codebase in a fully building and working state.
- Run tests and verify the build passes before committing any change (`nix develop --command ./gradlew test`).

### 3. Clear & Imperative Commit Messages
- Use concise, imperative commit messages (e.g. `proto: add version_v2 to Mumble.proto` instead of `added version_v2`).
- Prefix commit messages with a clear scope tag when applicable (e.g., `nix:`, `proto:`, `core:`, `docs:`).

### 4. Separate Generated Code & Formatting
- Keep automated code generation (e.g. `protoc` outputs) in separate commits from manual code edits when feasible, or couple them directly with the schema change that triggered the generation.

### 5. Never Rewrite Git History
- Never rewrite, squash, or mutate existing git history (no rebasing, squashing existing commits, or force-pushing).
- All changes, bug fixes, and improvements must be introduced as new, forward-only atomic commits on top of the current branch.

## Build & Packaging Guidelines

### FOSS Flavor Only
- **ALWAYS** build the `foss` product flavor (e.g., `./gradlew assembleFossDebug`, `./gradlew installFossDebug`, or `./gradlew assembleFossRelease`).
- **NEVER** build or package the `goog`, `beta`, or `donation` flavors unless explicitly requested.

## ADB Deployment & Launching

### Application ID vs Package Name
- The Android application ID includes the `.oled15` suffix for all builds (`debug` and `release`):
  - **Application ID**: `se.lublin.mumla.oled15`
  - **Java Package / Namespace**: `se.lublin.mumla`
- When launching the app via ADB, **ALWAYS** target the full component name using the `.oled15` application ID:
  ```bash
  adb shell am start -n se.lublin.mumla.oled15/se.lublin.mumla.app.MumlaActivity
  ```
- **NEVER** use `se.lublin.mumla/.app.MumlaActivity`, as that targets/launches upstream Mumla rather than Mumla OLED.

## Reference Implementation & Protocol Definitions

### Upstream Mumble Codebase (`../mumble`)
- When implementing features, debugging protocol issues, or verifying correct behavior and message handling, **ALWAYS** reference the original Mumble client and server source code located in `../mumble`.
- Check official protocol definitions and schemas (e.g. `../mumble/src/Mumble.proto`, `../mumble/src/MumbleUDP.proto`) when updating or verifying Protobuf models.
- Inspect the C++ reference implementations (e.g. connection lifecycle, audio/voice packet encoding/decoding, channel listeners, permissions, and server sync) in `../mumble/src/` to ensure exact behavioral parity.

## Versioning & Release Bumping Protocol

### Semantic Versioning & Dynamic Tag Resolution
- **Version Scheme**: Mumla OLED uses Semantic Versioning (`0.X.X`).
- **Dynamic Resolution**: `versionName` in `app/build.gradle` is dynamically determined via `git describe --tags --match "[0-9]*.[0-9]*.[0-9]*" --always`.
- **Release Tagging**: Releases are tagged using annotated Git tags (`git tag -a <version> -m "Release <version>"`).
- **Tag Retention**: Only `0.X.X` release tags are kept in the repository; upstream legacy tags (`3.X.X`) must not be reintroduced.
- **Upstream Tag Isolation**: `remote.upstream.tagOpt` is set to `--no-tags` to prevent `git fetch upstream` from importing upstream Plumble/Mumla tags.
- **Release Verification**: Run `nix develop --command ./gradlew test assembleFossRelease` to verify the build before publishing tags.


