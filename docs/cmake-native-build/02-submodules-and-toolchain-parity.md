# 2. Submodules (`opus` & `rnnoise`) & Toolchain Flag Parity

This document analyzes how the two third-party native submodules ([`libraries/humla/src/main/jni/opus`](../../libraries/humla/src/main/jni/opus) and [`libraries/humla/src/main/jni/rnnoise`](../../libraries/humla/src/main/jni/rnnoise)) should be built under CMake, why calling `add_subdirectory(opus)` is an anti-pattern for this repository, and how `ndk-build` compiler/linker flags map onto NDK 27's CMake toolchain.

---

## 2.1 Opus (`v1.6.1`) Submodule Analysis

The `opus` submodule is pinned to upstream tag `v1.6.1` (`22244de5a79bd1d6d623c32e72bf1954b56235be`). Upstream Opus ships both GNU Make source-list files (`celt_sources.mk`, `silk_sources.mk`, `opus_sources.mk`, `lpcnet_sources.mk`) and a CMake build system ([`opus/CMakeLists.txt`](../../libraries/humla/src/main/jni/opus/CMakeLists.txt) + [`opus/cmake/`](../../libraries/humla/src/main/jni/opus/cmake/)).

### Why `add_subdirectory(opus)` Is a Trap

A naive CMake migration might attempt `add_subdirectory(opus EXCLUDE_FROM_ALL)` and link `Opus::opus`. Inspection of [`opus/CMakeLists.txt`](../../libraries/humla/src/main/jni/opus/CMakeLists.txt) and [`opus/cmake/`](../../libraries/humla/src/main/jni/opus/cmake/) reveals four concrete problems with doing so:

