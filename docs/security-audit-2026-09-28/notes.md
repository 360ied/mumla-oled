# Notes — security audit 2026-09-28

## Scope

Read-only audit. No code changed, nothing committed, no worktree (audit-only task;
per `AGENTS.md` the worktree mandate covers feature/bugfix development).

| Slice | Areas examined |
|---|---|
| Platform/IPC | both manifests, `MumlaService`/`HumlaService`, TALK receiver, overlay, hot corner, notifications, `mumble://` deep link, search provider, cert export, SDK levels |
| Network/TLS/protocol | `humla/net/` (TCP, UDP, SSLSocketFactory, CertGenerator, CryptState, PacketBuffer), `humla/protocol/`, protobuf, `ServerConnectTask`, `ServerInfoTask/Response`, `MumlaTrustStore`, `jni/crypto/CryptStateOCB2.cpp` |
| Secrets/storage | cert import/export/generate/select, `MumlaCertificateGenerateTask`, auth prefs, `MumlaSQLiteDatabase`/`MumlaDatabase`/`DatabaseCertificate`/`DatabaseProvider`, `Settings`, `MumlaTrustStore`, `HumlaCertificateGenerator`, SharedPreferences/KeyStore sweeps, log leakage sweeps |
| Untrusted input | chat, comment WebViews, `MumbleImageGetter`, `AvatarCache`, name binding, TTS, notifications, `mumble://`, `HtmlUtils`/`BitmapUtils`, menus, search provider |
| Native/supply chain | `NativeAudio*Jni`, `NativeCryptStateJni`, `jniopus.cpp`, audio_engine glue, `Android.mk`/`Application.mk`, both `build.gradle`, root `build.gradle`, `gradle.properties`, `settings.gradle`, wrapper, `flake.nix`/lock, proguard, manifests, assets/model pipeline, `.github/` |

Five parallel `security-reviewer` subagents, one per slice; parent deduplicated
(backup-exfil and cert-export were triple/double-reported) and spot-verified every
High/Critical claim below against the tree before writing `findings.md`.

## Verification log (parent, this session)

- `app/src/main/AndroidManifest.xml:56-114` — confirmed exported `MumlaService`,
  launcher + `mumble://` filters on `MumlaActivity`, all other activities
  `exported=false`.
- `libraries/humla/src/main/AndroidManifest.xml` — confirmed exported `HumlaService`
  + CONNECT/DISCONNECT filter, `allowBackup=true`.
- `HumlaTCP.java` grep — confirmed `readInt` → `new byte[messageLength]` before type
  check, `setSoTimeout(0)`.
- `MumlaSQLiteDatabase.java` grep — confirmed `SERVER_PASSWORD TEXT`, `TOKENS_VALUE
  TEXT NOT NULL`, certificates `BLOB NOT NULL` + `password TEXT`.
- `HumlaSSLSocketFactory.java:40-140` — confirmed `SSLContext.getInstance("TLS")`,
  no endpoint identification, wrapper checks chain only, unclosed `FileInputStream`.
- `MumlaService.java:830-866` — confirmed unauthenticated control actions;
  `:655-680` — confirmed `RECEIVER_EXPORTED` TALK registration.
- `NativeAudioInputEngineJni.cpp:170-189` — confirmed 32-bit `offset + length` check.
- `jniopus.cpp:407-420` — confirmed `memcpy(ptr0 + arg1, ptr, arg2…)` with no length check.
- `HumlaService.java:212-219,620-624` — confirmed CONNECT `throw` + parcelable server.
- `MumlaConnectionNotification.java:327,373` — confirmed `VISIBILITY_PUBLIC`.
- `ChannelChatFragment.java:624-626`, `AbstractCommentFragment.java:99-100,125-128`,
  `MumbleImageGetter.java:392-440`, `AvatarCache.java:107-110` — confirmed chat HTML,
  comment `loadData`, bounded-compressed/unbounded-decompress decode, avatar decode.
- `humla/build.gradle:39-42,89-135`, `app/build.gradle:97-109,129-155`,
  `jni/opus/version.mk:2` — confirmed javacpp 0.7, MiniDNS 0.3.4, RNNoise download
  without digest, debug-signing fallback, jsoup 1.13.1, Opus 1.1-beta.
- `HumlaCertificateGenerator.java:68-70`, `MumlaTrustStore.java:37-60`,
  `MumlaActivity.java:202-247` — confirmed empty PBE password, empty store password,
  global-pin TOFU.
