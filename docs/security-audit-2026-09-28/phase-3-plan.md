# Phase 3 implementation plan — supply chain + native (M11, H12, H11, M12, H10, M15)

Companion to [remediation-plan.md](remediation-plan.md) Phase 3 and
[findings.md](findings.md) (M11, H12, H11, M12, H10, M15). Incorporates a
pre-implementation review of the tree: five corrections to the
remediation plan as written, prerequisites to close before coding, then
the work split (three worktrees, ordered). Implementation itself MUST
happen in dedicated worktrees (`./scripts/worktree.py add <branch>`);
this file is docs-only on `master`.

Threat model: build-time code injection first (mirror/MITM ships code
into `libhumlaaudio`), then dead-code removal and local hardening.

## 0. User-visible changes (UX contract — read first)

None. No UI, no strings, no behavior change on the happy path:

- Voice interop unchanged (same Opus wire format across the rebase).
- First build after M11 downloads the model archive once, then verifies
  before unpack; digest mismatch fails closed with a build error.
- NDK bump changes nothing observable except the toolchain version in
  the build log.

## 1. Corrections to remediation-plan.md (read first)

### C1 — H12 target decided: v1.6.1 (`22244de5`)

Latest stable is v1.6.1 (commit
`22244de5a79bd1d6d623c32e72bf1954b56235be`, supersedes v1.6). Rebase
the `opus` submodule (`libraries/humla/src/main/jni/opus`, current
pin `65471dd5`) to that SHA and keep the exact-SHA pin. Note: 1.6.x
has no `version.mk` (version comes from the `update_version`-generated
`package_version`, which our `Android.mk` flow never runs) — there is no
version file to edit; record tag + SHA in the commit message instead.
`OPUS_SET_PHASE_INVERSION_DISABLED` exists in 1.6.1
(`include/opus_defines.h:757`, handled in `celt/celt_{en,de}coder.c`);
it concerns stereo phase inversion and our output is mono
(`OpusVoiceDecoder.h:42`), so slice B deletes the stale
`OpusVoiceDecoder.cpp:59-61` comment and leaves the default alone.

### C2 — H11/M12 deletion is coupled to H12 via `Android.mk` (decided: fold into `humlaaudio`)

`humlaaudio` links the Opus codec out of the `jniopus` module
(`libraries/humla/src/main/jni/Android.mk:49-56,94`:
`LOCAL_SHARED_LIBRARIES := jniopus`, load order pinned in
`NativeAudioInputEngine.java:41`,
`NativeAudioOutputEngine.java:44`). Deleting `jniopus.cpp` + the
`jniopus` module without a replacement leaves `OpusVoiceDecoder.cpp`
and `OpusVoiceEncoder.cpp` (`#include <opus.h>`) with no codec to link.
Decided: fold the CELT/SILK/Opus sources straight into `humlaaudio`
(one library, no new module) — only two files include `opus.h`, the
`jniopus.cpp` exports are 100% JavaCPP glue with zero app callers, and
a separate `libopus` module re-creates the load-order headache. Slice B
deletes the module stanza, adds the source lists to `humlaaudio`, drops
the `LOCAL_SHARED_LIBRARIES` line and the three
`loadLibrary("jniopus")` calls. H12 and H11/M12 MUST land in one
worktree as a single review unit; two owners here is a guaranteed
broken-link conflict.

### C3 — M11: the filename key already IS the digest — just check it

`libraries/humla/src/main/jni/rnnoise/model_version` (`5e78411…5a09d`,
65 bytes with trailing newline) selects the tarball name at
`humla/build.gradle:107-113`; nothing verifies the bytes before
`tarTree(tarGz)` at `:119-127`. Fresh download confirms the file hashes
to exactly that string — no new value to vendor, the fix only adds the
check. The digest file must live in our tree (e.g.
`libraries/humla/model_sha256`), NOT alongside `model_version` (that
file is inside the rnnoise submodule, pin `d983458`). Verified in
`doLast` before unpack, fail closed. `scripts/worktree.py:99-141`
copies tarballs + generated sources across worktrees — update the copy
path if a new digest file is added, or fresh worktrees cannot verify
offline.

### C4 — M15 is last: toolchain change invalidates native verification

