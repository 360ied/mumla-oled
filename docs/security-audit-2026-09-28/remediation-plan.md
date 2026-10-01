# Remediation plan — security audit 2026-09-28

Companion to [findings.md](findings.md) (2 High, 12 Medium, 16 Low,
2 Info after 2026-09-29 downgrades) and [notes.md](notes.md).
Scope: every surviving finding, phased by exploitability × blast radius.
At-rest secrets (former C1, H6–H8, L1) are out of scope — see
[secrets-at-rest-plan.md](secrets-at-rest-plan.md) ("do not fix").

## Landing status — all phases complete on `master`

- **Phase 1 (H1, H2, M2)** — ✅ Completed (`phase1-tls-framing` merged).
- **Phase 2 (M3–M7, L3, L5)** — ✅ Completed (all four slices merged:
  chat-links, comment-webview, image-pipeline, ping-tts).
- **Phase 3 (M11, H12, H11, M12, H10, M15)** — ✅ Completed (rnnoise-digest,
  opus-native, ndk-hardening merged, in that order).
- **Phase 4 (H3–H5, H9, M1, M8, M9, M16, L2, L4, L6)** — ✅ Completed
  (`phase4-integration` merged; M10 won't-fix, M13/M14 deferred, I2
  non-goal per [phase-4-plan.md](phase-4-plan.md)).

Per-finding table: [findings.md](findings.md#remediation-status-all-phases-landed-on-master).

## Phase 1 — Remote MITM + pre-auth DoS (H1, H2, M2)

Highest value: any-CA-cert network attacker and malicious server.

- H1: verify leaf SAN/CN against `mHost` (IP-literal handling) or set
  endpoint identification (`HTTPS`) post-handshake, fail closed
  (`HumlaSSLSocketFactory.java:49-70,129-137`,
  `HumlaTCP.java:150-165`). Regression test: valid-for-wrong-host cert.
- H2: max-frame cap (~1 MiB), reject negative/overlarge before
  allocating, validate `messageType` first, finite read timeout
  (`HumlaTCP.java:179-185`; `setSoTimeout(0)` at `:165`).
- M2: bind TOFU pins per-host (hostname→fingerprint/SPKI, compare each
  connect), re-verify hostname for pinned entries, stop alias-overwrite
  (`MumlaActivity.java:202-247`, factory `:129-137`).

Verify: wrong-host cert rejected; oversize/negative frame dropped;
cross-host pin rejected; `./scripts/check.sh`.

## Phase 2 — Malicious-server rendering + decode (M3–M7, L3, L5)

One hostile server reaches every client.

- M3: intercept chat link clicks, allowlist http/https only
  (`ChannelChatFragment.java:625-626`).
- M4: harden comment `WebView` — JS/file/content access off, block
  network images or gate on the external-images setting, `WebViewClient`
  opening http(s) externally (`AbstractCommentFragment.java:100,127`).
- M5: when external images enabled, block RFC1918/link-local/loopback,
  cap redirects (`MumbleImageGetter.java:191-192,411-440`).
- M6/M7: two-pass bounded decode (bounds first, dimension cap,
  `inSampleSize` downsample to view/icon size); move data-URI decode off
  UI thread (`MumbleImageGetter.java:398-409,434-441`,
  `AvatarCache.java:104-108`).
- L3: `responsePacket.getLength() >= 24` guard before ping parse
  (`ServerInfoResponse.java:49-60`, `ServerInfoTask.java:52-77`).
- L5: strip/length-cap actor names for TTS, plain stripped text in
  notifications (`MumlaService.java:383-415`,
  `MumlaMessageNotification.java:72-103`).

Verify: scheme/SSRF/decode-bomb cases covered; `./scripts/check.sh`.

## Phase 3 — Supply chain + native (M11, H12, H10, H11, M12, M15)

Build-time code injection first, then dead-code removal and hygiene.

- M11: vendor SHA-256 for the RNNoise archive, verify before `tarTree`,
  fail closed (`humla/build.gradle:88-135`).
- H12: rebase vendored Opus 1.1-beta to latest 1.6.x, keep exact-SHA
  pin, voice-interop regression + `decodeFloat` fuzz
  (`jni/opus/version.mk:2`, `OpusVoiceDecoder.cpp:71-78`).
- H11 + M12: delete `jniopus` shims + `javacpp:0.7` dep and dead
  ProGuard keeps (`jniopus.cpp`, `humla/build.gradle:39`,
  `proguard-rules.pro:18-20`).
- H10: int64 `offset+length` check (copy `nativeRender`) + Java
  `offset >= 0` guard
  (`NativeAudioInputEngineJni.cpp:179-182`,
  `NativeAudioInputEngine.java:112-115`).
- M15: NDK 27 LTS in `flake.nix` + `build.gradle`, explicit
  `-fstack-protector-strong -D_FORTIFY_SOURCE=2 -Wl,-z,RelRO,-z,Now`
  (`Android.mk:20-21`, `APP_PLATFORM android-21`).

Verify: digest mismatch fails closed; Opus interop + fuzz; no javacpp
references remain; `./scripts/check.sh`.

## Phase 4 — Local IPC + hygiene (H3–H5, M9, M16, Low bulk)

> Superseded by [phase-4-plan.md](phase-4-plan.md), which governs on
> conflict (notably H5/TALK stays exported by design, M10 won't-fix,
> M13/M14 deferred, no export confirmation dialog).

Installed-app-only paths and process hygiene. TALK stays exported
(documented Tasker/Automate surface) — permission-guard, never
`NOT_EXPORTED`.

- H3/H4: signature-level permission or `exported=false` on control
  actions and `ACTION_CONNECT`; return error instead of `throw`; reject
  external trust-store paths. H5: signature permission + sender check in
  `onReceive`.
- M16: fail closed when `signingConfigs.release` is missing
  (`app/build.gradle:97-109`).
- H9: SAF-only export, confirmation + warning, filename sanitize
  (`CertificateExportActivity.java:105,143-148,179-190`).
- M8/M9: `char[]` end-to-end, zero after use, no secrets in Bundles.
- M10: `PRIVATE` visibility on the connection channel/builder, redact
  lockscreen content.
- M1/M13/M14: TLS 1.2+ floor, refresh jsoup/MiniDNS.
- L2/L4/L6, I2: try-with-resources + socket close, deep-link host
  prominence, legacy-storage flag removal, `FLAG_SECURE` only if in
  threat model. I1: no action (wire compat).

Verify: third-party control/broadcast attempts denied; release without
signing config fails; `./scripts/check.sh`.
