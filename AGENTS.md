# Agent Guidelines

## Core Guardrails

- **Dedicated Worktrees**: Conduct all feature, bugfix, experimental,
  and standalone documentation development in dedicated Git worktrees under
  `.worktrees/<branch>`; never develop directly on `master` or switch
  the root repository off `master`. (Trivial standalone doc-only
  edits may optionally be made on `master` when explicitly requested
  by the user.)
- **Completion Boundary**: A task is complete once changes are
  committed inside the worktree (for code tasks, after passing
  `./scripts/check.sh` following the final edit). **Never**
  autonomously merge into `master`, push, or delete worktrees—leave
  the branch intact and report for review. Merging (`mumla-merge`),
  cleanup (`mumla-worktree-cleanup`), and releases (`mumla-release`)
  happen only on explicit user request.
- **Forward-Only History**: Never rewrite, rebase, squash, amend, or
  force-push commits—pushed or not. Fix mistakes with a new commit.
- **Public Repository & Sensitive Information**: This is a publicly
  accessible repository. Never commit secrets, credentials, API
  tokens, personal data, physical hardware identifiers (serials, MACs,
  private IPs), or local machine paths (`/home/...`, `file:///...`)
  to Git history or documentation.
- **No `/nix/store/` Scavenging**: Never search, glob, or grep
  `/nix/store/` for tools or binaries. Use
  `nix develop --command <tool> <args>`,
  `nix run nixpkgs#<pkg> -- <args>` as a stopgap, or add tools to
  `flake.nix`.

## Architecture & Repository Map

- **Modules** (Java 21 / AGP 9 built-in Kotlin; `foss` flavor only):
  - `app/` (`:app`): Android UI, activities, fragments, overlay,
    preferences.
  - `libraries/humla/` (`:libraries:humla`): Mumble protocol engine,
    background service, codec bindings, and native JNI audio pipeline
    over AAudio callback streams (no Oboe), organized under
    `libraries/humla/src/main/jni/` (`audio_engine/`, `crypto/`,
    `voice_codec/`, `voice_io/`, `nanopb/`). Android links `aaudio`; the
    host library and host tests compile the callback bodies only.
- **Third-Party Submodules**: Registered directly in the root
  `.gitmodules` (not nested inside another submodule):
  `libraries/humla/src/main/jni/{opus, rnnoise, nanopb}`.
- **Target Architecture**: Check `docs/target-architecture/`
  (`README.md`, `PROGRESS.md`, `enforcement.md`, `testing.md`) before
  modifying state, networking, routing, or voice code. Migration steps
  start only on explicit request, and `scripts/check_architecture.py`
  enforces concurrency, Android-free package, native-layer, and
  `voice_io` `RenderGuard` checks;
  `scripts/architecture-allowlist.txt` only ratchets downward (lower a
  file's count in the same commit when removing matched lines, or
  remove the entry if it reaches `0`).
- **Single Sources of Truth**: Dependency/plugin versions live in
  `gradle/libs.versions.toml`; NDK version lives in
  `gradle/ndk.version` (shared by Gradle and `flake.nix`); native ABIs
  live in `sharedAbiFilters` in root `build.gradle`.
- **Native Build Wiring**: `libraries/humla/build.gradle` wires the
  3-ABI NDK build via `externalNativeBuild.cmake` (`buildCMake*`) and
  runs the host native suite only via `check`/explicit script
  invocation so unit-test tasks stay fast. After an AGP upgrade,
  re-validate with `--dry-run` that unit-test tasks exclude
  `buildCMake*`/`testNativeAudioEngine` while `assembleFossDebug`
  includes `buildCMake*`.
- **Upstream Reference (`../mumble`)**: Reference upstream C++ code
  and protocol schemas in `../mumble` relative to the *root*
  repository (e.g., `../mumble/src/Mumble.proto`,
  `../mumble/src/MumbleUDP.proto`, connection/audio logic) for exact
  behavioral and protocol parity. From inside `.worktrees/<branch>`,
  adjust `../` depth (or resolve from the root repo), since bare
  `../mumble` resolves inside `.worktrees/`.
- **Skills**: Operational workflows live in `.agents/skills/`
  (`.claude/skills` symlink): `mumla-merge`, `mumla-release`,
  `mumla-worktree-cleanup`, `mumla-sensitive-redact`.

## Workflow & Commands

1. **Create worktree**:
   `./scripts/worktree.py add <branch> [base-ref]` (creates
   `.worktrees/<branch>`, initializes submodules from the local cache,
   and copies existing RNNoise model weights).
2. **Work inside the worktree**: `cd .worktrees/<branch>` for all
   edits, codegen, builds, verification, and commits.
3. **Verify**: Run `./scripts/check.sh` before finishing any code
   task. Standalone doc-only edits (`AGENTS.md`, `README.md`, `docs/`,
   `.agents/skills/`) are exempt from `./scripts/check.sh` (verify
   with `markdownlint` / `cmark-gfm` when appropriate); docs
   accompanying active code work remain in the feature worktree and
   go through `./scripts/check.sh`.
4. **Commit**: Stage changes with `git add` and commit via
   `python3 scripts/commit.py -m "..."`. Report completion and stop.

### Common Commands