NDK pin is duplicated (`flake.nix:39` + `humla/build.gradle:57`,
`25.1.8937393`, must match). NDK `27.2.12479018` (r27c) composes on the
current `flake.lock` (`d6524aa`) — no lock move needed. Land M15 after
the opus/H10 native work so `test_native_audio.sh` and the NDK build
verify once against the final toolchain.

### C5 — H10 fix is a copy of the `nativeRender` pattern, plus Java

`NativeAudioInputEngineJni.cpp:179-182` (`offset + length > arrayLen`,
32-bit) becomes the int64 `end` check already used by
`NativeAudioOutputEngineJni.cpp:197-201`. Mirror `offset >= 0` in
`NativeAudioInputEngine.java:112-115` (native already has it; Java
guards only `length > 0`). Sole caller `AudioHandler.java:420` passes
offset `0` — local bug-class, no remote path; keep the change minimal.

## 2. Prerequisites (close before coding, not during)

- **P1 — RNNoise digest captured (closed 2026-09-29).** Fresh download
  of `rnnoise_data-5e78411….tar.gz` hashes to
  `5e7841199cf2947fc32f6eeaf6faffab5c2dd3da69e6a6e4830625a10285a09d`
  — byte-identical to `model_version`. The filename key already IS the
  SHA-256; the fix only adds the check, no new value to vendor. Wrinkle:
  `model_version` lives inside the rnnoise submodule (pin `d983458`;
  upstream HEAD has moved to `70f1d25`), so the digest file must live
  in our tree (e.g. `libraries/humla/model_sha256`), not next to
  `model_version`. Verify the whole `.tar.gz` before `tarTree`
  (archive holds 6 files, we unpack 2). Decided: keep download+verify,
  do NOT commit the 15 MB generated `.c` — smaller repo, and the check
  runs every clean build. Exercise the verify path by deleting
  `build/model_cache/`, not via the steady-state skip at
  `build.gradle:104-106`.
- **P2 — target Opus tree build lists inspected (closed 2026-09-29).**
  1.6.1 keeps the same `.mk` structure (`celt_sources.mk` /
  `silk_sources.mk` / `opus_sources.mk`), so `Android.mk:24-47` needs
  edits, not a rewrite. Deltas: new `OPUS_SOURCES` entries
  (`extensions.c`, `opus_projection_{encoder,decoder}.c`,
  `mapping_matrix.c`); CELT arch vars renamed (old `CELT_SOURCES_ARM`
  is now `CELT_SOURCES_ARM_RTCD` + NEON variants — the
  `Android.mk:32-35` ARM block must be updated); SILK gained
  arch-specific fixed/float variants. New `lpcnet_sources.mk` (`dnn/`,
  deep PLC + DRED) is opt-in, defaults off, and conflicts with
  fixed-point — do NOT include it. Flags `-DVAR_ARRAYS -DFIXED_POINT
  -DHAVE_LRINTF=1` still exist upstream; keep them with
  `SILK_SOURCES_FIXED` + `OPUS_SOURCES_FLOAT` as today. Every Opus API
  we call (create/destroy/encode/decode/ctls, VBR, complexity, FEC,
  DTX, bitrate, reset) is present in 1.6.1 — no encoder/decoder code
  changes needed; wire format unchanged (48 kHz mono), interop safe.
- **P3 — opus disposition decided (closed 2026-09-29, see C2).** Fold
  into `humlaaudio`. Keep `LOCAL_C_INCLUDES` (`opus/include`,
  `opus/celt`, `opus/silk` at `Android.mk:62`); update the load-order
  comments at `:49-56`; remove the defensive `jniopus` load in
  `CryptState.java:43` with the other two sites.
- **P4 — NDK 27 composes on current lock (closed 2026-09-29).** NDK
  `27.2.12479018` (r27c) resolves via `composeAndroidPackages` on the
  current `flake.lock` (`d6524aa`) — NO lock move needed (corrects the
  earlier "needs lock bump" claim). Pin that revision in `flake.nix:39`
  + `humla/build.gradle:57`, keep the two in sync. Still slice C's call:
  `APP_PLATFORM android-21` keep-vs-raise, and flag placement in
  `Android.mk:20-21`.