- `CertificateExportActivity.java:127-190`, `ServerConnectTask.java:53-105`,
  `TalkBroadcastReceiver.java:30-57` — confirmed external-storage export, secret extras,
  toggle-default receiver.
- `humla/build.gradle` + `flake.nix` + `Android.mk` greps — confirmed NDK 25 pin and
  absence of explicit SSP/Fortify/RELRO flags.
- `ServerInfoResponse.java:49-60` + `ServerInfoTask.java:52-77`,
  `NativeCryptStateJni.cpp:207-211`, `MumlaService.java:383-415` — confirmed
  length-unguarded ping parse, `length + 4` arithmetic, raw actor-name TTS.

## Non-findings (checked, OK — do not re-audit without cause)

- No trust-all `X509TrustManager` / allow-all hostname verifier; no cleartext
  fallback; default cleartext deny stands.
- `PendingIntent`s explicit + `FLAG_IMMUTABLE` (notification confused-deputy OK).
- Overlay uses `TYPE_APPLICATION_OVERLAY` + `canDrawOverlays()` check; no tapjacking
  primitive found.
- No `FileProvider` / `getUriForFile` surface; no `taskAffinity` hijack;
  `ChannelSearchProvider` is undeclared dead code.
- No WebView JS bridge, no clipboard-of-untrusted-text, no file-based avatar cache.
- TTS message body is Jsoup-stripped before speaking (actor name is not — see L5).
- External images default off (`DEFAULT_LOAD_IMAGES = false`); SSRF needs opt-in (M5).
- Trust-store file `MODE_PRIVATE`; no `MODE_WORLD_*` anywhere. RSA-2048 +
  `SecureRandom` generation strength OK (H7 is at-rest only).
- Key/IV/nonce sizes enforced in OCB JNI; tag-mismatch path restores IV (fail-closed).
- Opus/RNNoise submodules pinned to exact SHAs (stale, not floating — H12/M11).
- No committed secrets/keys in build files; signing file correctly uncommitted
  (M16 is the silent fallback, not a leak).

## Gaps (not verified — how to close)

1. Upstream `../mumble` checkout absent — OCB/replay parity judged in-repo only.
   Close by diffing `CryptStateOCB2.cpp` against upstream tag.
2. Vendored Speex jitter buffer, full Opus CELT/SILK, RNNoise DSP not line-audited.
   Close with ASan/UBSan host runs + fuzzing `queuePacket`/`renderMix`/`decodeFloat`.
3. No runtime backup test — `allowBackup` payload unconfirmed on device. Close with
   `adb backup` / backup-rules inspector on a debug build.
4. `mumble://` password-in-URL parsing not fully traced. Close by reading
   `MumbleURLParser` end-to-end + firing test VIEW intents.
5. JNI key/voice paths not reviewed for secret leakage (out of secrets-slice scope).
6. Full Gradle dependency CVE sweep not run (versions pinned by reading build files;
   transitive closure unchecked). Close with `dependency-check` / OSV scan in CI.

## Fix roadmap (suggested work order)

1. Secrets at rest + backup: C1, H6, H7, H8, L1 (KeyStore/SQLCipher/EncryptedPrefs,
   `allowBackup=false` or exclusion rules, per-host pins).
2. Network identity: H1, M1, M2 (endpoint identification, TLS 1.2+ floor, per-host TOFU).
3. IPC: H3, H4, H5, M9 (unexport or permission-guard services/receiver, handle-based
   secret passing).
4. Protocol DoS: H2, L2, L3 (frame cap, timeouts, socket/stream close, ping length guard).
5. Native/supply chain: H10, H11, H12, M11, M12, M15 (overflow-safe checks, delete
   JavaCPP shims, Opus rebase, RNNoise digest pin, NDK 27).
6. Remote-input rendering: M3–M7, L5 (URL allowlist, WebView hardening, bounded decode,
   TTS stripping).
7. Hygiene: H9, M8, M10, M13, M14, M16, L4, L6, I2 (SAF-only export, char[] passwords,
   notification visibility, dep refresh, fail-closed signing, deep-link confirmation,
   legacy-storage removal, FLAG_SECURE if in threat model).

## Re-audit triggers

Touching TLS, DB schema, manifests, JNI boundary, chat/comment rendering, cert flows,
or build dependencies should re-run the corresponding slice above, not the whole audit.
