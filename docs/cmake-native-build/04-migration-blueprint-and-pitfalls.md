# 4. Migration Blueprint, Pitfalls & Verification Checklist

This document provides the concrete implementation blueprint for migrating Mumla OLED's native build to CMake, cataloging the six non-obvious pitfalls that must be guarded against and supplying the complete target files.

---

## 4.1 Six Pitfalls & Required Mitigations

### Pitfall 1: AGP Uses `Debug` (`-O0`) and `RelWithDebInfo` (`-O2`), Not `Release` (`-O3`)
- **Mechanism**: Under `ndk-build`, `#APP_OPTIM := debug` was commented out in [`Application.mk`](../../libraries/humla/src/main/jni/Application.mk), so `ndk-build` always compiled with `-O3 -DNDEBUG`. Under AGP's `externalNativeBuild.cmake`:
  - `debug` builds pass `-DCMAKE_BUILD_TYPE=Debug` (`-g -fno-limit-debug-info`, i.e. **`-O0` and no `-DNDEBUG`**).
  - `release` builds pass `-DCMAKE_BUILD_TYPE=RelWithDebInfo` (which uses CMake's default **`-O2 -g -DNDEBUG`**, because NDK 27's `android-legacy.toolchain.cmake` only appends `-O3` to `CMAKE_C_FLAGS_RELEASE`, not `CMAKE_C_FLAGS_RELWITHDEBINFO`).
- **Mitigation**: Explicitly add `-O3 -DNDEBUG` to `target_compile_options` for `humlaopus`, `humlarnnoise`, and `humlaaudio` inside the `if(ANDROID)` block of `CMakeLists.txt`. Because `target_compile_options` are appended *after* `CMAKE_<LANG>_FLAGS_<CONFIG>` on the compiler command line, Clang always optimizes the real-time audio pipeline at `-O3` in both debug and release APKs while still retaining `-g` debug info for `strip*DebugSymbols`.

### Pitfall 2: NDK CMake Enables RTTI & Public Visibility by Default and Omits `16 KiB` Page Alignment on `armeabi-v7a`
- **Mechanism**: Unlike `ndk-build`, NDK's CMake toolchain leaves C++ RTTI enabled, does not pass `-fvisibility=hidden` or `-Wl,--exclude-libs,ALL`, omits `-Wl,--gc-sections` in `Debug` builds, and only passes `-Wl,-z,max-page-size=16384` on 64-bit ABIs when `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES` is enabled.
- **Mitigation**: Set `-ffunction-sections -fdata-sections -fvisibility=hidden -fstack-protector-strong -D_FORTIFY_SOURCE=2` on all Android targets, `$<$<COMPILE_LANGUAGE:CXX>:-fexceptions -fno-rtti>` on `humlaaudio`, and `-Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,relro,-z,now` in `target_link_options(humlaaudio PRIVATE ...)`.

### Pitfall 3: `prepareRnnoiseModel` Must Run Before `configureCMake*`
- **Mechanism**: CMake validates the existence of all target source files—including `rnnoise-build/generated/rnnoise_data.c`—at **configure time** (`configureCMakeDebug[<abi>]` / `configureCMakeRelWithDebInfo[<abi>]`). In AGP, `configureCMake*` tasks do **not** depend on `preBuild`. On a cold worktree without pre-copied RNNoise model files, or when `:libraries:humla:mergeDebugNativeLibs` is run directly, `configureCMake*` will fail if `prepareRnnoiseModel` has not yet run.
- **Mitigation**: In [`libraries/humla/build.gradle`](../../libraries/humla/build.gradle), wire:
  ```groovy
  tasks.matching { it.name.startsWith("configureCMake") }.configureEach {
      dependsOn(tasks.named("prepareRnnoiseModel"))
  }
  ```
  (in addition to keeping `preBuild.dependsOn prepareRnnoiseModel` for the `src/main/assets/rnnoise_model.bin` asset path).