- **Fast unit tests (dev loop)**:
  `nix develop --command ./gradlew testFossDebugUnitTest` (`:app`);
  append `:libraries:humla:testDebugUnitTest` for `humla` (which has
  no `foss` flavor). JVM only; skips native suite. `CryptState` tests
  in `humla` load `libhumlaaudio` from `build/test-native`, so run
  `test_native_audio.sh` or `check.sh` first on a clean tree (or after
  C++ edits).
- **Host native C++ suite**:
  `nix develop --command ./scripts/test_native_audio.sh`
  Fast loop for `jni/` changes (`test_audio_engine`,
  `test_crypt_state`, `test_voice_io`, `test_voice_codec`); also runs
  via `./gradlew check` and `check.sh`.
- **Pre-completion gate**: `./scripts/check.sh`
  Auto-enters `nix develop`; runs Python tests,
  `check_architecture.py`, `test_native_audio.sh`, and debug unit
  tests (`--full` runs `./gradlew test`).
- **Murmur interop suite**: `./scripts/test_interop.sh`
  Not run by `check.sh` (>1 min, Linux `mumble-server`); run for
  transport/session/`voice_io` network changes.
- **Regenerate nanopb**:
  `nix develop --command ./scripts/gen_nanopb.sh`
  Rebuilds `voice_codec/generated/MumbleUDP.pb.{c,h}` from
  `MumbleUDP.proto` + `MumbleUDP.options`. Requires `nix develop`.
- **Debug / Release APK**:
  `nix develop --command ./gradlew assembleFossDebug` (or
  `assembleFossRelease`). Release output is
  `app/build/outputs/apk/foss/release/mumla-foss-release.apk`;
  requires local uncommitted `app/signing.gradle`
  (`signingConfigs.release`), otherwise the release build fails
  (debug builds are unaffected).
- **Device install & `adb`**:
  `nix develop --command ./gradlew :app:installFossDebug`
  Install on device only when requested; never launch the app
  automatically (no `adb shell am start` unless asked). App ID is
  `se.lublin.mumla.oled15`; Java/Kotlin namespace is
  `se.lublin.mumla`. Use Nix `adb` (for `:app:installFossDebug` or
  `adb` commands like `logcat`), not `~/Android/Sdk` (which cannot run
  directly on NixOS).

## Commits & Versioning

- **Atomic Commits**: Single logical unit per commit; every code
  commit must leave the tree passing `./scripts/check.sh`. Separate
  automated code generation (`protoc`, `gen_nanopb.sh`) from manual
  edits when feasible.

### Commit Messages & Detailed Descriptions

- **Commit Wrapper**: Stage changes with `git add` first, then run
  `python3 scripts/commit.py -m "<message>"`. The wrapper wraps body
  lines to 72 chars automatically, hard-fails on >50-char subjects or
  malformed body labels, and commits only staged changes.
- **Subject Line**: Concise and imperative with a scope prefix
  (`app:`, `ui:`, `humla:`, `audio:`, `voice:`, `proto:`, `build:`,
  `nix:`, `docs:`, `util:`), max 50 chars (`chore: merge branch ...`
  is exempt from the 50-char limit, not the body check).
- **Detailed Body**: Separate the body from the subject with a blank
  line and separate the three sections from each other with blank
  lines so each `Label:` starts its own paragraph as plain text (no
  Markdown bullets or headings):

```text
Context & Motivation: <why this change is needed>

Technical Approach: <how it is implemented, key decisions>

Edge Cases & Impact: <boundary conditions, blast radius, parity>
```

- **Versioning**: Semver tags use a leading `v` (`v0.X.X`, e.g.,
  `v0.24.0`), created as annotated tags
  (`git tag -a <version> -m "Release <version>"`); Gradle strips the
  `v` so `versionName`/`VERSIONTAG` stay bare semver.

## Code, Documentation & Licensing Standards

- **Application Naming**: In documentation and user-facing text,
  always call the application **Mumla OLED**, never "Mumla" in
  isolation (it is a hard fork of Mumla not yet comprehensively
  rebranded). Do not rename existing `se.lublin.mumla` package or
  `Mumla*` code symbols unless requested.
- **Markdown (GFM)**: Target GitHub Flavored Markdown (`markdownlint`
  and `cmark-gfm` are in `flake.nix`).
  - **Portable Links**: Use relative Markdown links for in-tree files
    (e.g., `../../libraries/humla/...`) and canonical web URLs for
    upstream references (e.g.,
    `https://github.com/mumble-voip/mumble/blob/master/...`)—never
    `file:///` URIs or local machine paths. Never confuse
    conversational assistant link formatting (`file:///...`) with
    version-controlled documentation.
  - **Math**: Use blank-line-isolated ```` ```math ```` blocks (not
    `$$ ... $$`; indent inside list items so numbering isn't split)
    and `$x$` without internal padding for inline math.
- **Licensing (`GPL-3.0-or-later`)**: New source files must include
  the standard GPL-3.0-or-later header with
  `Copyright (C) <current year> Brian Zhu` (copy the full block from a
  recent file in the same component) and preserve existing
  third-party/upstream attributions. Do not use Apache, MIT, or other
  permissive/incompatible licenses.
