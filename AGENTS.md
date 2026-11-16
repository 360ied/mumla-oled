# Agent Guidelines

## Core Guardrails

- **Dedicated Worktrees**: Conduct all feature, bugfix, experimental, and documentation development in dedicated Git worktrees under `.worktrees/<branch>`; never develop directly on `master` or switch the root repo off `master`. (Trivial standalone doc-only edits may optionally be made on `master` when explicitly desired and are exempt from `./scripts/check.sh`.)
- **Completion Boundary**: A task is complete once changes are committed and verified inside the worktree. **Never** autonomously merge into `master`, push, or delete worktrees—leave the branch intact and report for review. Merging (`mumla-merge`), cleanup (`mumla-worktree-cleanup`), and releases (`mumla-release`) happen only on explicit user request.
- **Forward-Only History**: Never rewrite, rebase, squash, amend, or force-push commits—pushed or not. Fix mistakes with a new commit.
- **Public Repository & Sensitive Information**: Never commit secrets, credentials, API tokens, personal data, physical hardware identifiers (serials, MACs, private IPs), or local machine paths (`/home/...`, `file:///...`) to Git history or docs.
- **No `/nix/store/` Scavenging**: Never search, glob, or grep `/nix/store/` for tools or binaries. Use `nix develop --command <tool> <args>`, `nix run nixpkgs#<pkg> -- <args>`, or add packages to `flake.nix`.

## Architecture & Repository Map

- **Modules** (Java 21 / AGP 9 built-in Kotlin; `foss` product flavor only):
  - `app/` (`:app`): Android UI, activities, fragments, overlay, preferences.
  - `libraries/humla/` (`:libraries:humla`): Mumble protocol engine, background service, and native JNI pipeline under `libraries/humla/src/main/jni/` (`audio_engine/`, `crypto/`, `voice_codec/`, `voice_io/`, `nanopb/`).
- **Submodules** (registered in root `.gitmodules`): `libraries/humla/src/main/jni/{opus, rnnoise, nanopb}`.
- **Target Architecture**: Check `docs/target-architecture/` (`README.md`, `PROGRESS.md`, `enforcement.md`, `testing.md`) before modifying state, networking, routing, or voice code. Migration steps start only on explicit request, and `scripts/check_architecture.py` enforces concurrency, Android-free package, and native-layer boundaries via `scripts/architecture-allowlist.txt`.
- **Single Sources of Truth**: Dependency/plugin versions live in `gradle/libs.versions.toml`; NDK version lives in `gradle/ndk.version` (shared by Gradle and `flake.nix`).
- **Upstream Reference**: Reference upstream Mumble C++ and `.proto` files in `../mumble` (sibling of the *root* repo—from inside `.worktrees/<branch>`, use the root-relative path).
- **Skills**: Operational workflows live in `.agents/skills/` (`.claude/skills` symlink): `mumla-merge`, `mumla-release`, `mumla-worktree-cleanup`, `mumla-sensitive-redact`.

## Workflow & Commands

1. **Create worktree**: `./scripts/worktree.py add <branch-name> [base-ref]` (initializes submodules from local cache and copies RNNoise model weights).
2. **Work inside the worktree**: `cd .worktrees/<branch-name>` for all edits, codegen, builds, and tests.
3. **Verify**: Run `./scripts/check.sh` before finishing any code task.
4. **Commit**: Stage with `git add` and commit via `python3 scripts/commit.py -m "..."`. Report completion and stop.

| Task | Command | Notes |
| --- | --- | --- |
| Fast unit tests (dev loop) | `nix develop --command ./gradlew testFossDebugUnitTest` | JVM only; skips native suite |
| Pre-completion gate | `./scripts/check.sh` | Auto-enters `nix develop`; runs Python tests, `check_architecture.py`, `test_native_audio.sh`, and debug unit tests (`--full` runs `./gradlew test`) |
| Murmur interop suite | `./scripts/test_interop.sh` | Not run by `check.sh` (>1 min, Linux `mumble-server`); run for transport/session/`voice_io` network changes |
| Regenerate nanopb | `./scripts/gen_nanopb.sh` | Rebuilds `voice_codec/generated/MumbleUDP.pb.{c,h}` from `MumbleUDP.proto` + `MumbleUDP.options` |
| Debug / Release APK | `nix develop --command ./gradlew assembleFossDebug` / `assembleFossRelease` | Release output: `app/build/outputs/apk/foss/release/mumla-foss-release.apk` (uses `app/signing.gradle` if present, else debug-signed) |
| Install APK (`adb`) | `nix develop --command ./gradlew :app:installFossDebug` | App ID is `se.lublin.mumla.oled15`; Java/Kotlin namespace is `se.lublin.mumla`. Use Nix `adb` (not `~/Android/Sdk`); do not launch the app automatically |

## Commits & Versioning

- **Atomic Commits**: One logical unit per commit; keep every commit passing `./scripts/check.sh` (code) and separate generated code (`protoc`, `gen_nanopb.sh`) from manual edits when feasible.

### Commit Messages & Detailed Descriptions

- Always commit via `python3 scripts/commit.py -m "<message>"` (stage files with `git add` first).
- **Subject**: Imperative with scope prefix (`app:`, `ui:`, `humla:`, `audio:`, `voice:`, `proto:`, `build:`, `nix:`, `docs:`, `util:`), max 50 chars (merge commits exempt).
- **Body**: Blank line after subject, wrapped at 72 chars, using these three labeled paragraphs in exact order (`commit.py` enforces this):

```text
Context & Motivation: <why this change is needed>

Technical Approach: <how it is implemented>

Edge Cases & Impact: <boundary conditions, blast radius>
```

- **Versioning**: Semver tags use a leading `v` (`v0.X.X`, e.g. `v0.24.0`), created as annotated tags (`git tag -a <version> -m "Release <version>"`); Gradle strips the `v` for `versionName`/`VERSIONTAG`.

## Code, Documentation & Licensing Standards

- **App Naming**: Always call the application **Mumla OLED**, never "Mumla" in isolation.
- **Markdown (GFM)**: Target GitHub Flavored Markdown (`markdownlint` and `cmark-gfm` are in `flake.nix`). Use portable relative links for repo files and canonical web URLs for upstream references—never `file:///` or local machine paths. Isolate ```` ```math ```` blocks with blank lines (never `$$ ... $$`, never inside lists), and write inline math as `$x$` without internal padding.
- **Licensing (`GPL-3.0-or-later`)**: New source files must include the standard GPL-3.0-or-later header with `Copyright (C) <current year> Brian Zhu` (copy from a recent file in the same component) and preserve existing third-party/upstream attributions. No Apache/MIT/permissive headers.