### Pitfall 4: CMake `4.1.2` Deprecation Warnings on NDK 27's `android.toolchain.cmake`
- **Mechanism**: NDK `27.2.12479018` specifies `cmake_minimum_required(VERSION 3.6.0)` inside `build/cmake/android.toolchain.cmake`, `android-legacy.toolchain.cmake`, and `flags.cmake`. CMake `4.1.2` (shipped in `$ANDROID_HOME/cmake/4.1.2`) emits a deprecation warning whenever an included file requests `VERSION < 3.10`.
- **Mitigation**: Pass `arguments "-DANDROID_STL=c++_static", "-Wno-deprecated"` in `defaultConfig.externalNativeBuild.cmake` in [`libraries/humla/build.gradle`](../../libraries/humla/build.gradle).

### Pitfall 5: `NDK_CCACHE` Environment Variable vs CMake Compiler Launcher & Cross-Worktree Paths
- **Mechanism**: NDK's `android.toolchain.cmake` checks the CMake variable `NDK_CCACHE` / `ANDROID_CCACHE` and ignores `$ENV{NDK_CCACHE}`. Furthermore, without `CCACHE_BASEDIR` and `CCACHE_NOHASHDIR=1`, every worktree under `.worktrees/<branch>` misses the shared `ccache`.
- **Mitigation**:
  1. In `CMakeLists.txt`, configure `CMAKE_C_COMPILER_LAUNCHER` and `CMAKE_CXX_COMPILER_LAUNCHER` from `$ENV{NDK_CCACHE}` or `find_program(CCACHE_PROGRAM ccache)`.
  2. In [`flake.nix`](../../flake.nix) `shellHook`, export `CCACHE_BASEDIR="${PWD}"` and `CCACHE_NOHASHDIR=1`.

### Pitfall 6: Host JVM Unit Tests (`CryptStateTest`) Need Host `libhumlaaudio.so`
- **Mechanism**: When `:libraries:humla:testDebugUnitTest` runs on the JVM, `CryptState` calls `System.loadLibrary("humlaaudio")` against `java.library.path` (`${project.rootDir}/build/test-native`). Under `scripts/check.sh`, step 2b (`scripts/test_native_audio.sh`) runs before step 3 (`gradlew`), so `build/test-native/libhumlaaudio.so` is always present. However, if a developer creates a brand-new worktree and runs `nix develop --command ./gradlew :libraries:humla:testDebugUnitTest` directly *before* running `scripts/test_native_audio.sh`, `build/test-native/libhumlaaudio.so` does not exist yet.
- **Opportunity with CMake**: Because `CMakeLists.txt` defines the 2-TU host `humlaaudio` shared library as a distinct target (`ninja -C build/test-native humlaaudio`), building *only* the host JNI library takes **< 0.1 s** warm / **< 0.5 s** cold (and `5 ms` when up-to-date) without compiling or running the 156-TU Opus/audio test binaries. If desired, a lightweight Gradle task `buildHostTestJni` (`ninja -C build/test-native humlaaudio`) can be wired to `tasks.withType(Test)` at virtually zero cost (`5 ms` no-op), or the current `check.sh` ordering can be kept as-is.

---

## 4.2 Target File Implementations

### 1. Complete `libraries/humla/src/main/jni/CMakeLists.txt`

Replaces both [`libraries/humla/src/main/jni/Android.mk`](../../libraries/humla/src/main/jni/Android.mk) and [`libraries/humla/src/main/jni/Application.mk`](../../libraries/humla/src/main/jni/Application.mk), as well as the bash build logic in [`scripts/test_native_audio.sh`](../../scripts/test_native_audio.sh):