- **P5 — voice-interop + fuzz harness shape (closed 2026-09-29).**
  `scripts/test_native_audio.sh:4-8` is hermetic (`FakeDecoder`, no
  libopus linked) — confirmed gap. Slice B adds a host test linking
  the real 1.6.1 sources: encode→decode round trip (interop), then
  fuzz `decodeFloat` / `packetSampleCount`
  (`OpusVoiceDecoder.cpp:71-90`) with garbage/truncated/oversized
  packets asserting no-crash + sane returns. `AudioOutputEngine`
  already drops `span <= 0` (`AudioOutputEngine.cpp:415-418`), so the
  fuzz asserts that contract holds.

## 3. Work split — three worktrees, ordered

| Slice | Branch | Findings | Files owned | Tests owned |
|---|---|---|---|---|
| A | `phase3-rnnoise-digest` | M11 | `humla/build.gradle:88-135`, new digest file, `scripts/worktree.py` copy path | digest-mismatch fails closed |
| B | `phase3-opus-native` | H12+H11+M12+H10 | `jni/opus` pin, `Android.mk`, `jniopus.cpp`, `humla/build.gradle:39`, `app/proguard-rules.pro:18-20`, `app/build.gradle:130` javacpp exclude, `libraries/humla/tools/jnigen.sh`, `libraries/humla/tools/javacpp-0.7.jar`, `OpusVoiceDecoder.cpp`, `NativeAudioInputEngineJni.cpp`, `NativeAudioInputEngine.java`, loadLibrary sites | interop regression + `decodeFloat` fuzz (new) |
| C | `phase3-ndk-hardening` | M15 | `flake.nix:39`, `humla/build.gradle:57`, `Android.mk:20-21`, `Application.mk:2-4` | NDK build on 27, `test_native_audio.sh` |

Each worktree forks `master`; land order A, B, C (C last per C4).
Slice B is one worktree because H12/H11 share `Android.mk` (C2); H10
rides along (disjoint files, same native review).

### Slice A — RNNoise digest (M11)

- Vendor `libraries/humla/model_sha256` containing
  `5e7841199cf2947fc32f6eeaf6faffab5c2dd3da69e6a6e4830625a10285a09d`
  (P1: identical to `model_version`, kept as a separate in-tree file
  because `model_version` lives in the submodule); `doLast` verifies the
  whole tarball before `tarTree`, fail closed (throw on mismatch, no
  fallback URL). Keep download+verify, do NOT commit generated sources.
- Update `scripts/worktree.py:99-141` so the digest file travels with
  the tarball/generated-source copy.
- Accept: digest mismatch fails the build; clean-cache build verifies
  then unpacks; `./scripts/check.sh` green.

### Slice B — Opus rebase + jniopus deletion + H10 (H12, H11, M12, H10)

- Rebase the `opus` submodule to v1.6.1 `22244de5` (C1), keep the
  exact-SHA pin; no version file to update (1.6.x drops `version.mk`,
  C1) — record tag + SHA in the commit message. Fetch the 1.6.1 tree
  first and confirm the P2 deltas before editing `Android.mk:24-47`:
  add the new `OPUS_SOURCES` entries, update the `CELT_SOURCES_ARM`
  block to the RTCD/NEON vars, skip `lpcnet_sources.mk`, keep
  `-DVAR_ARRAYS -DFIXED_POINT -DHAVE_LRINTF=1` with
  `SILK_SOURCES_FIXED` + `OPUS_SOURCES_FLOAT`. Delete the stale
  phase-inversion comment (`OpusVoiceDecoder.cpp:59-61`), default stays.
- Delete `jniopus.cpp` (1367 lines, machine-generated, exports only
  `Java_com_googlecode_javacpp_*` + `Java_se_lublin_humla_audio_javacpp_Opus_*`
  glue — no live `Java_se_lublin_*` codec entry points; repo grep
  for `javacpp|bytedeco` in Java returns no hits and no `**/javacpp/**`
  Java package exists), the `jniopus` module stanza, `javacpp:0.7` dep,
  ProGuard keeps at `app/proguard-rules.pro:18-20`, the javacpp packaging
  exclude at `app/build.gradle:130`,
  `libraries/humla/tools/jnigen.sh` +
  `libraries/humla/tools/javacpp-0.7.jar`; fold codec sources into
  `humlaaudio` per C2 (drop `LOCAL_SHARED_LIBRARIES`, keep
  `LOCAL_C_INCLUDES`); update the three `loadLibrary("jniopus")` sites
  (`NativeAudioInputEngine.java:41`,
  `NativeAudioOutputEngine.java:44`, `CryptState.java:43`).
