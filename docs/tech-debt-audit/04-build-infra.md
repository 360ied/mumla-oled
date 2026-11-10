# Build/infra debt — Mumla OLED

Scope: `settings.gradle`, root `build.gradle`, `gradle.properties`,
`gradle/libs.versions.toml`, `gradle/ndk.version`,
`gradle/wrapper/gradle-wrapper.properties`, `flake.nix`, `flake.lock`,
`scripts/check.sh`, `scripts/test_native_audio.sh`, `scripts/worktree.py`,
`scripts/commit.py`, `app/build.gradle`, `libraries/humla/build.gradle`,
`.gitignore`, `.gitmodules`. No `.github/workflows` directory exists (only
issue/PR templates). AGP/Gradle themselves are current (AGP 9.0.1 + Gradle
9.1.0); the lag cluster sits around them.

## P0

| Location | Issue | Effort | Remediation |
|---|---|---|---|
| [`libraries/humla/build.gradle`](../../libraries/humla/build.gradle) (`prepareRnnoiseModel`) | `tarGz` is keyed by `modelVersionOrDigest`, but the download branch interpolates `${modelHash}` in both the log line and tarball URL. `modelHash` is never declared. Groovy resolves at execution time, so any cold download path throws `MissingPropertyException`. Warm checkouts skip via `if (!tarGz.exists())`, hiding the defect. | XS | Replace both `${modelHash}` uses with `${modelVersionOrDigest}`. |

## P1

| Location | Issue | Effort | Remediation |
|---|---|---|---|
| `.github/` (missing `workflows/`) | Templates only, zero workflows. Nothing enforces `scripts/check.sh`, lint, or debug packaging on PRs; all Gradle guards below help only developers who run them. | S | Add a minimal workflow running `scripts/check.sh` plus `lintFossDebug` plus `assembleFossDebug`. Gate release packaging only where signing is available. |
| CMake version tri-sync (`humla/build.gradle`, `flake.nix`, `test_native_audio.sh`) | Version `4.1.2` is manually tri-synced by `Keep in sync` comments. NDK is already single-sourced (`gradle/ndk.version`); CMake is not. A bump in one place silently splits NDK packaging from the host suite and dev-shell `PATH`. | S | Single-source it like NDK (e.g. `gradle/cmake.version` read by `flake.nix` plus `humla/build.gradle`, with the shell script deriving from the same file). |
| Floating Nix toolchain vs pinned wrapper | Wrapper pins `gradle-9.1.0`; `flake.nix` injects unbounded `gradle`/`protobuf`/`ccache`/`jdk21` from `nixos-unstable` (lock file pins today but floats on update). Host `protoc` drifts from catalog `protobuf 4.36.2`; shell Gradle can disagree with wrapper Gradle. | S | Pin shell `gradle`/`protoc` to wrapper/catalog versions (or stop putting shell Gradle on `PATH`); assert the match in `check.sh`. |
| Release-signing detection (`app/build.gradle`) | Conditional apply of gitignored `signing.gradle`; `hasProperty("release")` checks plus a debug fallback plus a narrow `whenReady` regex guard. `hasProperty` on the container is an unsupported existence check (`findByName != null` is the documented pattern); runtime behavior unverified statically. Any release-ish task outside the regex skips the guard; the intermediate state looks like a valid release signed by debug. Wording softened per validity check — hardening stands. | S | Switch both checks to `findByName`; consider removing the debug fallback so AGP fails unsigned even outside the regex; ship a `signing.gradle.example` template. |
| Non-hermetic `versionName` (`app/build.gradle`, `git describe --always`) | `--always` falls back to a bare short-SHA on shallow/tagless clones, producing non-semver `versionName` and unreproducible artifacts across fresh CI clones and tagless worktrees. | S | Fail fast with a clear message when `describe` yields no tag (CI should fetch tags and avoid shallow depth) instead of shipping a SHA version. |

## P2

| Location | Issue | Effort | Remediation |
|---|---|---|---|
| `testNativeAudioEngine` never up-to-date | `Exec` task with no `inputs`/`outputs`; `check` always pays a full native rebuild (~50 s cold). Direct `test` without the script path fails crypt tests that load `libhumlaaudio` from a hardcoded build dir. | S | Declare inputs/outputs (or `upToDateWhen`); make `java.library.path` wiring resolve the lib and order native build where needed. |
| Network fetch inside the build (`prepareRnnoiseModel.doLast`) | Bare `new URL(...).withInputStream` download with no timeout/retry/mirror; digest gating and stamp checks are good but hermeticity is not. Offline/sandboxed builds fail. | S | Vendor or prefetch the tarball (fixed-output derivation or checked-in cache); add timeouts/retries; keep the digest fail-closed check. |
| Single-`foss`-flavor lock-in | Shared ABI filters, single flavor retained to avoid renaming tasks, and `check.sh` hardcoding flavor task names. Adding a dimension renames every task and breaks the gate/docs together; `armeabi-v7a` has no local emulator coverage. Documented intent — acceptable, but brittle. | S | Extract flavor/task names into variables or discovery when a second flavor becomes plausible; note the ABI test gap. |
| `check.sh` covers tests only | No lint, no packaging, soft `master` guard; re-exec inherits ambient lock state without purity flags. | S | Add `lintFossDebug` and `assembleFossDebug` stages; consider failing (not warning) on `master` for write paths. |
| Modern-stack lag cluster (kapt, Paparazzi, legacy flags) | KGP rides the root classpath, kapt runs under `legacy-kapt` with a KSP TODO; Paparazzi pinned to an alpha behind a flag because unconditional use breaks the unit-test classpath; legacy `-jvm-default=enable` and `testNamespace` TODO remain; no configuration cache. Each AGP/KGP bump re-opens the same verification burden. | M | Track as one bundle behind the existing KSP TODO; re-verify `buildCMake*` exclusion from unit-test tasks plus Paparazzi re-enablement together. |
| Small script/Nix notes | `GRADLE_OPTS` clobbered unconditionally; single build-tools version is a second manually-synced string; worktree model-copy relies on loud notices; no signing template (error names the file but not its shape). | XS | Append instead of clobber; extract the version string; keep notices loud; add the example file. |