```cmake
# Copyright (C) 2013 Andrew Comminos
# Copyright (C) 2026 Brian Zhu
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.

cmake_minimum_required(VERSION 3.22...4.1)
project(humla_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Route compilation through ccache when available (covers both Android NDK and
# host builds; NDK's android.toolchain.cmake does not read $ENV{NDK_CCACHE}).
if(NOT CMAKE_C_COMPILER_LAUNCHER AND NOT CMAKE_CXX_COMPILER_LAUNCHER)
    if(DEFINED ENV{NDK_CCACHE} AND EXISTS "$ENV{NDK_CCACHE}")
        set(CCACHE_PROGRAM "$ENV{NDK_CCACHE}")
    else()
        find_program(CCACHE_PROGRAM ccache)
    endif()
    if(CCACHE_PROGRAM)
        set(CMAKE_C_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
        set(CMAKE_CXX_COMPILER_LAUNCHER "${CCACHE_PROGRAM}")
    endif()
endif()

set(JNI_ROOT "${CMAKE_CURRENT_SOURCE_DIR}")
set(OPUS_DIR "${JNI_ROOT}/opus")
set(RNNOISE_DIR "${JNI_ROOT}/rnnoise")
set(RNNOISE_BUILD_DIR "${JNI_ROOT}/rnnoise-build")
set(AUDIO_ENGINE_DIR "${JNI_ROOT}/audio_engine")
set(CRYPTO_DIR "${JNI_ROOT}/crypto")
set(TEST_CPP_DIR "${JNI_ROOT}/../../test/cpp")

# Parse source lists from upstream Opus .mk files (file(STRINGS) joins \-continuations).
# The opus submodule is pinned to upstream v1.6.1
# (22244de5a79bd1d6d623c32e72bf1954b56235be); lpcnet_sources.mk (dnn/, deep
# PLC + DRED) is opt-in and conflicts with fixed-point, so it is excluded.
function(parse_opus_mk mk_file var_name out_var)
    file(STRINGS "${mk_file}" lines)
    set(result "")
    foreach(line IN LISTS lines)
        if(line MATCHES "^${var_name}[ \t]*=[ \t]*(.*)$")
            string(REGEX MATCHALL "[^ \t;]+" items "${CMAKE_MATCH_1}")
            foreach(item IN LISTS items)
                if(NOT item MATCHES "^#")
                    list(APPEND result "${OPUS_DIR}/${item}")
                endif()
            endforeach()
        endif()
    endforeach()
    if(NOT result)
        message(FATAL_ERROR "Failed to parse ${var_name} from ${mk_file}")
    endif()
    set(${out_var} "${result}" PARENT_SCOPE)
endfunction()

parse_opus_mk("${OPUS_DIR}/celt_sources.mk" "CELT_SOURCES" CELT_SOURCES)
parse_opus_mk("${OPUS_DIR}/silk_sources.mk" "SILK_SOURCES" SILK_SOURCES)
parse_opus_mk("${OPUS_DIR}/silk_sources.mk" "SILK_SOURCES_FIXED" SILK_SOURCES_FIXED)
parse_opus_mk("${OPUS_DIR}/opus_sources.mk" "OPUS_SOURCES" OPUS_SOURCES)
parse_opus_mk("${OPUS_DIR}/opus_sources.mk" "OPUS_SOURCES_FLOAT" OPUS_SOURCES_FLOAT)

set(OPUS_COMPILE_FLAGS
    -DOPUS_BUILD
    -DVAR_ARRAYS
    -DFIXED_POINT
    -DHAVE_LRINTF=1
)

# Flag & header scoping:
# - humlaopus: plain codec flags; exposes only PUBLIC opus/include.
#   -DUSE_WEIGHTS_FILE must never leak here (it redirects OPUS_SET_DNN_BLOB to
#   lpcnet_plc_load_model/silk_LoadOSCEModels, whose dnn/ sources are omitted).
# - humlarnnoise: PRIVATE -DHAVE_CONFIG_H -DUSE_WEIGHTS_FILE; exposes only
#   PUBLIC rnnoise/include so internal headers (arch.h, pitch.h, kiss_fft.h,
#   celt_lpc.h) never collide with opus/celt/.
add_library(humlaopus STATIC
    ${CELT_SOURCES}
    ${SILK_SOURCES}
    ${SILK_SOURCES_FIXED}
    ${OPUS_SOURCES}
    ${OPUS_SOURCES_FLOAT}
)
target_include_directories(humlaopus
    PUBLIC  "${OPUS_DIR}/include"
    PRIVATE "${OPUS_DIR}/celt"
            "${OPUS_DIR}/silk"
            "${OPUS_DIR}/silk/float"
            "${OPUS_DIR}/silk/fixed"
)

if(ANDROID)
    set(COMMON_ANDROID_FLAGS
        -ffunction-sections
        -fdata-sections
        -fvisibility=hidden
        -fstack-protector-strong
        -D_FORTIFY_SOURCE=2
    )

    target_compile_options(humlaopus PRIVATE
        ${COMMON_ANDROID_FLAGS}
        ${OPUS_COMPILE_FLAGS}
        -O3
        -DNDEBUG
    )

    add_library(humlarnnoise STATIC
        "${RNNOISE_BUILD_DIR}/generated/rnnoise_data.c"
        "${RNNOISE_DIR}/src/rnnoise_tables.c"
        "${RNNOISE_DIR}/src/rnn.c"
        "${RNNOISE_DIR}/src/pitch.c"
        "${RNNOISE_DIR}/src/nnet.c"
        "${RNNOISE_DIR}/src/nnet_default.c"
        "${RNNOISE_DIR}/src/parse_lpcnet_weights.c"
        "${RNNOISE_DIR}/src/kiss_fft.c"
        "${RNNOISE_DIR}/src/denoise.c"
        "${RNNOISE_DIR}/src/celt_lpc.c"
    )
    target_include_directories(humlarnnoise
        PUBLIC  "${RNNOISE_DIR}/include"
        PRIVATE "${RNNOISE_DIR}/src"
                "${RNNOISE_BUILD_DIR}"
                "${RNNOISE_BUILD_DIR}/generated"
    )
    target_compile_options(humlarnnoise PRIVATE
        ${COMMON_ANDROID_FLAGS}
        -DHAVE_CONFIG_H
        -DUSE_WEIGHTS_FILE
        -O3
        -DNDEBUG
        -fno-math-errno
        -fvectorize
        -DVAR_ARRAYS
        "-Wno-#warnings"
    )

    # Single-library layout: Opus, RNNoise, audio engines, Speex jitter buffer,
    # and OCB2 crypto all link into libhumlaaudio.so.
    add_library(humlaaudio SHARED
        "${AUDIO_ENGINE_DIR}/PreSpeechRingBuffer.cpp"
        "${AUDIO_ENGINE_DIR}/SoftLimiter.cpp"
        "${AUDIO_ENGINE_DIR}/AdaptiveLeveler.cpp"
        "${AUDIO_ENGINE_DIR}/HysteresisVad.cpp"
        "${AUDIO_ENGINE_DIR}/RnnoiseProcessor.cpp"
        "${AUDIO_ENGINE_DIR}/OpusVoiceEncoder.cpp"
        "${AUDIO_ENGINE_DIR}/AudioInputEngine.cpp"
        "${AUDIO_ENGINE_DIR}/NativeAudioInputEngineJni.cpp"
        "${AUDIO_ENGINE_DIR}/OpusVoiceDecoder.cpp"
        "${AUDIO_ENGINE_DIR}/AudioOutputEngine.cpp"
        "${AUDIO_ENGINE_DIR}/NativeAudioOutputEngineJni.cpp"
        "${AUDIO_ENGINE_DIR}/NativeCrashTestJni.cpp"
        "${AUDIO_ENGINE_DIR}/jitter/jitter.c"
        "${CRYPTO_DIR}/CryptStateOCB2.cpp"
        "${CRYPTO_DIR}/NativeCryptStateJni.cpp"
    )
    target_include_directories(humlaaudio PRIVATE
        "${AUDIO_ENGINE_DIR}"
        "${AUDIO_ENGINE_DIR}/jitter"
        "${CRYPTO_DIR}"
    )
    target_compile_options(humlaaudio PRIVATE
        ${COMMON_ANDROID_FLAGS}
        ${OPUS_COMPILE_FLAGS}
        -O3
        -DNDEBUG
        $<$<COMPILE_LANGUAGE:CXX>:-fexceptions -fno-rtti>
    )
    target_link_options(humlaaudio PRIVATE
        -Wl,-z,max-page-size=16384
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
        -Wl,-z,relro,-z,now
    )
    target_link_libraries(humlaaudio PRIVATE
        humlaopus
        humlarnnoise
        log
    )
else()
    # Host native test executables & host JNI shared library.
    enable_testing()
    find_package(Threads REQUIRED)
    find_package(JNI REQUIRED)

    set(HOST_FLAGS -O2 -Wall -Wextra -Werror -UNDEBUG)

    target_compile_options(humlaopus PRIVATE
        ${OPUS_COMPILE_FLAGS}
        ${HOST_FLAGS}
        -Wno-maybe-uninitialized
    )

    add_library(humlacrypto_obj OBJECT
        "${CRYPTO_DIR}/CryptStateOCB2.cpp"
    )
    target_include_directories(humlacrypto_obj PUBLIC "${CRYPTO_DIR}")
    target_compile_options(humlacrypto_obj PRIVATE ${HOST_FLAGS})

    add_executable(test_audio_engine
        "${AUDIO_ENGINE_DIR}/jitter/jitter.c"
        "${AUDIO_ENGINE_DIR}/SoftLimiter.cpp"
        "${AUDIO_ENGINE_DIR}/PreSpeechRingBuffer.cpp"
        "${AUDIO_ENGINE_DIR}/AdaptiveLeveler.cpp"
        "${AUDIO_ENGINE_DIR}/AudioInputEngine.cpp"
        "${AUDIO_ENGINE_DIR}/AudioOutputEngine.cpp"
        "${AUDIO_ENGINE_DIR}/HysteresisVad.cpp"
        "${AUDIO_ENGINE_DIR}/OpusVoiceEncoder.cpp"
        "${AUDIO_ENGINE_DIR}/OpusVoiceDecoder.cpp"
        "${TEST_CPP_DIR}/test_biquad_filter.cpp"
        "${TEST_CPP_DIR}/test_soft_limiter.cpp"
        "${TEST_CPP_DIR}/test_pre_speech_ring_buffer.cpp"
        "${TEST_CPP_DIR}/test_adaptive_leveler.cpp"
        "${TEST_CPP_DIR}/test_hysteresis_vad.cpp"
        "${TEST_CPP_DIR}/test_jitter_buffer.cpp"
        "${TEST_CPP_DIR}/test_audio_input_engine.cpp"
        "${TEST_CPP_DIR}/test_audio_output_engine.cpp"
        "${TEST_CPP_DIR}/test_opus_interop.cpp"
        "${TEST_CPP_DIR}/run_audio_tests.cpp"
    )
    target_include_directories(test_audio_engine PRIVATE
        "${AUDIO_ENGINE_DIR}"
        "${AUDIO_ENGINE_DIR}/jitter"
        "${TEST_CPP_DIR}"
    )
    target_compile_options(test_audio_engine PRIVATE ${HOST_FLAGS} ${OPUS_COMPILE_FLAGS})
    target_link_libraries(test_audio_engine PRIVATE humlaopus Threads::Threads m)
    add_test(NAME test_audio_engine COMMAND test_audio_engine)

    add_executable(test_crypt_state
        $<TARGET_OBJECTS:humlacrypto_obj>
        "${TEST_CPP_DIR}/test_crypt_state.cpp"
    )
    target_include_directories(test_crypt_state PRIVATE "${CRYPTO_DIR}" "${TEST_CPP_DIR}")
    target_compile_options(test_crypt_state PRIVATE ${HOST_FLAGS})
    target_link_libraries(test_crypt_state PRIVATE Threads::Threads)
    add_test(NAME test_crypt_state COMMAND test_crypt_state)

    add_library(humlaaudio SHARED
        $<TARGET_OBJECTS:humlacrypto_obj>
        "${CRYPTO_DIR}/NativeCryptStateJni.cpp"
    )
    target_include_directories(humlaaudio PRIVATE "${CRYPTO_DIR}" ${JNI_INCLUDE_DIRS})
    target_compile_options(humlaaudio PRIVATE ${HOST_FLAGS})
    target_link_libraries(humlaaudio PRIVATE Threads::Threads)
endif()
```

