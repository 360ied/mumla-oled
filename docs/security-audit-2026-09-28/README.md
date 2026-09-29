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
| High | 2 | H1–H2 |
| Medium | 12 | H3–H5, H12, M2–M7, M11, M16 |
| Low | 16 | H9–H11, M1, M8–M10, M12–M15, L2–L6 |
| Info | 2 | I1–I2 |

No trust-all `X509TrustManager`, no cleartext fallback, no JS bridge, no `FileProvider`
surface, no `taskAffinity` hijack found.

## Fix first (exploitability × blast radius)

1. **H1 + M2** — TLS has no hostname verification; TOFU pins are global. Any CA-valid
   cert MITMs voice + password.
   `libraries/humla/src/main/java/se/lublin/humla/net/HumlaSSLSocketFactory.java:49-70,129-137`;
   `libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.java:150-165`.
2. **H2** — server-controlled TCP frame length allocated verbatim (`new byte[len]`),
   type validated after, infinite read timeout.
   `libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.java:180-185`.
3. **H3 + H4 + H5** — exported services + TALK receiver drive call/mic state from
   any installed app (local-only); TALK is a documented Tasker/Automate surface.
   `app/src/main/AndroidManifest.xml:60-64`;
   `libraries/humla/src/main/AndroidManifest.xml:29-35`;
   `app/src/main/java/se/lublin/mumla/service/MumlaService.java:665-667,845-866`.
4. **H12 + M11** — vendored Opus 1.1-beta decoding remote voice; RNNoise weights
   downloaded without digest (build-time code injection).
   `libraries/humla/src/main/jni/opus/version.mk:2`;
   `libraries/humla/build.gradle:88-135`.

Next tranche: SAF-only cert export with re-auth (H9), `char[]`-only passwords (M8/M9),
chat URL allowlist + comment-`WebView` hardening + bounded image/avatar decode
(M3–M7), RNNoise digest pin (M11), refresh jsoup/MiniDNS/NDK (M13–M15), fail-closed
release signing (M16). Full ordering in `notes.md`.
