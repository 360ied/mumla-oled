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

### C1 — H12 target version needs a decision: 1.5.x vs 1.6.x

The plan says "1.5.x", but the vendored submodule
(`libraries/humla/src/main/jni/opus`, pin `65471dd5`, `version.mk:2`
`1.1-beta`) already carries tags through `v1.6.1` (including `v1.5`,
`v1.5.1`, `v1.5.2`, `v1.6`, `v1.6.1`). Decide before rebasing: latest
supported 1.5.x per the plan, or current 1.6.x. Either keeps the
exact-SHA submodule pin (good hygiene, stays). The
`OpusVoiceDecoder.cpp:59-61` phase-inversion comment is gated on this
rebase — re-evaluate `OPUS_SET_PHASE_INVERSION_DISABLED` availability
in the chosen tree before deleting or keeping the comment.

### C2 — H11/M12 deletion is coupled to H12 via `Android.mk`

`humlaaudio` links the Opus codec out of the `jniopus` module
(`libraries/humla/src/main/jni/Android.mk:49-56,94`:
`LOCAL_SHARED_LIBRARIES := jniopus`, load order pinned in
`NativeAudioInputEngine.java:41`,
`NativeAudioOutputEngine.java:44`). Deleting `jniopus.cpp` + the
`jniopus` module without a replacement leaves `OpusVoiceDecoder.cpp`
and `OpusVoiceEncoder.cpp` (`#include <opus.h>`) with no codec to link.
Decide the opus disposition before coding (see P3): fold the
CELT/SILK/Opus sources into `humlaaudio`, or keep a clean `libopus`
module with no JavaCPP payload. H12 and H11/M12 MUST land in one
worktree as a single review unit; two owners here is a guaranteed
broken-link conflict.

### C3 — M11 `model_version` is a filename key, not a digest

`libraries/humla/src/main/jni/rnnoise/model_version` (`5e78411…5a09d`,
65 bytes with trailing newline) selects the tarball name at
`humla/build.gradle:107-113`; nothing verifies the bytes before
`tarTree(tarGz)` at `:119-127`. The fix vendors a real SHA-256 of the
archive (new file alongside `model_version`, e.g. `model_sha256`),
verified in `doLast` before unpack, fail closed. `scripts/worktree.py:99-141`
copies tarballs + generated sources across worktrees — update the copy
path if a new digest file is added, or fresh worktrees cannot verify
offline.

### C4 — M15 is last: toolchain change invalidates native verification

NDK pin is duplicated (`flake.nix:39` + `humla/build.gradle:57`,
`25.1.8937393`, must match); the nix store currently provides only
`25.1`. Bumping to 27 LTS needs a `flake.lock` move (currently
`d6524aa…`) plus an `androidenv.composeAndroidPackages` support check.
Land M15 after the opus/H10 native work so `test_native_audio.sh` and
the NDK build verify once against the final toolchain.

### C5 — H10 fix is a copy of the `nativeRender` pattern, plus Java

`NativeAudioInputEngineJni.cpp:179-182` (`offset + length > arrayLen`,
32-bit) becomes the int64 `end` check already used by
`NativeAudioOutputEngineJni.cpp:197-201`. Mirror `offset >= 0` in
`NativeAudioInputEngine.java:112-115` (native already has it; Java
guards only `length > 0`). Sole caller `AudioHandler.java:420` passes
offset `0` — local bug-class, no remote path; keep the change minimal.

## 2. Prerequisites (close before coding, not during)

- **P1 — capture the RNNoise digest.** No tarball is cached
  (`libraries/humla/build/model_cache/` absent; generated
  `rnnoise-build/generated/rnnoise_data.{c,h}` exists, 15 MB `.c`).
  Download once from
  `https://media.xiph.org/rnnoise/models/rnnoise_data-<hash>.tar.gz`,
  record SHA-256, vendor it in-repo. Fresh worktrees download on first
  build, so the verify-before-`tarTree` path is exercised by deleting
  the cache, not by the steady-state skip at `build.gradle:104-106`.
- **P2 — inspect the target Opus tree's build lists.** `Android.mk:24-47`
  uses 1.1-era `celt_sources.mk` / `silk_sources.mk` / `opus_sources.mk`
  plus `-DVAR_ARRAYS -DFIXED_POINT -DHAVE_LRINTF=1`. Diff these lists
  and flags against the chosen 1.5.x/1.6.x tree before rebasing; the
  source renames alone can break the NDK build.
- **P3 — opus disposition after `jniopus` deletion (C2).** Fold vs
  clean-module decision, including `LOCAL_C_INCLUDES` (`opus/include`,
  `opus/celt`, `opus/silk` at `Android.mk:62`) and the load-order
  comments at `:49-56`. `CryptState.java:43` also loads `jniopus`
  defensively — update or remove with the rest.
- **P4 — NDK 27 availability in nixpkgs androidenv.** Confirm the
  composed SDK offers an NDK 27 LTS revision before editing
  `flake.nix`/`build.gradle`; keep the two pins in sync.
- **P5 — voice-interop + fuzz harness does not exist yet.**
  `scripts/test_native_audio.sh:4-8` is hermetic (`FakeDecoder`, no
  libopus linked). The `decodeFloat` / `packetSampleCount` fuzz and the
  interop regression (`OpusVoiceDecoder.cpp:71-90`) must be built as
  part of slice B, not assumed present.

