# 3. Empirical Benchmarks & Verification

All measurements in this document were executed in the repository's Nix development environment using the SDK CMake and NDK already provisioned by [`flake.nix`](../../flake.nix):
- **CMake**: `4.1.2` (`$ANDROID_HOME/cmake/4.1.2/bin/cmake`)
- **Ninja**: `1.12.1` (`$ANDROID_HOME/cmake/4.1.2/bin/ninja`)
- **CTest**: `4.1.2` (`$ANDROID_HOME/cmake/4.1.2/bin/ctest`)
- **Android NDK**: `27.2.12479018` (`r27c`, Clang `18.0.3`)
- **Host Compiler**: GCC `16.2.0` (`gcc` / `g++`) with `ccache 4.14.1`

---

## 3.1 Android NDK Binary Size Parity

Building `libhumlaaudio.so` across all three target ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`) with the prototype `CMakeLists.txt` and stripping with `llvm-strip --strip-unneeded` (the exact command used by `ndk-build` and AGP's `strip*DebugSymbols` task) yields:

| ABI | `ndk-build` (`android-21`) | CMake + Ninja (`android-21`, with `--whole-archive`) | CMake + Ninja (`android-21`, standard static link) | Delta vs `ndk-build` (`android-21`) | CMake + Ninja (`android-30`, matching `minSdk 30`) | Delta vs `ndk-build` (`android-30`) |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| **`arm64-v8a`** | `730,632 B` | `730,632 B` | `730,632 B` | **`0 B` (0.00%)** | `730,088 B` | **`−544 B` (−0.07%)** |
| **`armeabi-v7a`** | `507,812 B` | `507,228 B` | `507,228 B` | **`−584 B` (−0.11%)** | `506,592 B` | **`−1,220 B` (−0.24%)** |
| **`x86_64`** | `798,568 B` | `798,568 B` | `798,568 B` | **`0 B` (0.00%)** | `798,016 B` | **`−552 B` (−0.07%)** |
| **Total (3 ABIs)** | **`2,037,012 B`** | **`2,036,428 B`** | **`2,036,428 B`** | **`−584 B`** | **`2,034,696 B`** | **`−2,316 B`** |

### Key Takeaways from the Size Measurements
1. **Exact byte-count parity on 64-bit ABIs at `android-21`**: When targeting `ANDROID_PLATFORM=android-21` (matching `APP_PLATFORM := android-21` in `Application.mk`), `arm64-v8a` (`730,632 B`) and `x86_64` (`798,568 B`) match the `ndk-build` outputs to the **exact byte** (`0 B` delta). On `armeabi-v7a`, CMake's explicit `-march=armv7-a` flag shaves `584 B`.
2. **Zero difference with or without `--whole-archive`**: Linking `humlaopus` and `humlarnnoise` normally (`target_link_libraries(humlaaudio PRIVATE humlaopus humlarnnoise log)`) produces the exact same `730,632 B` / `507,228 B` / `798,568 B` binaries as wrapping them in `-Wl,--whole-archive ... -Wl,--no-whole-archive`.
3. **Net size reduction at `android-30`**: Allowing AGP to pass its default `ANDROID_PLATFORM=android-30` (matching `minSdkVersion 30` in `libraries/humla/build.gradle`) reduces total uncompressed native binary size by `2,316 B` across the 3 ABIs while improving ELF segment protections (see §3.2).

---

## 3.2 ELF Program Header & Symbol Table Inspection

Inspecting `libhumlaaudio.so` (`arm64-v8a`) with `llvm-readelf -d -l -s --dyn-syms` compares the `ndk-build` (`android-21`) binary against the CMake (`android-30`) binary:

### Dynamic Section & Hardening Invariants
Both binaries have identical security hardening, `SONAME`, and `NEEDED` shared libraries:
- `SONAME`: `[libhumlaaudio.so]`
- `NEEDED`: `[liblog.so]`, `[libc.so]`, `[libm.so]`, `[libdl.so]`
- `FLAGS`: `BIND_NOW` / `FLAGS_1`: `NOW` (full immediate symbol binding from `-Wl,-z,now`)
- `GNU_RELRO`: Present (read-only relocation table after startup from `-Wl,-z,relro`)
- `GNU_STACK`: `RW` (`0x0` size, non-executable stack)
- Stack protector: `__stack_chk_fail@LIBC` imported; `_FORTIFY_SOURCE=2` checks (`__memcpy_chk@LIBC`, `__memset_chk@LIBC`) imported.
- Exported dynamic symbols (`.dynsym` defined `GLOBAL DEFAULT` functions): Identical set of **43 exported entrypoints** (`JNI_OnLoad` + 19 `Java_se_lublin_humla_audio_NativeAudioInputEngine_*` + 7 `Java_se_lublin_humla_audio_NativeAudioOutputEngine_*` + 1 `Java_se_lublin_humla_audio_NativeCrashTest_nativeCrash` + 15 `Java_se_lublin_humla_net_CryptState_*`). Zero internal `opus_*` or `rnnoise_*` symbols leak into `.dynsym`.

### ELF Segment Layout Improvement at `android-30` vs `android-21`

In `ndk-build` (`android-21`), NDK passes `-Wl,--no-rosegment` (a workaround for a Bionic loader bug in Android $\le 9$). This merges read-only data (`.rodata`, `.eh_frame`, `.dynsym`, `.gcc_except_table`) into the **executable** `R E` `LOAD` segment:

```text
# ndk-build (android-21): .rodata and .text share a single R E (read + execute) LOAD segment
Program Headers:
  Type           Offset   VirtAddr           PhysAddr           FileSiz  MemSiz   Flg Align
  LOAD           0x000000 0x0000000000000000 0x0000000000000000 0x0adfd0 0x0adfd0 R E 0x4000
  LOAD           0x0adfd0 0x00000000000b1fd0 0x00000000000b1fd0 0x003c68 0x004030 RW  0x4000
  LOAD           0x0b1c38 0x00000000000b9c38 0x00000000000b9c38 0x0000f0 0x000c98 RW  0x4000