---

### 2. Changes to [`libraries/humla/build.gradle`](../../libraries/humla/build.gradle)

1. **Remove** `sourceSets.main.jniLibs.srcDir 'src/main/libs/'` and `sourceSets.main.jni.srcDirs = []` (lines 83–84).
2. **Add** `externalNativeBuild` inside `defaultConfig` and `android`:
   ```groovy
   defaultConfig {
       testApplicationId "se.lublin.humla.test"
       testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
       minSdkVersion 30
       targetSdk 36

       ndk {
           abiFilters.addAll(rootProject.ext.sharedAbiFilters)
       }

       externalNativeBuild {
           cmake {
               arguments "-DANDROID_STL=c++_static", "-Wno-deprecated"
           }
       }
   }

   externalNativeBuild {
       cmake {
           path = file("src/main/jni/CMakeLists.txt")
           version = "4.1.2"
       }
   }
   ```
3. **Replace** `task ndkBuild(type: Exec)`, the `if (gradle.startParameter.taskNames.any ...)` block, the `gradle.taskGraph.whenReady` guard, and the `tasks.named('clean', Delete)` block with:
   ```groovy
   tasks.matching { it.name.startsWith("configureCMake") }.configureEach {
       dependsOn(tasks.named("prepareRnnoiseModel"))
   }
   ```