## 3. Work split — three worktrees, ordered

| Slice | Branch | Findings | Files owned | Tests owned |
|---|---|---|---|---|
| A | `phase3-rnnoise-digest` | M11 | `humla/build.gradle:88-135`, new digest file, `scripts/worktree.py` copy path | digest-mismatch fails closed |
| B | `phase3-opus-native` | H12+H11+M12+H10 | `jni/opus` pin, `Android.mk`, `Application.mk`, `jniopus.cpp`, `humla/build.gradle:39`, `proguard-rules.pro:18-20`, `tools/jnigen.sh`, `tools/javacpp-0.7.jar`, `OpusVoiceDecoder.cpp`, `NativeAudioInputEngineJni.cpp`, `NativeAudioInputEngine.java`, loadLibrary sites | interop regression + `decodeFloat` fuzz (new) |
| C | `phase3-ndk-hardening` | M15 | `flake.nix:39` + `flake.lock`, `humla/build.gradle:57`, `Android.mk:20-21`, `Application.mk:2-4` | NDK build on 27, `test_native_audio.sh` |

Each worktree forks `master`; land order A, B, C (C last per C4).
Slice B is one worktree because H12/H11 share `Android.mk` (C2); H10
rides along (disjoint files, same native review).

### Slice A — RNNoise digest (M11)

- Vendor SHA-256 alongside `model_version`; `doLast` verifies the
  tarball before `tarTree`, fail closed (throw on mismatch, no
  fallback URL). Optionally commit generated `rnnoise_data.c/h`
  instead of fetching — either way the bytes are pinned.
- Update `scripts/worktree.py:99-141` so the digest file travels with
  the tarball/generated-source copy.
- Accept: digest mismatch fails the build; clean-cache build verifies
  then unpacks; `./scripts/check.sh` green.

### Slice B — Opus rebase + jniopus deletion + H10 (H12, H11, M12, H10)

- Rebase the `opus` submodule to the decided version (C1), keep the
  exact-SHA pin; update `version.mk`, reconcile `Android.mk:24-47`
  source lists/flags (P2).
- Delete `jniopus.cpp` (1367 lines, machine-generated, exports only
  `Java_com_googlecode_javacpp_*` — zero `Java_se_lublin_*`; repo grep
  for `javacpp|bytedeco` in Java returns no hits), the `jniopus` module
  stanza, `javacpp:0.7` dep, ProGuard keeps at
  `proguard-rules.pro:18-20`, `tools/jnigen.sh` +
  `tools/javacpp-0.7.jar`; re-home the codec per P3; update the three
  `loadLibrary("jniopus")` sites
  (`NativeAudioInputEngine.java:41`,
  `NativeAudioOutputEngine.java:44`, `CryptState.java:43`).
- H10: int64 `end` check in `nativeProcessFrame` (copy `nativeRender`
  pattern), Java `offset >= 0` guard.
- Accept: no `javacpp` references remain (`grep -rn javacpp` +
  `bytedeco` clean); voice interop regression passes; `decodeFloat`
  fuzz runs; `./scripts/check.sh` green.

### Slice C — NDK + hardening flags (M15)

- NDK 27 LTS in `flake.nix` + `humla/build.gradle` (keep in sync),
  `flake.lock` move; explicit `-fstack-protector-strong
  -D_FORTIFY_SOURCE=2 -Wl,-z,RelRO,-z,Now` in `Android.mk:20-21`
  (`COMMON_CFLAGS`/`COMMON_LDFLAGS`); decide `APP_PLATFORM android-21`
  (`Application.mk:4`) stance (keep vs raise with minSdk 21).
- Accept: full NDK build on 27 for all ABIs
  (`armeabi-v7a arm64-v8a x86_64`); `./scripts/check.sh` green.

## 4. Test matrix

| Case | Expects |
|---|---|
| RNNoise tarball with wrong bytes | build fails closed before unpack |
| Clean-cache build (no `model_cache/`) | downloads, verifies, unpacks |
| Opus voice interop (old ↔ new codec) | decodes, no regression |
| `decodeFloat` / `packetSampleCount` fuzz | no crash / OOB |
| `grep -rn javacpp\|bytedeco` tree-wide | no hits outside this plan doc |
| `nativeProcessFrame` hostile offset+length | dropped, no OOB read |
| NDK 27 full build, all ABIs | `libhumlaaudio` (+ `libopus` if split) links, loads |
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
- No `APP_PLATFORM`/minSdk raise unless slice C decides it; no
  `customtabs` or unrelated dep refreshes (M13/M14 are Phase 4).

## 6. Execution (worktree + commit + merge order)

- Create: `./scripts/worktree.py add phase3-rnnoise-digest`,
  `phase3-opus-native`, `phase3-ndk-hardening` (root stays on
  `master`; stagger `check.sh` runs — nix gradle is heavy in parallel).
- Commits via `scripts/commit.py` with the three-section body; every
  code commit leaves `./scripts/check.sh` green inside its worktree.
- No autonomous merging, pushing, or deletion (per `AGENTS.md`): leave
  branches and worktrees intact and unpushed, report for review.
- Suggested review/merge order: digest, opus-native, NDK last.