1. **Broken Git Version Detection in `OpusPackageVersion.cmake`**:
   [`opus/cmake/OpusPackageVersion.cmake:8-13`](../../libraries/humla/src/main/jni/opus/cmake/OpusPackageVersion.cmake#L8-L13) attempts to resolve `PACKAGE_VERSION` via:
   ```cmake
   find_package(Git)
   if(GIT_FOUND AND EXISTS "${CMAKE_CURRENT_LIST_DIR}/.git")
     execute_process(COMMAND ${GIT_EXECUTABLE}
                     --git-dir=${CMAKE_CURRENT_LIST_DIR}/.git describe
                     --tags --match "v*" OUTPUT_VARIABLE OPUS_PACKAGE_VERSION)
   ```
   Because `OpusPackageVersion.cmake` resides in `opus/cmake/`, `${CMAKE_CURRENT_LIST_DIR}/.git` evaluates to `opus/cmake/.git`, which **never exists**. (Even at `opus/.git`, in a Git submodule or worktree `.git` is a `gitdir:` pointer file, not a directory.) The fallback file `opus/package_version` only exists in release tarballs, not git checkouts. Consequently, `get_package_version` always hits its failure branch:
   ```cmake
   message(WARNING "Could not get package version.")
   set(OPUS_PACKAGE_VERSION 0)
   set(OPUS_PROJECT_VERSION 0)
   ```
   This emits a CMake warning on every configure and writes `#define PACKAGE_VERSION "0"` into `config.h`, causing `opus_get_version_string()` to return `"libopus 0"`.
2. **Slow Configure-Time `try_compile` Probe Storm**:
   [`opus/cmake/OpusConfig.cmake`](../../libraries/humla/src/main/jni/opus/cmake/OpusConfig.cmake) and [`opus/cmake/OpusFunctions.cmake`](../../libraries/humla/src/main/jni/opus/cmake/OpusFunctions.cmake) execute ~20 `c_feature_check`, `check_include_file`, `check_symbol_exists`, `check_library_exists`, and `check_c_compiler_flag` probes (`VLA`, `alloca.h`, `alloca`, `floor` in `libm`, `lrintf`, `lrint`, `elf_aux_info`, `CPU_INFO_BY_ASM`, `cpuid.h`, `CPU_INFO_BY_C`, `xmmintrin.h`, `emmintrin.h`, `smmintrin.h`, `immintrin.h`, `arm_neon.h`, `-ffast-math`, `-fstack-protector-strong`, `-fvisibility=hidden`, `-Wall`, `-W`, `-Wstrict-prototypes`, `-Wextra`, `-Wcast-align`, `-Wnested-externs`, `-Wshadow`). Across 3 Android ABIs plus the host build, that adds **~80 slow `try_compile` compiler invocations** during CMake configure for platform properties that are already known statically.
3. **Divergent Compile Definitions and Source Lists**:
   - Upstream `opus/CMakeLists.txt` defines `-DHAVE_CONFIG_H`, `-DENABLE_HARDENING` (`OPUS_HARDENING=ON`), and `-DDISABLE_DEBUG_FLOAT` by default, and adds directory-scoped warning flags (`-Wstrict-prototypes`, `-Wcast-align`, `-Wnested-externs`, `-Wshadow`).
   - Unless `OPUS_DISABLE_INTRINSICS=ON` is forced, upstream `opus/CMakeLists.txt` compiles x86 RTCD + SSE/SSE2/SSE4.1/AVX2 (`-msse4.1`, `-mavx2 -mfma -mavx`) and ARM NEON intrinsics (`celt_neon_intr.c`, `pitch_neon_intr.c`, `biquad_alt_neon_intr.c`, `LPC_inv_pred_gain_neon_intr.c`, `NSQ_del_dec_neon_intr.c`, `NSQ_neon.c`, `warped_autocorrelation_FIX_neon_intr.c`), diverging from the audited source list and binary footprint in [`Android.mk`](../../libraries/humla/src/main/jni/Android.mk) and [`scripts/test_native_audio.sh`](../../scripts/test_native_audio.sh).
4. **Incidentally Audited Dead ARM RTCD Files in Current `Android.mk`**:
   In [`Android.mk:33-39`](../../libraries/humla/src/main/jni/Android.mk#L33-L39):
   ```makefile
   ifeq ($(TARGET_ARCH), arm)
   CELT_SOURCES += $(CELT_SOURCES_ARM_RTCD)
   SILK_SOURCES += $(SILK_SOURCES_ARM_RTCD)
   endif
   ```
   Inspection of [`opus/celt/arm/armcpu.c:31`](../../libraries/humla/src/main/jni/opus/celt/arm/armcpu.c#L31), [`opus/celt/arm/arm_celt_map.c:34`](../../libraries/humla/src/main/jni/opus/celt/arm/arm_celt_map.c#L34), and [`opus/silk/arm/arm_silk_map.c`](../../libraries/humla/src/main/jni/opus/silk/arm/arm_silk_map.c) shows that the entire contents of all three files are guarded by `#if defined(OPUS_HAVE_RTCD)`. Because `HUMLA_OPUS_CFLAGS` does not define `OPUS_HAVE_RTCD`, those three files compile to empty translation units (`0 B` of code and data) on `armeabi-v7a`.

### Recommended Opus Integration: Direct `.mk` Source Parsing
Even upstream [`opus/cmake/OpusSources.cmake`](../../libraries/humla/src/main/jni/opus/cmake/OpusSources.cmake) does not hardcode source lists—it parses `celt_sources.mk`, `silk_sources.mk`, and `opus_sources.mk` via `file(STRINGS ...)`.

In CMake, `file(STRINGS <file> <out>)` automatically joins backslash-continued lines (`\`). A 15-line helper function in our own `CMakeLists.txt` reads `CELT_SOURCES`, `SILK_SOURCES`, `SILK_SOURCES_FIXED`, `OPUS_SOURCES`, and `OPUS_SOURCES_FLOAT` directly from the submodule's `.mk` files in **< 1 ms** with **zero `try_compile` overhead**, guaranteeing that:
- `opus/{celt,silk,opus}_sources.mk` remains the single source of truth across submodule updates.
- Both Android (`libhumlaaudio.so`) and Host (`test_audio_engine`) compile the exact same `humlaopus` target with the exact same fixed-point configuration (`-DOPUS_BUILD -DVAR_ARRAYS -DFIXED_POINT -DHAVE_LRINTF=1`).

---

## 2.2 RNNoise Submodule Analysis & Flag/Header Scoping

The `rnnoise` submodule ([`libraries/humla/src/main/jni/rnnoise`](../../libraries/humla/src/main/jni/rnnoise)) uses Autotools ([`Makefile.am`](../../libraries/humla/src/main/jni/rnnoise/Makefile.am), [`configure.ac`](../../libraries/humla/src/main/jni/rnnoise/configure.ac)) and has no upstream `CMakeLists.txt`. Its pre-generated configuration header lives at [`libraries/humla/src/main/jni/rnnoise-build/config.h`](../../libraries/humla/src/main/jni/rnnoise-build/config.h), and its model source files (`rnnoise_data.c`, `rnnoise_data.h`) are unpacked into `libraries/humla/src/main/jni/rnnoise-build/generated/` by the Gradle `prepareRnnoiseModel` task.

### Critical Isolation Invariant 1: `-DUSE_WEIGHTS_FILE` Flag Scoping
As documented in [`Android.mk:56-63`](../../libraries/humla/src/main/jni/Android.mk#L56-L63) and [`docs/apk-size-analysis/README-0.26.1.md §3`](../apk-size-analysis/README-0.26.1.md#3-deep-dive-asset-storage--rnnoise-model-payload):
- `humlarnnoise` **must** be compiled with `-DUSE_WEIGHTS_FILE` so the 3.5 MB static weight arrays in `rnnoise_data.c` are excluded by `#ifndef USE_WEIGHTS_FILE` (saving ~10.14 MiB uncompressed / > 7 MB compressed across the 3 ABIs; weights are loaded once from `assets/rnnoise_model.bin` at runtime via `rnnoise_model_from_buffer`).
- `humlaopus` **must never** see `-DUSE_WEIGHTS_FILE`: in Opus 1.6.1, `#ifdef USE_WEIGHTS_FILE` inside `opus_decoder.c` and `opus_encoder.c` enables the `OPUS_SET_DNN_BLOB` control handler calling `lpcnet_plc_load_model` and `silk_LoadOSCEModels`, whose `dnn/` sources (`lpcnet_sources.mk`) are intentionally not compiled.

In CMake, `target_compile_options(humlarnnoise PRIVATE -DHAVE_CONFIG_H -DUSE_WEIGHTS_FILE ...)` scopes these flags strictly to `humlarnnoise`'s 10 translation units.

### Critical Isolation Invariant 2: Internal Header Name Collisions (`opus/celt` vs `rnnoise/src`)
Because RNNoise was forked from CELT/Opus by the same author (Jean-Marc Valin), `opus/celt/` and `rnnoise/src/` contain internal headers with **identical filenames**:

| Colliding Header Filename | In `opus/` | In `rnnoise/` |
| :--- | :--- | :--- |
| `arch.h` | `opus/celt/arch.h` | `rnnoise/src/arch.h` |
| `common.h` | `opus/dnn/common.h` | `rnnoise/src/common.h` |
| `pitch.h` | `opus/celt/pitch.h` | `rnnoise/src/pitch.h` |
| `celt_lpc.h` | `opus/celt/celt_lpc.h` | `rnnoise/src/celt_lpc.h` |
| `kiss_fft.h` | `opus/celt/kiss_fft.h` | `rnnoise/src/kiss_fft.h` |
| `_kiss_fft_guts.h` | `opus/celt/_kiss_fft_guts.h` | `rnnoise/src/_kiss_fft_guts.h` |
| `cpu_support.h` | `opus/celt/cpu_support.h` | `rnnoise/src/cpu_support.h` |
| `opus_types.h` | `opus/include/opus_types.h` | `rnnoise/src/opus_types.h` |
| `x86/x86cpu.h` | `opus/celt/x86/x86cpu.h` | `rnnoise/src/x86/x86cpu.h` |

In [`Android.mk:101-102`](../../libraries/humla/src/main/jni/Android.mk#L101-L102), `humlaaudio` had to place both `$(HUMLA_OPUS_C_INCLUDES)` and `$(HUMLA_RNNOISE_C_INCLUDES)` (including `opus/celt` and `rnnoise/src`) on its own include path.

In CMake, `PUBLIC` vs `PRIVATE` `target_include_directories` enforces clean module boundaries:
- `humlaopus` exposes only `PUBLIC "${OPUS_DIR}/include"` (`<opus.h>`) and keeps `celt`, `silk`, `silk/float`, and `silk/fixed` `PRIVATE`.
- `humlarnnoise` exposes only `PUBLIC "${RNNOISE_DIR}/include"` (`<rnnoise.h>`) and keeps `rnnoise/src`, `rnnoise-build`, and `rnnoise-build/generated` `PRIVATE`.
- `humlaaudio` links `humlaopus` and `humlarnnoise` and never has `opus/celt` or `rnnoise/src` on its include path.

*(Note: At link time, `humlaopus` and `humlarnnoise` have zero global symbol collisions because RNNoise prefixes its internal functions with `rnn_*`, e.g. `rnn_lpc`, `rnn_autocorr`, `rnn_pitch_downsample`, `rnn_fft`—verified via `llvm-nm --defined-only -g` across both archives.)*

---

## 2.3 Complete Compiler & Linker Flag Parity Matrix (`ndk-build` vs NDK 27 CMake)

Inspecting the exact commands emitted by `ndk-build -B -n V=1` against NDK 27.2.12479018's `build/cmake/android.toolchain.cmake` (which delegates to `android-legacy.toolchain.cmake`) reveals several critical flag differences where CMake's defaults do **not** match `ndk-build`. Every difference below must be accounted for in `CMakeLists.txt`:

| Aspect | `ndk-build` (`Android.mk` + `Application.mk`) | NDK 27 `android.toolchain.cmake` Default | Required Setting in `CMakeLists.txt` / `build.gradle` |
| :--- | :--- | :--- | :--- |
| **Target API Level (`ANDROID_PLATFORM`)** | `APP_PLATFORM := android-21` (hardcoded in `Application.mk` before `minSdkVersion` moved to `30`) | AGP automatically passes `-DANDROID_PLATFORM=android-30` (from `minSdkVersion 30`) | Use AGP's default `android-30` (matches `minSdkVersion 30`; shrinks `.so` by 0.5–1.2 KB per ABI and separates `.rodata` into a non-executable `R` segment—see [§3.2](03-empirical-benchmarks-and-verification.md#32-elf-program-header--symbol-table-inspection)). |
| **C++ Standard** | `APP_CPPFLAGS := -std=c++17` | `-std=c++14` (Clang default unless set) | `set(CMAKE_CXX_STANDARD 17)`, `set(CMAKE_CXX_STANDARD_REQUIRED ON)`, `set(CMAKE_CXX_EXTENSIONS OFF)` |
| **C++ STL** | `APP_STL := c++_static` (`-static-libstdc++`) | `c++_static` (default in toolchain, but AGP recommends explicit) | Pass `arguments "-DANDROID_STL=c++_static"` in `build.gradle`. |
| **Optimization in `Debug` vs `Release`** | `Application.mk` comments out `#APP_OPTIM := debug`, so `ndk-build` **always** builds Release (`-O2`/`-Oz` + target `-O3 -DNDEBUG`). | AGP passes `CMAKE_BUILD_TYPE=Debug` (`-O0`, no `-DNDEBUG`) for `debug` variants and `CMAKE_BUILD_TYPE=RelWithDebInfo` (**`-O2 -g -DNDEBUG`**, *not* `-O3`!) for `release` variants. | Explicitly pass `-O3 -DNDEBUG` in `target_compile_options` for `humlaopus`, `humlarnnoise`, and `humlaaudio` on `ANDROID` so real-time audio DSP always compiles at `-O3` in both debug and release APKs. |
| **C++ Exceptions & RTTI** | `ndk-build` defaults to `-fno-exceptions -fno-rtti`; `LOCAL_CPP_FEATURES := exceptions` adds `-fexceptions` (keeping `-fno-rtti`). | NDK CMake leaves **both** exceptions and RTTI enabled by default. | Pass `$<$<COMPILE_LANGUAGE:CXX>:-fexceptions -fno-rtti>` on `humlaaudio` so RTTI typeinfo tables/strings are not emitted for internal C++ classes. |
| **Symbol Visibility & Section Splitting** | `COMMON_CFLAGS` passes `-ffunction-sections -fdata-sections -fvisibility=hidden`. | NDK CMake passes `-ffunction-sections -fdata-sections` but **does not** pass `-fvisibility=hidden`. | Explicitly pass `-ffunction-sections -fdata-sections -fvisibility=hidden` on all three targets. |
| **Dead-Code Elimination & Static Lib Symbol Hiding** | `COMMON_LDFLAGS` passes `-Wl,--gc-sections -Wl,--exclude-libs,ALL`. | NDK CMake omits `-Wl,--gc-sections` in `Debug` builds and **never** passes `-Wl,--exclude-libs,ALL`. | Explicitly pass `-Wl,--gc-sections -Wl,--exclude-libs,ALL` in `target_link_options(humlaaudio PRIVATE ...)`. |
| **16 KiB Page Alignment** | `COMMON_LDFLAGS` passes `-Wl,-z,max-page-size=16384` on all ABIs. | Only added if `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES` is set, and only on `arm64-v8a` / `x86_64`. | Explicitly pass `-Wl,-z,max-page-size=16384` in `target_link_options(humlaaudio PRIVATE ...)`. |
| **Stack Protector, `_FORTIFY_SOURCE`, RELRO/NOW** | `COMMON_CFLAGS` / `COMMON_LDFLAGS` pass `-fstack-protector-strong -D_FORTIFY_SOURCE=2 -Wl,-z,relro,-z,now`. | Toolchain passes `-fstack-protector-strong -D_FORTIFY_SOURCE=2`; LLD defaults to `relro,now` on Android. | Explicitly keep `-fstack-protector-strong -D_FORTIFY_SOURCE=2` in compile flags and `-Wl,-z,relro,-z,now` in link options for defense-in-depth parity with commit `1e0b19c6`. |
| **ARMv7 NEON (`armeabi-v7a`)** | `clang -target armv7-none-linux-androideabi21 -mthumb` enables `__ARM_NEON` by default in NDK 27 Clang. | `android-legacy.toolchain.cmake` sets `ANDROID_ARM_NEON=TRUE` and `-march=armv7-a -mthumb`; `__ARM_NEON` is identical. | No extra flags needed; verified via `clang -dM -E` that `__ARM_NEON 1` and `__ARM_NEON_FP 0x4` are identical. |
| **Whole-Archive Linking (`--whole-archive`)** | `LOCAL_WHOLE_STATIC_LIBRARIES := humlaopus humlarnnoise` | Standard `target_link_libraries(humlaaudio PRIVATE humlaopus humlarnnoise log)` | Standard linking without `--whole-archive` produces the **exact same `.so` byte size and symbol table** (verified in [§3.1](03-empirical-benchmarks-and-verification.md#31-android-ndk-binary-size-parity)). |
