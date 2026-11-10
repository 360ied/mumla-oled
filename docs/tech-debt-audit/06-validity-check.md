# Validity check — re-verification of Mumla OLED debt findings

Five independent evidence-auditor lanes re-read every P0/P1 claim against
primary sources on `master`. No builds or device runs were executed;
deprecation semantics and crash-path consequences are inference, snippet
existence is evidence. Verdicts: `VALID` (snippet plus interpretation hold),
`PARTIAL` (substance holds with count/citation correction),
`INVALID`/`contradicted` (claim should be dropped or downgraded).

## Summary

Zero P0s overturned. Zero P1s fully overturned. Two P1 sub-claims
contradicted (timing-test hang framing and UDP-await framing); several
counts and citations corrected. The original Top-10 order stood with
wording tweaks below; round 4 (at the end of this file) later re-ranked
severities and reordered the roadmap.

## Per-lane verdicts

### App UI

| Claim | Verdict |
|---|---|
| `ServerConnectTask` deprecated API, no cancel, `applicationContext` mitigation | VALID |
| Certificate-generate task deprecated API plus retained `Activity`, admitted race | VALID (race-comment line drifted ~2–5) |
| `ServerInfoTask` plus 50-thread pool, per-`onResume` rebuild with shutdown | VALID (`currently does, but fragile` is fair) |
| DataStore `runBlocking` on UI thread, interim-stance KDoc | VALID (line drift ~+10) |
| Channel navigation triple-deprecation plus plain `commit()` | VALID (minor drift) |
| Options/context-menu deprecation plus `GridView` | VALID |
| `setTargetFragment`, `startActivityForResult` (with in-tree replacement precedent) | VALID |
| 1 s poll, crash-check thread, image-loader pool, ANR-test gating | VALID (crash-thread lines drifted ~220; ANR concern is judgment on verified snippet) |
| Talk-state duplication plus whisper TODO | VALID (near-duplicate with differing signatures, not byte-identical) |
| Tint snippet | PARTIAL — 3 sites verbatim confirmed; 4th cite against `ChannelFragment` did not verify, drop it |
| Volume routing | PARTIAL — 3+ sites confirmed; count of 4 unverified, soften to `3+` |
| Unlabeled icons plus preview `android:text` | VALID as pattern (exact totals need a grep) |

### Core

| Claim | Verdict |
|---|---|
| Undefined `modelHash` cold-build breakage | VALID (within ±3 lines; 2-word fix confirmed) |
| Silent TCP send death | VALID (both overloads swallow; early return documented silent; debug-only drop log) |
| Certificate hygiene (serial, empty password, key size, validity) | VALID with path correction (`net/`, not `util/`) |
| Missing foreground-service contract | PARTIAL — sampled absence holds; exhaustive grep plus host-app mitigation unverified, reword to contract gap |
| Logging debt, god-class sizes, SRV duplication | VALID with nuances (exact `printStackTrace` census unverified; timeout value same 800 ms in both SRV paths, mechanism differs) |

### Native audio

| Claim | Verdict |
|---|---|
| Input `nativeCreate` missing guards | Supported, High |
| `RNNModel` mirror plus unpinned submodules | Supported, High (Opus pin comment lives in `CMakeLists.txt`, RNNoise pin note in processor header — neither enforced) |
| Destroy race, host-suite blindness, callback hygiene, unchecked `opus_ctl` | Supported, High (`getBitrate` fallback wording overstated robustness; code is weaker than described) |
| Line numbers | Unclear-citation across findings (off ~5–15); substance supported, re-baseline pins |

### Build/infra

| Claim | Verdict |
|---|---|
| Undefined `modelHash` | VALID, High |
| No CI workflows | VALID, High (default-path probe; alternate CI outside default path noted as missing evidence) |
| CMake tri-sync vs NDK single-source | VALID, High |
| Floating Nix toolchain | VALID, High (lock-file nuance already noted) |
| Release-signing `hasProperty` fragility | PARTIAL, Medium — file facts supported; runtime `hasProperty` semantics unverified, harden to `findByName` regardless |
| Non-hermetic `versionName` | VALID, High |

### Cross-cutting

| Claim | Verdict |
|---|---|
| `Android.mk` deleted / `CMakeLists.txt` current; true link breaks | Supported (2 link breaks verified; rest are stale prose, not 404s — correct total to links-plus-prose) |
| `printStackTrace` per-file sites; intentional reporter use excluded | Supported (12 production + 1 intentional = 13 total verified; report claimed 13+1 = 14, one app site unresolved) |
| `Log.e(+e.message)` plus `Log.d`-for-exceptions | Supported with path/nuance corrections (`service/`, not `overlay/`; HotCorner passes the throwable) |
| Base `app_name` plus user-visible short strings; identifier exemption | Supported (core strings verified; preferences/username/locale lines need a grep) |
| `returnDefaultValues` blind spots | Supported |
| Timing examples | Supported as snippets; hang/shard framing contradicted (bounded `await`/`awaitTermination` already correct) |
| Zero `androidTest/` | Supported — `find` over both `src` trees returns no files and neither holds an `androidTest/` dir (landed per README transcript); `testInstrumentationRunner` still declared |
| Counts (`catch (ignored)`, TODO, test inventory) | Unverified tallies carried forward; need grep/listing to lock |

