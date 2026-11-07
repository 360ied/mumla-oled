# 1. Current Native Build Architecture & Pain Points

This document audits the existing native build setup in Mumla OLED across both build surfaces—the Android NDK packaging pipeline and the host C++ test/JNI pipeline—and documents the concrete defects and maintenance costs that motivate a unified CMake + Ninja rewrite.

---

## 1.1 Overview of the Dual-Build Split

Mumla OLED compiles the native C/C++ audio and cryptographic sources under [`libraries/humla/src/main/jni/`](../../libraries/humla/src/main/jni/) and [`libraries/humla/src/test/cpp/`](../../libraries/humla/src/test/cpp/) through two completely independent build systems:

| Build Surface | Driver & Build Files | Compiler & Linker | Produced Artifacts | Consumers |
| :--- | :--- | :--- | :--- | :--- |
| **Android NDK** | [`libraries/humla/build.gradle`](../../libraries/humla/build.gradle) (`task ndkBuild`) $\rightarrow$ `ndk-build` $\rightarrow$ [`Android.mk`](../../libraries/humla/src/main/jni/Android.mk) + [`Application.mk`](../../libraries/humla/src/main/jni/Application.mk) | NDK 27.2.12479018 Clang 18.0.3 (`clang` for `.c`, `clang++` for `.cpp`), `lld` | `libhumlaopus.a`, `libhumlarnnoise.a` (`src/main/obj/local/<abi>/`), stripped `libhumlaaudio.so` (`src/main/libs/<abi>/`) for `armeabi-v7a`, `arm64-v8a`, `x86_64` | `:libraries:humla` AAR & `:app` APK packaging (`NativeAudioInputEngine`, `NativeAudioOutputEngine`, `CryptState`, `NativeCrashTest`) |
| **Host Native Suite & Host JNI** | [`scripts/check.sh`](../../scripts/check.sh) (step 2b) / `task testNativeAudioEngine` $\rightarrow$ [`scripts/test_native_audio.sh`](../../scripts/test_native_audio.sh) | Host GCC (`g++` for both `.c` and `.cpp`) | `build/test-native/test_audio_engine`, `build/test-native/test_crypt_state`, `build/test-native/libhumlaaudio.so` (or `.dylib`) | Direct execution in `check.sh` step 2b; JVM unit tests (`CryptStateTest`, `HumlaUDP*Test`, `PingIntervalTest`, `AdaptiveKeepaliveTest`) via `java.library.path` |

---

## 1.2 The 2014 Hand-Wired `ndkBuild` Exec Task & Its Cascading Workarounds

