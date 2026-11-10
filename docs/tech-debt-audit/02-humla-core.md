# Core debt — `:libraries:humla` of Mumla OLED

Scope: `libraries/humla/src/main/java/se/lublin/humla/**` (generated protobuf
excluded), `libraries/humla/build.gradle`, plus protobuf parity spot-checks
against upstream (`src/Mumble.proto`, `src/MumbleProtocol.h`, `src/ACL.h`,
`src/Connection.cpp`). No protocol divergence was found; gaps are in error
handling, lifecycle, and hygiene, not wire parity. TCP frame validation
matches upstream (`0x7fffff` cap), all 27 TCP message types are modeled,
`Permissions.All = 0x1F0FFF` matches the upstream sum, and the coroutine
migration left honest threading comments.

## P0

| Location | Issue | Effort | Remediation |
|---|---|---|---|
| [`libraries/humla/build.gradle`](../../libraries/humla/build.gradle) (`prepareRnnoiseModel`, `modelHash`) | Task defines `modelVersionOrDigest` and keys the tarball by it, but the download branch interpolates never-declared `modelHash` in the log line and tarball URL. Steady-state builds skip the block via the stamp check, so it detonates (`MissingPropertyException`) only on cold builds or model rotation — exactly when it is needed. | XS | Replace `modelHash` with `modelVersionOrDigest` in both lines; verify with a cold-cache gate run. |

## P1

| Location | Issue | Effort | Remediation |
|---|---|---|---|
| [`HumlaTCP.kt`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.kt) (both `sendMessage` catches) + [`HumlaConnection.kt`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaConnection.kt) (`sendTCPMessage`) | Write failures are swallowed (`printStackTrace` + `TODO handle`, or silent early return on `!mConnected` documented as `Can silently fail`). The connection looks alive until the 30 s read timeout. Auth/version frames lost to a connect/disconnect race vanish. | S | Route send-path `IOException` into `error(...)`; add a debug log plus counter on the `!mConnected` drop. |
| [`HumlaService.kt`](../../libraries/humla/src/main/java/se/lublin/humla/HumlaService.kt) (wake/wifi vs foreground) | No `startForeground`/`foregroundServiceType` in the library; lifecycle relies on partial wake plus wifi locks. The app layer of Mumla OLED does promote (`MumlaConnectionNotification` `startForeground` with microphone type, manifest `foregroundServiceType="microphone"`), so the residual gap is a 4-row state table, not a definite kill: connected/background, connecting/background, notification-denied on API 33+, Doze vs locks plus `START_NOT_STICKY` schedule loss. | S | Keep library promotion out; add the contract comment plus the 4-row state table (trigger timing vs `onStartCommand` restrictions, denied-notification path, Doze); extend the smoke tier with Doze + process-death cases. |

## P2

| Location | Issue | Effort | Remediation |
|---|---|---|---|
| Logging debt (`HumlaTCP`, `HumlaConnection`, `HumlaService`, plus `Server`) | About seven `printStackTrace()` sites plus direct `android.util.Log` inside the library alongside the `HumlaLogger` fan-out; `logInfo` silently drops pre-sync info (`connection==null` or `!isSynchronized` early return); fatal path can dump server-controlled text. Exact census needs a grep to lock. | S | Centralize on tagged `Log.w/e(TAG,msg,e)`; funnel fatal paths through `HumlaLogger.logError` so the app layer can redact/forward. |
| `unitTests.returnDefaultValues = true` | `Handler(Looper.getMainLooper())` posts silently no-op under JVM tests, so the main-confined threading contract is untestable as configured. | S | Robolectric or a `Handler` seam for hot paths; at minimum a comment-linked test asserting the assumption. |
| God classes (`HumlaService` ~2000 lines, `HumlaConnection` ~1100, `AudioHandler` ~1050) | Connection state, reconnect, locks, routing, audio building, whisper, and binder share files; blast radius of any edit is the whole lifecycle. | M | Incrementally extract reconnect-scheduler and wake/wifi-lock ownership into tested units; no rewrite. |
| Certificate key backup exposure (app `AndroidManifest.xml` `allowBackup`, app DB certificate blobs) | Round 4 replaces the former certificate-hardening P1. [`HumlaCertificateGenerator.kt`](../../libraries/humla/src/main/java/se/lublin/humla/net/HumlaCertificateGenerator.kt) (serial `1`, RSA-2048, 20-year validity) matches upstream `src/SelfSignedCertificate.cpp` exactly, and Mumble identifies users by certificate hash, not serial, so those values are not defects. An empty PKCS#12 password is also not the exposure: the app DB stores any password next to the blob (`MumlaDatabase.addCertificate`). The real question is that `android:allowBackup="true"` with no `dataExtractionRules` puts private keys in device backups and transfers. That may be intended (identity survives a phone change), but it is undocumented. | XS | Decide and document: either keep backup as a feature (note it in user docs) or exclude the database via `dataExtractionRules` plus `fullBackupContent`. Keep generator values at upstream parity. |
| Split DB ownership (`Server.kt` `@Entity` vs app-layer `@Database`/migrations) | Schema/migration debt is invisible from this module; any field change needs a cross-module migration with no in-module reminder. | XS | One-line KDoc pointer on `Server` to the owning database/migration file. |
| Duplicated SRV resolution (`Server.srvResolve()` vs `HumlaTCP.runLoop`) | Two executors and two onion/IP-skip spellings; timeout value is the same 800 ms in both (mechanism differs, not the value). Drift risk. | S | Single `SrvResolver` helper used by both. |
| Silent stub surface (`HumlaTCPMessageListener` stubs, `ModelHandler` 8/27 overrides) | `Version`, `Reject`, `CodecVersion`, `SuggestConfig`, and similar arrive as no-op stubs; a server `Reject` or suggestion is swallowed with zero observability. Benign today (Opus-only) but blind. | S | Log-and-count unhandled types via `HumlaLogger.logWarning`. |
| Reconnect holds wake+wifi across backoff gaps | By design for redial, but an unreachable server means an indefinite partial-wake hold with 25 s-max backoff and `START_NOT_STICKY` losing the schedule on process death. | S | Cap the total reconnect window or release locks between attempts after N tries; document intended behavior. |

## P3 (report-only)

- `WhisperTargetUsers` members throw `UnsupportedOperationException` with TODO:
  dead whisper-to-user path.
- `ModelHandler` TODO/FIXME cluster (local-mute history, bitmap-on-main,
  crash-guard hack); `AudioHandler`/`HumlaConnection`/`HumlaTCP` TODO cluster;
  `calculateAudioBandwidth` knowingly underestimates worst case.
- `PacketBuffer.readDouble` preserves a deliberate upstream-parity arithmetic
  quirk; keep parity but flag if positional audio revives.
- `HumlaUDP.BUFFER_SIZE = 2048` vs upstream `1024`: harmless oversize, note only.
- `CryptState.finalize()` relies on explicit `destroy()`; migrate to `Cleaner`
  or delete if disconnect coverage is proven.
- `Constants.kt` protocol `1.5.0` matches upstream `1.5.x`; no version drift.
