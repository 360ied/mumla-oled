# Security audit — Mumla OLED — 2026-09-28

Read-only audit of Mumla OLED (`app/` + `libraries/humla/`, JNI, manifests, Gradle build).
No code changed, nothing committed. `plans/` is gitignored.

- [findings.md](findings.md) — deduplicated findings with evidence, impact, fix.
- [notes.md](notes.md) — scope, method, verification log, non-findings, gaps, roadmap.

## Verdict

Real risk concentrates in **at-rest secrets + network identity + IPC + remote-input
DoS**, not exotic bugs. Backup/root reader, any-CA-cert network attacker, malicious
server, or malicious local app each has at least one practical path today.

## Counts (deduplicated)

| Severity | N | IDs |
|---|---|-----|
| Critical | 1 | C1 |
| High | 12 | H1–H12 |
| Medium | 16 | M1–M16 |
| Low | 6 | L1–L6 |
| Info | 2 | I1–I2 |

No trust-all `X509TrustManager`, no cleartext fallback, no JS bridge, no `FileProvider`
surface, no `taskAffinity` hijack found.

## Fix first (exploitability × blast radius)

1. **C1 + H6 + H8** — plaintext secrets + backup exfil. One change covers key theft,
   password theft, backup theft together.
   `app/src/main/java/se/lublin/mumla/db/MumlaSQLiteDatabase.java:42-50,100-111`;
   `app/src/main/AndroidManifest.xml:47-48`.
2. **H1 + M2** — TLS has no hostname verification; TOFU pins are global. Any CA-valid
   cert MITMs voice + password.
   `libraries/humla/src/main/java/se/lublin/humla/net/HumlaSSLSocketFactory.java:49-69,129-137`;
   `libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.java:156-165`.
3. **H3 + H4 + H5** — exported `MumlaService`, exported `HumlaService` CONNECT, exported
   TALK receiver: any app can mute/deafen/disconnect, crash or drive connects, force
   mic on/off.
   `app/src/main/AndroidManifest.xml:60-64`;
   `libraries/humla/src/main/AndroidManifest.xml:29-35`;
   `app/src/main/java/se/lublin/mumla/service/MumlaService.java:665-667,845-866`.
4. **H2** — server-controlled TCP frame length allocated verbatim (`new byte[len]`),
   type validated after, infinite read timeout.
   `libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.java:180-185`.
5. **H10 + H11 + H12** — `nativeProcessFrame` 32-bit `offset+length` overflow,
   `jniopus.cpp` JavaCPP shims `memcpy` with no bounds check, vendored Opus 1.1-beta
   decoding remote voice.
   `libraries/humla/src/main/jni/audio_engine/NativeAudioInputEngineJni.cpp:179-182`;
   `libraries/humla/src/main/jni/jniopus.cpp:407-420`;
   `libraries/humla/src/main/jni/opus/version.mk:2`.

Next tranche: SAF-only cert export with re-auth (H9), `char[]`-only passwords (M8/M9),
chat URL allowlist + comment-`WebView` hardening + bounded image/avatar decode
(M3–M7), RNNoise digest pin (M11), refresh jsoup/MiniDNS/NDK (M13–M15), fail-closed
release signing (M16). Full ordering in `notes.md`.