---

### 3. Simplified [`scripts/test_native_audio.sh`](../../scripts/test_native_audio.sh)

Replaces the 371-line custom bash incremental build driver with a thin CMake + Ninja wrapper:

```bash
#!/usr/bin/env bash
#
# scripts/test_native_audio.sh: Configure, compile, and execute the host C++
# audio engine and crypto test suites (plus the host JNI shared library) via
# CMake and Ninja.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JNI_DIR="$ROOT_DIR/libraries/humla/src/main/jni"
BUILD_DIR="$ROOT_DIR/build/test-native"

# Locate cmake and ninja (prefer PATH, fall back to SDK cmake under ANDROID_HOME).
CMAKE_BIN="$(command -v cmake || true)"
NINJA_BIN="$(command -v ninja || true)"
if [[ -z "$CMAKE_BIN" || -z "$NINJA_BIN" ]] && [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/cmake" ]]; then
    SDK_CMAKE_BIN_DIR="$(find "$ANDROID_HOME/cmake" -maxdepth 2 -type d -name bin 2>/dev/null | sort -V | tail -n 1)"
    if [[ -n "$SDK_CMAKE_BIN_DIR" ]]; then
        [[ -z "$CMAKE_BIN" && -x "$SDK_CMAKE_BIN_DIR/cmake" ]] && CMAKE_BIN="$SDK_CMAKE_BIN_DIR/cmake"
        [[ -z "$NINJA_BIN" && -x "$SDK_CMAKE_BIN_DIR/ninja" ]] && NINJA_BIN="$SDK_CMAKE_BIN_DIR/ninja"
    fi
fi

if [[ -z "$CMAKE_BIN" || -z "$NINJA_BIN" ]]; then
    echo "test_native_audio.sh: error: cmake and ninja are required (enter 'nix develop' or install cmake/ninja)." >&2
    exit 1
fi

CC_BIN="${CC:-gcc}"
CXX_BIN="${CXX:-g++}"

# Configure when build.ninja is missing or the toolchain changed.
TOOLCHAIN_STAMP="$BUILD_DIR/.toolchain_fingerprint"
TOOLCHAIN_CANDIDATE="$BUILD_DIR/.toolchain_fingerprint.new"
mkdir -p "$BUILD_DIR"
{ printf '%s\n%s\n' "$CC_BIN" "$CXX_BIN"; "$CXX_BIN" --version; } > "$TOOLCHAIN_CANDIDATE" 2>/dev/null || echo "unknown" > "$TOOLCHAIN_CANDIDATE"
if [[ ! -f "$BUILD_DIR/build.ninja" ]] || ! cmp -s "$TOOLCHAIN_STAMP" "$TOOLCHAIN_CANDIDATE" 2>/dev/null; then
    rm -rf "$BUILD_DIR/CMakeCache.txt" "$BUILD_DIR/CMakeFiles"
    "$CMAKE_BIN" -Wno-deprecated \
        -S "$JNI_DIR" \
        -B "$BUILD_DIR" \
        -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$NINJA_BIN" \
        -DCMAKE_C_COMPILER="$CC_BIN" \
        -DCMAKE_CXX_COMPILER="$CXX_BIN"
    mv "$TOOLCHAIN_CANDIDATE" "$TOOLCHAIN_STAMP"
else
    rm -f "$TOOLCHAIN_CANDIDATE"
fi

"$NINJA_BIN" -C "$BUILD_DIR"

"$BUILD_DIR/test_audio_engine"
"$BUILD_DIR/test_crypt_state"
```

