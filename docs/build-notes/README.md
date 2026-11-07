# Build Friction Notes

Short list of suboptimal spots in the Mumla OLED build, for future cleanup.
See [`app/build.gradle`](../../app/build.gradle),
[`libraries/humla/build.gradle`](../../libraries/humla/build.gradle),
[`flake.nix`](../../flake.nix),
[`gradle/libs.versions.toml`](../../gradle/libs.versions.toml), and
[`scripts/check.sh`](../../scripts/check.sh).

1. **Hand-wired NDK build.** Custom `ndkBuild` Exec task gated by
   task-name regex plus a fail-closed `taskGraph.whenReady` guard.
   Fragile to renames, and outputs to `src/main/libs` inside the source tree.
2. **Network fetch at build time.** RNNoise model tarball is downloaded
   during `preBuild` (hash-verified, but still non-hermetic). Breaks
   offline builds; depends on upstream hosting.
3. **Duplicated config.** NDK version in both `flake.nix` and
   `humla/build.gradle` (comment-synced), `abiFilters` copy-pasted in
   both modules, single no-op `foss` flavor/dimension, same `.oled15`
   suffix on debug and release.
4. **Half-migrated version catalog.** Catalog is meant to be the single
   source of truth, but `app/build.gradle` still hardcodes many
   `androidx` versions.
5. **Slow gate.** `check.sh` re-execs itself under one `nix develop`
   entry (fixed: was twice per run, native + Gradle) but still always
   runs the full native suite (~50 s cold). No incrementality at the
   Gradle level.
