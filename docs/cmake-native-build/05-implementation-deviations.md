# 5. Implementation Deviations from the Blueprint

This document records every place where the landed implementation on branch
`build/cmake-native` intentionally differs from the blueprint in
[04-migration-blueprint-and-pitfalls.md](04-migration-blueprint-and-pitfalls.md).
There are three deviations: one functional fix and two judgment calls.
Everything else — including
[`libraries/humla/src/main/jni/CMakeLists.txt`](../../libraries/humla/src/main/jni/CMakeLists.txt),
which is byte-identical to the blueprint's code block — follows the plan verbatim.

---

## 5.1 Absolute `ctest` Resolution in `scripts/test_native_audio.sh` (Functional Fix)

**What the plan says:**
[§3](04-migration-blueprint-and-pitfalls.md#3-simplified-scriptstest_native_audiosh)
ends the wrapper with:

```bash
"$CMAKE_BIN" -E chdir "$BUILD_DIR" ctest --output-on-failure
```

relying on a bare `ctest` resolved via `PATH`.

**What was implemented:**
[`scripts/test_native_audio.sh`](../../scripts/test_native_audio.sh) resolves
`CTEST_BIN` exactly like `CMAKE_BIN`/`NINJA_BIN` (`command -v ctest` first,
`$ANDROID_HOME/cmake/4.1.2/bin/ctest` fallback), requires all three, and runs:

```bash
"$CMAKE_BIN" -E chdir "$BUILD_DIR" "$CTEST_BIN" --output-on-failure
```

**Why:**
In any shell where `cmake`/`ninja` arrive via the `ANDROID_HOME` fallback
without the SDK `bin/` directory on `PATH`, `cmake -E chdir <dir> ctest`
fails with a bare `no such file or directory` because the `ctest` subcommand
cannot be exec'd. This was reproduced during implementation: the wrapper
succeeded under `nix develop` (where the new `flake.nix` `PATH` export puts
`ctest` on `PATH`) but failed when `check.sh` ran in an environment without
that export. Resolving `ctest` absolutely makes the wrapper robust in both
environments. The error message was updated accordingly ("cmake, ninja, and
ctest are required").

---

## 5.2 Removed Unused `import java.util.Locale` (Incidental Cleanup)

**What the plan says:**
[§2](04-migration-blueprint-and-pitfalls.md#2-changes-to-librarieshumlabuildgradle)
orders deleting the `ndkBuild` task, the `startParameter` gate, the
`taskGraph.whenReady` guard, and the `clean` block, but is silent on imports.

**What was implemented:**
`import java.util.Locale` was removed from
[`libraries/humla/build.gradle`](../../libraries/humla/build.gradle).

**Why:**
`Locale` was used solely by the deleted `ndkBuild` task (the Windows `.cmd`
`os.name` sniff). Leaving it behind would be dead code with no remaining
consumer in the file.

---

## 5.3 Deleted `Android.mk` / `Application.mk` (Judgment Call)

**What the plan says:**
[§1](04-migration-blueprint-and-pitfalls.md#1-complete-librarieshumlasrcmainjnicmakeliststxt)
states that `CMakeLists.txt` "replaces both" makefiles, but the deletion list
in [§2](04-migration-blueprint-and-pitfalls.md#2-changes-to-librarieshumlabuildgradle)
covers only the Gradle references (`sourceSets` lines, `ndkBuild` task, gates,
`clean` block) — it never explicitly orders deleting the `.mk` files.

**What was implemented:**
`libraries/humla/src/main/jni/Android.mk` and `Application.mk` were deleted
(`git rm`). The upstream `opus/*_sources.mk` files are untouched and remain
the parsed source of truth for `CMakeLists.txt`.

**Why:**
Once AGP's `externalNativeBuild.cmake` is wired, the two makefiles are
unreferenced dead code; keeping them invites future source-list drift between
two authorities. Historical references to them in this report directory are
intentionally preserved as investigation context.

---

## Verification-Scope Note (Not a Code Deviation)

The blueprint's size-parity table was proven via `:libraries:humla:assembleRelease`
(AAR, `RelWithDebInfo`) rather than a signed release APK, because this machine
has no local `app/signing.gradle` (required for `assembleFossRelease`). The
stripped outputs matched the blueprint's `android-30` column byte-for-byte
(`arm64-v8a` 730,088 B, `armeabi-v7a` 506,592 B, `x86_64` 798,016 B) with
intact hardening (`0x4000` LOAD alignment, `GNU_RELRO`, `BIND_NOW`,
`__stack_chk_fail`) and zero `opus_*`/`rnnoise_*` symbol leaks, so the proof
is equivalent.