- H10: int64 `end` check in `nativeProcessFrame` (copy `nativeRender`
  pattern), Java `offset >= 0` guard.
- Accept: no `javacpp` references remain (`grep -rn javacpp` +
  `bytedeco` clean); voice interop regression passes; `decodeFloat`
  fuzz runs (fixed seed corpus: empty, truncated, oversized, random
  bytes × 10k iterations, deterministic seed); `./scripts/check.sh`
  green.

### Slice C — NDK + hardening flags (M15)

- Pin NDK `27.2.12479018` (r27c, P4: composes on current lock, no lock
  move) in `flake.nix` + `humla/build.gradle` (keep in sync); append
  `-fstack-protector-strong -D_FORTIFY_SOURCE=2` to `COMMON_CFLAGS`
  and `-Wl,-z,RelRO,-z,Now` to `COMMON_LDFLAGS` (`Android.mk:20-21`,
  both modules inherit them). Keep `APP_PLATFORM android-21`
  (`Application.mk:4`) and minSdk 21 — raising breaks old devices for
  zero security gain; revisit only if the NDK 27 build errors force it.
- Accept: full NDK build on 27 for all ABIs
  (`armeabi-v7a arm64-v8a x86_64`); `./scripts/check.sh` green.

## 4. Test matrix

| Case | Expects |
|---|---|
| RNNoise tarball with wrong bytes | build fails closed before unpack |
| Clean-cache build (no `model_cache/`) | downloads, verifies, unpacks |
| Opus voice interop (old ↔ new codec) | decodes, no regression |
| `decodeFloat` / `packetSampleCount` fuzz (10k iters, fixed seed) | no crash / OOB |
| `grep -rn javacpp\|bytedeco` tree-wide | no hits outside this plan doc |
| `nativeProcessFrame` hostile offset+length | dropped, no OOB read |
| NDK 27 full build, all ABIs | single `libhumlaaudio` links, loads |
| Existing suite | `./scripts/check.sh` green from each worktree |

## 5. Residuals and non-goals (explicit, not overlooked)

- M5 DNS TOCTOU-style residual analogue: digest pins the tarball, not
  the mirror — a compromised `media.xiph.org` serving malicious bytes
  with a matching name still fails verification; rotation means a new
  vendored digest.
- Vendored Speex jitter buffer, full Opus CELT/SILK, RNNoise DSP are
  not line-audited (audit notes gap 2) — fuzz + rebase bound the risk,
  they do not close the gap.
- OCB/replay parity vs upstream (`../mumble` checkout) stays open
  (audit notes gap 1); untouched by this phase.
- Secrets at rest (former C1, H6–H8, L1) out of scope per
  [secrets-at-rest-plan.md](secrets-at-rest-plan.md).
- No minSdk raise (kept at 21, slice C); no `customtabs` or unrelated
  dep refreshes (M13/M14 are Phase 4).

## 6. Execution (worktree + commit + merge order)

- Create: `./scripts/worktree.py add phase3-rnnoise-digest`,
  `phase3-opus-native`, `phase3-ndk-hardening` (root stays on
  `master`; stagger `check.sh` runs — nix gradle is heavy in parallel).
- Commits via `scripts/commit.py` with the three-section body; every
  code commit leaves `./scripts/check.sh` green inside its worktree.
- No autonomous merging, pushing, or deletion (per `AGENTS.md`): leave
  branches and worktrees intact and unpushed, report for review.
- Suggested review/merge order: digest, opus-native, NDK last.
- Parallelization (2026-09-29): slice A may run parallel with slice B (disjoint `build.gradle` hunks; land A first so B inherits `model_sha256` + the `worktree.py` copy path). Slice C MUST wait for B: C edits `COMMON_CFLAGS/LDFLAGS` assuming B's folded single-module `Android.mk`, and C's all-ABI link acceptance only proves the final tree. Three-way parallel is unsafe (same-file conflict + invalid premise + gradle contention).