---

### 4. Supporting Updates in [`flake.nix`](../../flake.nix), [`.gitignore`](../../.gitignore), and [`AGENTS.md`](../../AGENTS.md)

1. **[`flake.nix`](../../flake.nix)**:
   - Remove `pkgs.gnumake` from `buildInputs` (no longer needed once `ndk-build` is retired).
   - Add `$ANDROID_HOME/cmake/4.1.2/bin` to `PATH` and export `CCACHE_BASEDIR` + `CCACHE_NOHASHDIR` in `shellHook`:
     ```nix
     export PATH="${androidSdkRoot}/cmake/4.1.2/bin:$PATH"
     export CCACHE_DIR="''${REPO_ROOT}/.gradle-home/ccache"
     export CCACHE_BASEDIR="''${PWD}"
     export CCACHE_NOHASHDIR=1
     ```
2. **[`libraries/humla/.gitignore`](../../libraries/humla/.gitignore) & [`.gitignore`](../../.gitignore)**:
   - Add `.cxx/` to ignore AGP's CMake build staging directory.
   - Remove `src/main/libs/` and `src/main/obj/` from `libraries/humla/.gitignore` after deleting any leftover untracked directories.
3. **[`AGENTS.md`](../../AGENTS.md)**:
   - Update the **Native Build Wiring** bullet under **Verification** to reference `externalNativeBuild.cmake` (`buildCMake*`) instead of `ndkBuild`.

