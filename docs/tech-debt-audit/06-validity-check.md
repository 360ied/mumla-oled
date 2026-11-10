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
counts and citations corrected. The original Top-10 order stands with
wording tweaks below.

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
| Zero `androidTest/` | Weak — single-probe corroboration, needs a `find` to prove |
| Counts (`catch (ignored)`, TODO, test inventory) | Unverified tallies carried forward; need grep/listing to lock |

## Required wording changes (applied in 01–05 and 07)

- Refresh drifted line pins; use snippet search, not line pins, for the
  DataStore, crash-thread, certificate-race, and native blocks.
- Drop the `ChannelFragment` tint cite; soften volume routing to `3+`.
- Correct the certificate path to `net/`; note both SRV timeouts are 800 ms.
- Correct `getBitrate` wording (returns `0` on failure, not a cached value).
- Soften signing from `will mis-sign` to `unsupported pattern, harden`.
- Soften foreground-service from definite kill to contract gap pending
  app-layer verification.
- Correct `printStackTrace` to 12 verified production plus 1 intentional;
  correct Android.mk to links-plus-prose; correct overlay paths to `service/`.
- Downgrade timing-test P1 to unbounded waits and wall-clock asserts only;
  UDP bounded awaits and the user-remove start-gate are already correct.
- Mark preferences/username/locale, `androidTest/` zero, and tally totals as
  needing a grep/`find` to lock; do not act on unverified lines.

## Missing evidence (bounded)

- No build/run execution: cold-path throw, `hasProperty` runtime behavior,
  pending-exception poisoning, and destroy race are code-path inferences.
- No full-repo grep outputs for `printStackTrace`, `catch (ignored)`, TODO,
  short product strings, or `Android.mk` mentions.
- No directory listings for `androidTest/`, JNI tree, or test-file globs.
- No host-app foreground-service handling, certificate caller passwords, or
  upstream parity re-check beyond background acceptance.