```

In CMake (`android-30`, matching `minSdkVersion 30`), `-Wl,--no-rosegment` is dropped, SysV `.hash` is omitted in favor of `.gnu.hash`, and native ELF `PT_TLS` replaces `__emutls`:

```text
# CMake (android-30): .rodata is isolated in a non-executable R segment; all LOAD segments keep 0x4000 (16 KiB) alignment
Program Headers:
  Type           Offset   VirtAddr           PhysAddr           FileSiz  MemSiz   Flg Align
  LOAD           0x000000 0x0000000000000000 0x0000000000000000 0x02b374 0x02b374 R   0x4000
  LOAD           0x02b380 0x000000000002f380 0x000000000002f380 0x082a50 0x082a50 R E 0x4000
  LOAD           0x0addd0 0x00000000000b5dd0 0x00000000000b5dd0 0x003c80 0x004230 RW  0x4000
  LOAD           0x0b1a50 0x00000000000bda50 0x00000000000bda50 0x0000b0 0x000c50 RW  0x4000
  TLS            0x0addd0 0x00000000000b1dd0 0x00000000000b1dd0 0x000000 0x000019 R   0x8
  GNU_RELRO      0x0addd0 0x00000000000b5dd0 0x00000000000b5dd0 0x003c80 0x004230 R   0x1