---

## 4.3 Post-Implementation Verification Checklist

When executing this migration in a dedicated feature worktree (`.worktrees/build/cmake-native`), verify all of the following before completion:

1. **Task-graph isolation (`--dry-run`)**:
   - `nix develop --command ./gradlew testFossDebugUnitTest :libraries:humla:testDebugUnitTest --dry-run` contains **no** `configureCMake*` or `buildCMake*` tasks.
   - `nix develop --command ./gradlew assembleFossDebug --dry-run` includes `prepareRnnoiseModel`, `configureCMakeDebug[<abi>]`, `buildCMakeDebug[<abi>]`, `mergeDebugNativeLibs`, and `stripFossDebugDebugSymbols` for all 3 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`).
   - `nix develop --command ./gradlew :libraries:humla:mergeDebugNativeLibs --dry-run` includes `prepareRnnoiseModel` before `configureCMakeDebug[<abi>]`.
   - `nix develop --command ./gradlew lintFossDebug --dry-run` succeeds without throwing the old line-278 `GradleException`.
2. **Android APK & `.so` binary inspection**:
   - Run `nix develop --command ./gradlew assembleFossDebug` and inspect the packaged `lib/<abi>/libhumlaaudio.so` inside `app/build/outputs/apk/foss/debug/mumla-foss-debug.apk` with `llvm-readelf -d -l --dyn-syms`:
     - All `LOAD` segments have `0x4000` (16 KiB) alignment.
     - `GNU_RELRO`, `BIND_NOW`, and `__stack_chk_fail@LIBC` are present.
     - `.dynsym` exports the exact 43 JNI symbols (`JNI_OnLoad` + 42 `Java_se_lublin_humla_*` methods) and zero `opus_*` or `rnnoise_*` symbols.
3. **Host test & `check.sh` gate**:
   - Run `./scripts/check.sh --full` from a clean `build/test-native` directory and again on a warm incremental run; verify `test_audio_engine`, `test_crypt_state`, and all JVM unit tests (`CryptStateTest`, `HumlaUDP*Test`, `PingIntervalTest`, `AdaptiveKeepaliveTest`) pass.
