# Security audit — Mumla OLED — 2026-09-28

Audit of Mumla OLED (`app/` + `libraries/humla/`, JNI, manifests, Gradle build).

- [findings.md](findings.md) — deduplicated findings with evidence, impact, fix.
- [notes.md](notes.md) — scope, method, verification log, non-findings, gaps, roadmap.

## Verdict

Real risk concentrates in **network identity + IPC + remote-input
DoS**, not exotic bugs. Any-CA-cert network attacker, malicious
server, or malicious local app each has at least one practical path today.
At-rest secrets (former C1, H6–H8, L1) withdrawn as noise — see
[secrets-at-rest-plan.md](secrets-at-rest-plan.md) ("do not fix").

## Counts (deduplicated)

| Severity | N | IDs |
|---|---|-----|
| Critical | 0 | — |
| High | 8 | H1–H5, H9–H12 |
| Medium | 16 | M1–M16 |
| Low | 5 | L2–L6 |
| Info | 2 | I1–I2 |

No trust-all `X509TrustManager`, no cleartext fallback, no JS bridge, no `FileProvider`
surface, no `taskAffinity` hijack found.

## Fix first (exploitability × blast radius)

1. **H1 + M2** — TLS has no hostname verification; TOFU pins are global. Any CA-valid
   cert MITMs voice + password.
   `libraries/humla/src/main/java/se/lublin/humla/net/HumlaSSLSocketFactory.java:49-69,129-137`;
   `libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.java:156-165`.
2. **H3 + H4 + H5** — exported `MumlaService`, exported `HumlaService` CONNECT, exported
   TALK receiver: any app can mute/deafen/disconnect, crash or drive connects, force
   mic on/off.
   `app/src/main/AndroidManifest.xml:60-64`;
   `libraries/humla/src/main/AndroidManifest.xml:29-35`;
   `app/src/main/java/se/lublin/mumla/service/MumlaService.java:665-667,845-866`.
3. **H2** — server-controlled TCP frame length allocated verbatim (`new byte[len]`),
   type validated after, infinite read timeout.
   `libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.java:180-185`.
4. **H10 + H11 + H12** — `nativeProcessFrame` 32-bit `offset+length` overflow,
   `jniopus.cpp` JavaCPP shims `memcpy` with no bounds check, vendored Opus 1.1-beta
   decoding remote voice.
   `libraries/humla/src/main/jni/audio_engine/NativeAudioInputEngineJni.cpp:179-182`;
   `libraries/humla/src/main/jni/jniopus.cpp:407-420`;
   `libraries/humla/src/main/jni/opus/version.mk:2`.

Next tranche: SAF-only cert export with re-auth (H9), `char[]`-only passwords (M8/M9),
chat URL allowlist + comment-`WebView` hardening + bounded image/avatar decode
(M3–M7), RNNoise digest pin (M11), refresh jsoup/MiniDNS/NDK (M13–M15), fail-closed
release signing (M16). Full ordering in `notes.md`.