```

This reduces the executable (`PROT_EXEC`) memory mapping by `177 KiB` (`0x2b374` bytes of lookup tables and constants are mapped `R` instead of `R E`) while maintaining strict 16 KiB page alignment (`0x4000`) across all four `LOAD` segments.

---

## 3.3 AGP 9.0.1 Task-Graph Verification (`--dry-run`)

Configuring `externalNativeBuild.cmake` on `:libraries:humla` and inspecting the Gradle task graph with `--dry-run` across all key workflows confirms that AGP's native task wiring eliminates the need for both the `startParameter.taskNames` regex gate and the `taskGraph.whenReady` guard:

### 1. Fast JVM Unit Tests (`./gradlew testFossDebugUnitTest :libraries:humla:testDebugUnitTest --dry-run`)
```text
:libraries:humla:prepareRnnoiseModel SKIPPED
```
- **Zero** `configureCMake*` or `buildCMake*` tasks enter the task graph.
- Unit-test invocations stay completely decoupled from the 3-ABI Android NDK build.

### 2. Debug APK Assembly (`./gradlew assembleFossDebug --dry-run`)
```text
:app:mergeFossDebugNativeDebugMetadata SKIPPED
:libraries:humla:prepareRnnoiseModel SKIPPED
:libraries:humla:configureCMakeDebug[arm64-v8a] SKIPPED
:libraries:humla:buildCMakeDebug[arm64-v8a] SKIPPED
:libraries:humla:configureCMakeDebug[armeabi-v7a] SKIPPED
:libraries:humla:buildCMakeDebug[armeabi-v7a] SKIPPED
:libraries:humla:configureCMakeDebug[x86_64] SKIPPED
:libraries:humla:buildCMakeDebug[x86_64] SKIPPED
:libraries:humla:mergeDebugNativeLibs SKIPPED
:app:mergeFossDebugNativeLibs SKIPPED
:app:stripFossDebugDebugSymbols SKIPPED
```

### 3. Release AAR / APK Assembly (`./gradlew :libraries:humla:assembleRelease --dry-run`)
```text
:libraries:humla:prepareRnnoiseModel SKIPPED
:libraries:humla:configureCMakeRelWithDebInfo[arm64-v8a] SKIPPED
:libraries:humla:buildCMakeRelWithDebInfo[arm64-v8a] SKIPPED
:libraries:humla:configureCMakeRelWithDebInfo[armeabi-v7a] SKIPPED
:libraries:humla:buildCMakeRelWithDebInfo[armeabi-v7a] SKIPPED
:libraries:humla:configureCMakeRelWithDebInfo[x86_64] SKIPPED
:libraries:humla:buildCMakeRelWithDebInfo[x86_64] SKIPPED
:libraries:humla:mergeReleaseNativeLibs SKIPPED
:libraries:humla:stripReleaseDebugSymbols SKIPPED
```

### 4. Lint & Direct Subtask Invocations (`bundleDebugLocalLintAar` / `mergeDebugNativeLibs --dry-run`)
```text
:libraries:humla:prepareRnnoiseModel SKIPPED
:libraries:humla:configureCMakeDebug[arm64-v8a] SKIPPED
:libraries:humla:buildCMakeDebug[arm64-v8a] SKIPPED
:libraries:humla:configureCMakeDebug[armeabi-v7a] SKIPPED
:libraries:humla:buildCMakeDebug[armeabi-v7a] SKIPPED
:libraries:humla:configureCMakeDebug[x86_64] SKIPPED
:libraries:humla:buildCMakeDebug[x86_64] SKIPPED
:libraries:humla:mergeDebugJniLibFolders SKIPPED
:libraries:humla:mergeDebugNativeLibs SKIPPED
```
*(Note: In direct `:libraries:humla:mergeDebugNativeLibs` invocations where `preBuild` is not in the graph, `prepareRnnoiseModel` is included only when `tasks.matching { it.name.startsWith("configureCMake") }.configureEach { dependsOn(tasks.named("prepareRnnoiseModel")) }` is wired—see [04-migration-blueprint-and-pitfalls.md §1 Pitfall 3](04-migration-blueprint-and-pitfalls.md#pitfall-3-preparernnoisemodel-must-run-before-configurecmake).)*

---

## 3.4 Host C++ Test & Host JNI Build Timings

Running the host build (`humlaopus`, `test_audio_engine`, `test_crypt_state`, and host `libhumlaaudio.so`) through CMake `4.1.2` + Ninja `1.12.1` + CTest `4.1.2`:

| Phase | Measured Wall Time | Measured User / Sys Time | Notes |
| :--- | ---: | ---: | :--- |
| **Cold CMake configure (`cmake -G Ninja`)** | `1.017 s` | `0.563 s` user / `0.329 s` sys | Detects `gcc`/`g++`, `Threads`, `JNI`, parses Opus `.mk` lists, writes `build.ninja`. |
| **Cold compile + link (158 TUs, `ccache` cold)** | `17.951 s` | `107.925 s` user / `12.615 s` sys | Compiles all 3 targets in a single parallel Ninja DAG. |
| **Warm `ccache` compile + link (fresh build dir)** | **`0.333 s`** | `0.990 s` user / `0.624 s` sys | Simulates a brand-new worktree building all 158 TUs + 4 links from the shared `ccache`. |
| **Incremental no-op build (`ninja`)** | **`0.005 s`** | `0.002 s` user / `0.003 s` sys | `ninja: no work to do.` (5 milliseconds). |
| **Test execution (`ctest --output-on-failure`)** | **`0.350 s`** | `0.343 s` user / `0.015 s` sys | `test_audio_engine` (`0.34 s`) + `test_crypt_state` (`0.00 s`), 100% pass. |

---

## 3.5 Cross-Worktree `ccache` Hit-Rate Verification

To verify the fix for the 81.67% `ccache` miss rate diagnosed in [01-current-architecture-and-pain-points.md §1.4](01-current-architecture-and-pain-points.md#14-broken-cross-worktree-ccache-sharing-8167-miss-rate), two separate worktree directories (`wt1` and `wt2`) were compiled against a clean `CCACHE_DIR` with `-g -O2` and absolute `-I` and `-c` paths:

| Configuration | `wt1` (First Worktree) | `wt2` (Second Worktree) | Cross-Worktree Hit Rate |
| :--- | :--- | :--- | ---: |
| **Current `flake.nix` (`CCACHE_DIR` only)** | `0 hits / 1 miss` | `0 hits / 1 miss` | **`0.00%`** |
| **With `CCACHE_BASEDIR="$PWD"` + `CCACHE_NOHASHDIR=1`** | `0 hits / 1 miss` | `1 hit / 0 misses` | **`100.00%`** |

Exporting `CCACHE_BASEDIR="${PWD}"` and `CCACHE_NOHASHDIR=1` in [`flake.nix`](../../flake.nix)'s `shellHook` makes `ccache` rewrite all absolute paths inside the active worktree to relative paths before computing the manifest/direct hash, enabling instant (`~0.33 s` host, `~1.0 s` 3-ABI Android) cache hits in newly created worktrees.