## Required wording changes (applied in 01–05 and 07)

- Refresh drifted line pins; use snippet search, not line pins, for the
  DataStore, crash-thread, certificate-race, and native blocks.
- Drop the `ChannelFragment` tint cite; soften volume routing to `3+`.
- Correct the certificate path to `net/`; note both SRV timeouts are 800 ms.
- Correct `getBitrate` wording (cached `m_bitrate` when encoder null, otherwise unchecked `ctl` output).
- Soften signing from `will mis-sign` to `unsupported pattern, harden`.
- Soften foreground-service from definite kill to contract gap; app-layer
  promotion since verified (`MumlaConnectionNotification` + manifest — landed
  per README transcript; residual states in the README appendix).
- Correct `printStackTrace` to 12 verified production plus 1 intentional;
  correct Android.mk to links-plus-prose; correct overlay paths to `service/`.
- Downgrade timing-test P1 to unbounded waits and wall-clock asserts only;
  UDP bounded awaits and the user-remove start-gate are already correct.
- Mark preferences/username/locale and tally totals (`catch (ignored)`, TODO,
  test inventory) as needing a grep to lock; do not act on unverified lines.
  (`androidTest/` zero and foreground promotion are closed by landed `find`.)

## Missing evidence (bounded)

- No build/run execution: cold-path throw, `hasProperty` runtime behavior,
  pending-exception poisoning, and destroy race are code-path inferences.
- No full-repo grep outputs for `catch (ignored)`, TODO,
  short product strings, or `Android.mk` mentions (`printStackTrace` and
  `androidTest/` landed full-repo greps/listings per README transcript).
- No directory listings for JNI tree or test-file globs (`androidTest/`
  dir absence landed via `find` + listing).
- Host-app foreground-service handling since verified (landed per README
  transcript); certificate caller passwords and upstream parity re-check
  remain beyond background acceptance.

## Round 4: severity and remediation review

Rounds 1–3 checked whether each claim was true. Round 4 checked whether each
severity matched the P0 definition in [README](README.md) and whether each
remediation was feasible and justified, against `master` and upstream
`../mumble`. Evidence lines are in the README evidence transcripts.

| Item | Round-4 verdict | Change |
|---|---|---|
| `modelHash` cold-build break | Confirmed by reading `prepareRnnoiseModel`: lines using `modelHash` sit next to lines using `modelVersionOrDigest`, and no `modelHash` is declared anywhere in the build | Stays P0, roadmap item 1 |
| No CI workflows | Confirmed (`.github/` holds only issue/PR templates) | Moved to roadmap item 2; a cold-cache CI run would have caught the `modelHash` class of break |
| Input `nativeCreate` guards | Facts hold; R8 keeps both listener methods, and a failed `new` aborts on Android | P0 → P1 (cheap parity with the output bridge) |
| `RNNModel` mirror | Facts hold; submodule is gitlink-pinned, so drift needs a deliberate bump. The upstream struct is in `rnnoise/src/denoise.c`, not a header | P0 → P1; `static_assert` gate replaced with a revision/struct-text configure check, or removal of the mirror via an RNNoise fix |
| `AsyncTask` sites | Deprecated, still functional on API 36. Only the certificate-generate task has a real defect (retained `Activity`) | P0 → P1 (certificate task) and P2 (`ServerConnectTask`, ping pool) |
| DataStore `runBlocking` | Reads hit the in-memory cache after first load; writes wait on disk. The Room `io()` bridge runs real SQLite queries on main but was allowlisted | P0 → P1; Room bridge added to the same item; effort raised because `snapshotBlocking`/`updateBlocking` have 18 call sites |
| Certificate hardening | Contradicted: upstream `src/SelfSignedCertificate.cpp` uses serial `1`, RSA-2048, 20-year validity, matching Humla; Mumble keys users by certificate hash; any PKCS#12 password is stored next to the blob | Dropped. Replaced by a P2 decision on `allowBackup="true"` exposing keys in backups |
| `printStackTrace` census | Full grep: 12 active (5 app + 7 core), 1 commented-out, 1 intentional reporter | Round-1 "one app site unresolved" note closed |
| `03` table formatting | Two rows were split across lines, which breaks GFM tables | Rows joined |
| Native destroy race | Convention honored today: `AudioHandler.shutdown()` calls `AudioInput.shutdown()`, which loops `thread.join()` with no timeout, before `NativeAudioInputEngine.destroy()`; `AudioInput.startRecording` bails if a stale capture thread survives its 200 ms join. Latent, not live | Stays P1, roadmap item 7 (release hardening) |
| Roadmap order follow-up | Item 4 bundled cheap input guards with the RNNoise pin, which only guards a deliberate submodule bump; icon labels and the key-backup decision were cheap but ranked last | Input guards stay item 4; RNNoise pin moved into item 7; RNNoise upstream fix, icon labels, and key-backup decision moved to quick-wins; Top-10 → Top-9 |

Not re-checked in round 4: UI duplication counts,
timing-test notes, and build-infra P1/P2 rows other than CI. Their earlier
verdicts stand.