### Historical Origin
In [`libraries/humla/build.gradle:83-84`](../../libraries/humla/build.gradle#L83-L84), AGP's native build support is explicitly disabled:

```groovy
sourceSets.main.jniLibs.srcDir 'src/main/libs/'
sourceSets.main.jni.srcDirs = [] // Disable NDK build support until it supports multiple modules.
```

That comment dates back to commit `0c7fb4d2` / upstream Humla in 2014 (AGP 0.x/1.x, two years before AGP 2.2 introduced `externalNativeBuild` in 2016). In 2014, AGP's built-in `jni.srcDirs` hook auto-generated a single-module `Android.mk` on the fly and could not consume a custom multi-module `Android.mk`. To work around that limitation in 2014, the build defined a custom `task ndkBuild(type: Exec)` that shelled out to `ndk-build -C src/main/jni` and wrote `.so` files into `src/main/libs/`, pointing `sourceSets.main.jniLibs.srcDir` at `src/main/libs/` as if they were prebuilt third-party libraries.

### Cascading Workarounds Added Over Time
Because AGP treats `sourceSets.main.jniLibs.srcDir 'src/main/libs/'` as a static directory of prebuilt `.so` files rather than the output of a native build task, [`libraries/humla/build.gradle`](../../libraries/humla/build.gradle) had to accumulate manual dependency, invalidation, and guard logic (also flagged in [`docs/build-notes/README.md`](../build-notes/README.md) item #1):

1. **In-tree build pollution**:
   `ndk-build` writes intermediate `.o`/`.d`/`.a` files into `libraries/humla/src/main/obj/` and stripped `.so` binaries into `libraries/humla/src/main/libs/` inside the tracked source tree, requiring custom `clean` task deletions ([`libraries/humla/build.gradle:292-295`](../../libraries/humla/build.gradle#L292-L295)) and `.gitignore` exclusions ([`libraries/humla/.gitignore`](../../libraries/humla/.gitignore)).
2. **Manual NDK path & OS sniffing**:
   [`libraries/humla/build.gradle:198-216`](../../libraries/humla/build.gradle#L198-L216) manually queries `androidComponents.sdkComponents.ndkDirectory`, sniffs `os.name` for Windows `.cmd`, and declares `inputs.dir file('src/main/jni')`, `inputs.property("ndkVersion", android.ndkVersion)`, and `outputs.dir file('src/main/libs')`.
3. **Coarse task-level up-to-date tracking**:
   Because `inputs.dir file('src/main/jni')` hashes the entire `jni/` directory tree (including documentation, unused files in `opus/` and `rnnoise/`, and generated stamps), any change anywhere under `src/main/jni/` invalidates the Gradle task.
4. **The `gradle.startParameter.taskNames` regex gate**:
   Originally, `preBuild.dependsOn ndkBuild` ran a 3-ABI `ndk-build` on *every* Gradle invocation—including JVM-only unit tests (`testDebugUnitTest`). Commit `73a59682` (*humla: decouple unit tests from native rebuilds*) gated `preBuild.dependsOn ndkBuild` behind a regex on top-level CLI task names ([`libraries/humla/build.gradle:254-256`](../../libraries/humla/build.gradle#L254-L256)):
   ```groovy
   if (gradle.startParameter.taskNames.any { it ==~ /.*(assemble|bundle|package|install|publish|build|connected).*/ }) {
       tasks.named("preBuild").configure { dependsOn tasks.named("ndkBuild") }
   }
   ```
5. **The `gradle.taskGraph.whenReady` fail-closed guard**:
   Because `gradle.startParameter.taskNames` only inspects the literal CLI strings typed by the user, commit `ecb67475` (*humla: address review findings in gradle wiring*) added a post-graph validation guard ([`libraries/humla/build.gradle:265-280`](../../libraries/humla/build.gradle#L265-L280)):
   ```groovy
   gradle.taskGraph.whenReady { graph ->
       def consumesNativeLibs = graph.allTasks.any { t ->
           t.project == project && (
               (t.name.startsWith("merge") && (t.name.contains("JniLib") || t.name.contains("NativeLibs"))) ||
               (t.name.startsWith("copy") && t.name.contains("JniLib")) ||
               t.name.startsWith("strip") ||
               (t.name.startsWith("package") && !t.name.contains("Resources")) ||
               (t.name.startsWith("bundle") && t.name.endsWith("Aar")) ||
               t.name.startsWith("assemble")
           )
       }
       def hasNdkBuild = graph.allTasks.any { t -> t.project == project && t.name == "ndkBuild" }
       if (consumesNativeLibs && !hasNdkBuild) {
           throw new GradleException('Refusing to package native libs without ndkBuild in the task graph (direct packaging-subtask invocation is unsupported; run a top-level task such as :app:assembleFossDebug instead).')
       }
   }
   ```

### Active Bug on `master`: `./gradlew lintFossDebug` Fails Closed
The combination of the `startParameter.taskNames` regex and the `taskGraph.whenReady` guard breaks Android Lint on `master` (noted as pre-existing in commit `13946a2b`).

When `./gradlew lintFossDebug` is invoked:
1. `"lintFossDebug"` does **not** match `/.*(assemble|bundle|package|install|publish|build|connected).*/`, so `preBuild` does **not** depend on `ndkBuild`.
2. However, AGP's `:app:lintAnalyzeFossDebug` task depends on `:libraries:humla:bundleDebugLocalLintAar`, which depends on `:libraries:humla:mergeDebugJniLibFolders` and `:libraries:humla:copyDebugJniLibsProjectAndLocalJars`.
3. Those tasks match `t.name.startsWith("bundle") && t.name.endsWith("Aar")` and `t.name.contains("JniLib")` in `consumesNativeLibs`, causing `taskGraph.whenReady` to throw:

```text
FAILURE: Build failed with an exception.

* Where:
Build file 'libraries/humla/build.gradle' line: 278

* What went wrong:
Refusing to package native libs without ndkBuild in the task graph (direct packaging-subtask invocation is unsupported; run a top-level task such as :app:assembleFossDebug instead).
```

Direct subtask invocations such as `./gradlew :libraries:humla:mergeDebugJniLibFolders` fail with the exact same exception.

With AGP's first-class `externalNativeBuild.cmake`, AGP wires `buildCMakeDebug[<abi>]` directly into `mergeDebugJniLibFolders` via Gradle `DirectoryProperty` task outputs—never via `preBuild` or CLI task-name regexes. Both `lintFossDebug` and direct packaging subtasks work automatically, while `testFossDebugUnitTest` and `:libraries:humla:testDebugUnitTest` never trigger CMake builds (verified in [03-empirical-benchmarks-and-verification.md §3.3](03-empirical-benchmarks-and-verification.md#33-agp-901-task-graph-verification---dry-run)).

---

## 1.3 Duplication, Language Drift, and Coarse Tracking in `scripts/test_native_audio.sh`

[`scripts/test_native_audio.sh`](../../scripts/test_native_audio.sh) is a 371-line bash script that compiles and runs the host C++ audio engine test suite (`test_audio_engine`), the OCB2 crypto unit/stress suite (`test_crypt_state`), and the host JNI shared library (`build/test-native/libhumlaaudio.so` or `.dylib`).

### 1. Source and Flag Duplication
Every source file in `audio_engine/`, `crypto/`, and `opus/` and every compiler flag (`-DOPUS_BUILD -DVAR_ARRAYS -DFIXED_POINT -DHAVE_LRINTF=1`, include paths, etc.) is manually duplicated between [`Android.mk`](../../libraries/humla/src/main/jni/Android.mk) and [`scripts/test_native_audio.sh`](../../scripts/test_native_audio.sh). Adding or renaming a source file in `audio_engine/` or `crypto/` requires synchronized edits across both files plus the existence-check loop at [`scripts/test_native_audio.sh:21-46`](../../scripts/test_native_audio.sh#L21-L46).

### 2. C-as-C++17 Compilation Drift on the Host
In [`scripts/test_native_audio.sh:51-61`](../../scripts/test_native_audio.sh#L51-L61), the script sets `CXX="${CXX:-g++}"` and compiles **every** source file in `SRCS[@]`—including `audio_engine/jitter/jitter.c` and all 136 `.c` files from `opus/`—using `$CXX -std=c++17`:

```bash
$CXX "${CXXFLAGS[@]}" -c "${todo_src[$i]}" -o "$tmp_obj" &
```

When `g++` is invoked with `-std=c++17` on a `.c` file without `-x c`, GCC compiles the file as **C++17**, not C! By contrast, on Android (`ndk-build`), `.c` files are compiled with `clang` as **C** (`C99`/`C11`). Compiling C code as C++17 alters implicit conversion rules, `restrict` / VLA semantics, struct initialization rules, and compiler diagnostics. Under CMake (`project(humla_native LANGUAGES C CXX)`), `.c` files are compiled with the C compiler (`gcc` on host, `clang` on Android) and `.cpp` files with the C++17 compiler (`g++` on host, `clang++` on Android).

### 3. Reinvented Incremental Build Engine & Standing Deferred Items
Lines 130–320 of [`scripts/test_native_audio.sh`](../../scripts/test_native_audio.sh#L130-L320) implement a custom incremental build system in bash (`stat_mtime`, `tu_stale`, `newest_header_ep`, `compile_and_link`, `.toolchain_fingerprint`, `.header_set.cksum`, and PID-batched background compilation loops).

[`docs/check-sh-speed/README.md`](../check-sh-speed/README.md) documents three standing architectural limitations of this bash build engine that were deferred because fixing them in bash was too complex:

| Standing Item in `docs/check-sh-speed/README.md` | Root Cause in `scripts/test_native_audio.sh` | How CMake + Ninja Solves It Natively |
| :--- | :--- | :--- |
| **§1. Concurrent-run locking (`flock`)** | A toolchain, script, or header-set change runs `rm -rf "${objdir:?}"`, which can race with a concurrent invocation in the same worktree. | Ninja tracks build state atomically in `.ninja_log` and `.ninja_deps` and only rewrites affected outputs without wiping object trees mid-build. |
| **§2. `CXX` as an unquoted word-split string** | `CXX="ccache $CXX"` requires unquoted `$CXX` expansion at four sites with `# shellcheck disable=SC2086`. | CMake provides first-class `CMAKE_C_COMPILER_LAUNCHER` and `CMAKE_CXX_COMPILER_LAUNCHER` properties; the compiler path and launcher are never string-concatenated. |
| **§3. `-MMD` depfiles for precise header dependencies** | `newest_header_ep` finds the newest `.h` mtime across all header directories for a target. Touching **any single header** in `audio_engine/` or `test/cpp/` (e.g. `SoftLimiter.h` or `TestHarness.h`) marks **all ~155 translation units** stale—recompiling all 136 Opus `.c` files even though zero Opus files include `audio_engine/*.h` or `test/cpp/*.h`. | Ninja consumes compiler-generated `-MD -MT <obj> -MF <obj>.d` depfiles per translation unit (`deps = gcc` in `build.ninja`). Touching `SoftLimiter.h` recompiles **only the 3 `.cpp` files** that actually `#include "SoftLimiter.h"`. |

Additionally, in `scripts/test_native_audio.sh`:
- `CryptStateOCB2.cpp` is compiled **twice** into two separate object directories (`$OBJ_DIR/crypt` and `$OBJ_DIR/jni`) with identical flags (`-fPIC` can be shared via a CMake `OBJECT` library `humlacrypto_obj`, compiling `CryptStateOCB2.cpp` once).
- The three targets (`test_audio_engine`, `test_crypt_state`, `libhumlaaudio.so`) are built in three sequential `compile_and_link` barriers rather than a single unified dependency graph.

---

## 1.4 Broken Cross-Worktree `ccache` Sharing (81.67% Miss Rate)

Mumla OLED mandates dedicated Git worktrees under `.worktrees/<branch-name>` for all feature and bugfix development. To keep native builds fast across worktrees, [`flake.nix:84-90`](../../flake.nix#L84-L90) configures a shared `ccache` directory rooted at the main repository's `.gradle-home/ccache`:

```nix
NDK_CCACHE = "${pkgs.ccache}/bin/ccache";
```

```bash
export GRADLE_USER_HOME="${REPO_ROOT}/.gradle-home"
export ANDROID_USER_HOME="${REPO_ROOT}/.gradle-home/android"
export CCACHE_DIR="${REPO_ROOT}/.gradle-home/ccache"
```

### Measured Cache Miss Rate
Despite this shared `CCACHE_DIR`, querying `ccache -s` inside `nix develop` shows that **81.67% of all cacheable compiler invocations have been cache misses**:

```math
\text{Historical ccache Miss Rate} = \frac{68,096\text{ misses}}{83,379\text{ cacheable calls}} = 81.67\% \quad (\text{Hits: } 15,283 = 18.33\%)
```

### Root Causes of the 81.67% Miss Rate
1. **Missing `CCACHE_BASEDIR` and `CCACHE_NOHASHDIR=1` in `flake.nix`**:
   By default, `ccache` hashes the literal command-line `-I<path>` and `-c <path>` arguments and, when `-g` is present (which `ndk-build` and NDK CMake always pass), the compiler's current working directory (`DW_AT_comp_dir`). Because every worktree has a distinct path (`.worktrees/<branch-a>` vs `.worktrees/<branch-b>`), **every new worktree misses `ccache` 100% on its first Android and host native builds** and writes duplicate objects into the 1.2 GiB cache.
   Setting `export CCACHE_BASEDIR="${PWD}"` and `export CCACHE_NOHASHDIR=1` in `flake.nix`'s `shellHook` normalizes all paths under the active worktree root to relative paths before hashing, raising the cross-worktree cache hit rate from **0% to 100%** for unchanged files (proven in [03-empirical-benchmarks-and-verification.md §3.5](03-empirical-benchmarks-and-verification.md#35-cross-worktree-ccache-hit-rate-verification)).
2. **NDK CMake Ignores the `NDK_CCACHE` Environment Variable**:
   In `ndk-build`, GNU Make automatically imports environment variables as Make variables, so `export NDK_CCACHE=...` in `flake.nix` is picked up by `ndk-build`.
   However, NDK 27's `build/cmake/android.toolchain.cmake` and `build/cmake/android-legacy.toolchain.cmake` check:
   ```cmake
   if(NDK_CCACHE AND NOT ANDROID_CCACHE)
     set(ANDROID_CCACHE "${NDK_CCACHE}")
   endif()
   if(ANDROID_CCACHE)
     set(CMAKE_C_COMPILER_LAUNCHER   "${ANDROID_CCACHE}")
     set(CMAKE_CXX_COMPILER_LAUNCHER "${ANDROID_CCACHE}")
   endif()
   ```
   In CMake, `if(NDK_CCACHE)` tests a **CMake variable**, not `$ENV{NDK_CCACHE}`. Unless `-DANDROID_CCACHE=...` is passed or `CMAKE_<LANG>_COMPILER_LAUNCHER` is set in `CMakeLists.txt`, CMake Android builds bypass `ccache` entirely. Detecting `ccache` directly in `CMakeLists.txt` via `find_program(CCACHE_PROGRAM ccache)` (and honoring `$ENV{NDK_CCACHE}`) solves this for both Android and host builds in one place.
