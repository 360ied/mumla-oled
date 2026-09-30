# Phase 4 implementation plan — local IPC + hygiene (H3–H5, H9, M1, M8, M9, M16, L2, L4, L6, I2)

Companion to [remediation-plan.md](remediation-plan.md) Phase 4 and
[findings.md](findings.md) (H3–H5, H9, M1, M8, M9, M16, L2, L4, L6,
I2). M10 (lockscreen visibility) is decided won't-fix, see C6; M13
(jsoup) and M14 (MiniDNS) deferred to routine dep bumps, see C11.
Incorporates a pre-implementation review of the tree: eleven
corrections to the remediation plan as written, prerequisites to close
before coding, then the work split (four parallel worktrees).
Implementation itself MUST happen in dedicated worktrees
(`./scripts/worktree.py add <branch>`); this file is docs-only on
`master`.

Threat model: installed malicious app, plus user error / device
hygiene. No remote attacker in this phase. Already closed by earlier
phases — do not redo: L3 ping guard + socket close
(`ServerInfoTask.java:53-67`, Phase 2 slice D), `ServerInfoResponse`
length guard (`ServerInfoResponse.java:50-54`, Phase 2), message/TTS
sanitizer (`NotificationSanitizer.java`, Phase 2), NDK 27 (Phase 3
slice C). I1 needs no action (wire compat).

## 0. User-visible changes (UX contract — read first)

No new dialogs. Behavior changes:

- Third-party apps can no longer start or drive `MumlaService` /
  `HumlaService` (mute/deafen/disconnect/connect). All internal
  starters are explicit same-app intents, so nothing internal breaks.
  Tasker/Automate `TALK` broadcasts keep working while a new settings
  toggle (default on) allows disabling them.
- Cert export becomes SAF-only: the pre-R `/sdcard/Mumla` classic path
  is deleted. No extra confirmation dialog — the export tap plus the
  SAF picker is consent; the suggested filename is sanitized.
- `mumble://` links keep the existing edit-before-connect dialog; URLs
  embedding a password gain an inline warning line (host stays
  visible/editable as today).
- Lockscreen notification visibility unchanged (M10 won't-fix, C6):
  voice comes out the speakers anyway, so anyone close enough to read
  the lockscreen can already hear the conversation.
- Release builds without `signing.gradle` now fail with an explicit
  error instead of silently shipping a debug-signed artifact.
- TLS 1.0/1.1-only servers stop connecting (1.2+ floor).
- New strings ship English-only initially (no translations in this change).

## 1. Corrections to remediation-plan.md (read first)

### C1 — H5 signature permission would kill the Tasker surface it claims to keep

`TalkBroadcastReceiver.java:30-61` is a documented Tasker/Automate
surface (`BROADCAST_TALK`, null-status defaults to toggle, no sender
check). A `signature`-level permission cannot be held by Tasker
(different signing key), so "signature permission + sender check"
reads as "break Tasker". A `normal`-level custom permission is
theater — any app requests it. Sender checks are unavailable:
`getSendingUid()` is API 34+ against minSdk 21. Decided: keep
`RECEIVER_EXPORTED`, no custom permission. Mitigation is a settings
toggle (default on, preserves compat) plus the already-existing
compensating control — mute state is surfaced in the foreground
notification, so TALK-driven silent-unmute is visible. Unknown
non-null status values are already no-ops; keep null→toggle.

### C2 — H3/H4: `exported=false`, not a custom permission

No documented external consumer drives the services — internal
starters are exhaustive: `MumlaActivity.java:491-492` (`bindService`,
explicit), `ServerConnectTask.java:64-111` (`startService`,
explicit), `ChannelSearchProvider.java:97-98` (`bindService`,
explicit), notification `PendingIntent.getService` with explicit
`Intent(mService, MumlaService.class)`
(`MumlaConnectionNotification.java:310-314`), MediaSession custom
actions via Binder callback (`:202-215`, not intents). TALK is the
only external surface and it is a broadcast, not a service start.
`exported=false` on both services closes H3/H4 with zero new
permission surface and zero internal churn.

### C3 — H4 `throw` must become log-and-ignore, not just any error return

`HumlaService.java:217-220` throws `RuntimeException` from exported
`onStartCommand` — a crash primitive today. But `onStartCommand`
cannot "return an error" to the sender either; the correct shape is
`Log.w` + `return START_NOT_STICKY` (never `throw`, never proceed to
`configureExtras`/`connect()` on the malformed intent).

### C4 — external trust-store paths are already dead under C2; keep a cheap validator

Sole internal producer is `ServerConnectTask.java:77-79`
(`MumlaTrustStore.getTrustStorePath`, filesDir or null). Under C2 no
external app can inject `EXTRAS_TRUST_STORE` at all. Defense in depth
only: `configureExtras` (`HumlaService.java:694-705`) rejects paths
outside the app filesDir (reject `..`, require BKS), it does not need
a wider redesign. Do NOT move trust-store loading out of extras —
that drags app-layer `Context` knowledge into `HumlaService`.

### C5 — M9 full handle-refactor is disproportionate for a downgraded Low

M9 readers need `system_server`/root/dump; `exported=false` (C2)
already removes the installed-app Binder reader. True `char[]`
end-to-end would cross the DB boundary
(`addCertificate(..., String)`, `Server.writeToParcel` Strings,
`SERVER_PASSWORD TEXT`) — and at-rest persistence stays plaintext by
[secrets-at-rest-plan.md](secrets-at-rest-plan.md) decision, so the
Strings cannot be eliminated anyway. Bounded scope: `char[]` from the
import dialog through `KeyStore.load`, zeroed immediately, never
duplicated into Bundles/fields beyond the two required same-UID
handoffs (Server parcel, connect extras), which C2 contains.
Document the `system_server` parcel residual; do not build a
content-URI/KeyStore-alias passing scheme.

### C6 — M10 won't-fix: lockscreen redaction is theater on a voice app

Voice comes out the speakers, so anyone within lockscreen-reading
distance can already hear the conversation. A `PRIVATE` flip buys
nothing and costs a channel-ID bump (OS persists channel settings, so
the flip needs a new ID plus old-ID deletion — same pattern as
`connected_channel_v9` — resetting every user's importance settings
for zero threat gain). Leave `MumlaConnectionNotification.java:327,373`
and `message_channel` (`MumlaMessageNotification.java:87-95`) as-is.
No opt-out setting either — nothing to opt out of.

### C7 — H9 manifest bits and activity rewrite split across two slices

SAF-only export deletes `WRITE_EXTERNAL_STORAGE` (`AndroidManifest.xml:29`)
and `requestLegacyExternalStorage` (`:52`) — but `READ_EXTERNAL_STORAGE`
(`:30`) STAYS: `ChannelChatFragment.java:192-200` still needs it for
the image picker on SDK ≤ 32. Slice B owns the activity rewrite plus
the storage/legacy manifest lines; slice A owns only the service
stanzas. Land B before A so the classic path is gone before its
permission disappears (intermediate state otherwise degrades to a
deny-toast, not a crash).

### C8 — M1 floor placement: filter, don't hardcode

`SSLContext.getInstance("TLS")`
(`HumlaSSLSocketFactory.java:50`) with no protocol pin (Phase 1
touched SNI/endpoint-ID, not protocols). Hardcoding
`{"TLSv1.2","TLSv1.3"}` breaks nothing on API 29+, but pre-29 has no
1.3 — intersect the request set with `getSupportedProtocols()` so
API 21–28 keep 1.2. Pure helper (`filterTlsProtocols(String[])`),
unit-testable, no socket needed.

### C9 — L4 already gates on a dialog; add warning, not friction

`MumlaActivity.java:452-466` never auto-connects: VIEW intents land
in `ServerEditFragment` (host/user/password prefilled `:129-138`,
Cancel available `:145-150`). The gap is prominence of embedded
credentials, not missing consent. Add an inline warning row shown
only when the parsed URL carried a password; do not block or strip
(the parser `MumbleURLParser.java:34,42-52` keeps its shape).

### C10 — small-hunk placement (no slice of its own)

- L2 remnant: `MumlaTrustStore.java:45-61` still hand-closes streams
  (throw inside `load`/`store` skips `close`); try-with-resources.
  (`HumlaSSLSocketFactory` and `ServerInfoTask` streams were fixed in
  Phases 1–2.)
- L6 crypt: `NativeCryptStateJni.cpp:208` `NewByteArray(length + 4)`
  in 32-bit arithmetic — `length <= srcLen` bounds it in practice;
  add explicit `length > INT_MAX - 4` guard. One line.
- M16 naive-throw trap: the release-signing fallback
  (`app/build.gradle:104-108`) runs at *configuration* time, so a
  bare `throw` in the `else` breaks every build (including debug/CI)
  without `signing.gradle`. Fail at execution time only: check inside
  `taskGraph.whenReady` (or equivalent) for release-assemble tasks.
- I2 (`FLAG_SECURE` nowhere): non-goal — screenshots are the device
  owner's own action; no credential-window flag in this phase.

### C11 — M13/M14 deferred to routine dep bumps, not this security plan

Neither has a demonstrated vuln: M13 is `parseBodyFragment`+`text()`
only with no HTML sink at the call site
(`MumlaService.java:386-388`, `NotificationSanitizer.java:68`); M14 is
staleness only ("no vuln demonstrated"). The 0.3.4→1.x MiniDNS jump is
the riskiest migration in Phase 4 (`ResolverApi`/`SrvResolverResult`/
`SrvUtil` at `Server.java:226-242` and `HumlaTCP.java:114-124`,
`AndroidUsingLinkProperties` at `HumlaService.java:248-258`) for zero
demonstrated payoff. Defer both to routine dependency maintenance; no
version pin, no call-site migration in this phase.

## 2. Prerequisites (close before coding, not during)

- **P1 — no external service consumers (closed 2026-09-29).** Internal
  starters enumerated in C2 (grep over `app/` + `humla/`); TALK is the
  sole documented external surface (receiver code + findings H5). No
  Play/Tasker doc references a service start. `exported=false` is safe.
- **P2 — dropped (M13/M14 deferred, C11).** No version pins, no
  call-site migration in this phase.
- **P3 — release-signing expectations (open, slice C).** Read CI
  workflows + any release docs for who provides `signing.gradle`;
  craft the fail-closed error message (what file, what to do).
- **P4 — test seams.** `unitTests.returnDefaultValues = true`
  (`app/build.gradle:139`, `humla/build.gradle:84`) stubs platform
  types. Pure and unit-testable: TLS protocol filter (C8),
  trust-store path validator (C4), export filename sanitizer,
  `ServerInfoResponse`-style guards need nothing new. Service
  denial, TALK toggle, export, deep-link warning are
  manual (`adb` + device).
- **P5 — settings wiring (open, slice A).** Read the settings
  preference pattern for the TALK toggle (default-on key,
  English-only strings).

## 3. Work split — four parallel worktrees

| Slice | Branch | Findings | Files owned | Tests owned |
|---|---|---|---|---|
| A | `phase4-ipc-services` | H3,H4,H5,C4 | both manifests (service stanzas only), `MumlaService.java` `onStartCommand`+TALK registration, `HumlaService.java` entry guard+`configureExtras`, `TalkBroadcastReceiver.java`, Settings toggle + strings | adb denial tests (manual) |
| B | `phase4-secrets-export` | H9,M8,M9(java),L6-filename | `CertificateExportActivity.java`, `CertificateImportActivity.java`, `Server.java` (parcel only), `ServerConnectTask.java`, app-manifest storage/legacy lines only, export filename sanitizer + test | sanitizer unit test, rotation manual |
| C | `phase4-tls-signing` | M1,M16,L6-crypt | `app/build.gradle` (signing only), `HumlaSSLSocketFactory.java` + protocol-filter helper, `NativeCryptStateJni.cpp` one-liner | filter unit test, build |
| D | `phase4-surface-hygiene` | L4,L2-remnant | `MumlaActivity.java` VIEW hunk, `ServerEditFragment.java` + warning strings, `MumbleURLParser.java` if needed, `MumlaTrustStore.java` | deep-link manual |

Each worktree forks `master`; land order D, C, B, A (A last: IPC
surface; B before A per C7).

### Slice A — service lockdown + TALK toggle (H3, H4, H5)

- `android:exported="false"` on `MumlaService`
  (`app/.../AndroidManifest.xml:60-64`) and `HumlaService`
  (`humla/.../AndroidManifest.xml:29-35`); intent filters stay (harmless
  when not exported). No new `<permission>` elements (C1, C2).
- `HumlaService.onStartCommand`: malformed CONNECT (missing
  `EXTRAS_SERVER`) logs and returns `START_NOT_STICKY` (C3); keep the
  duplicate-connect guard as is.
- `configureExtras`: filesDir-scoped trust-store validation (C4).
- `MumlaService.onStartCommand` control actions unchanged in logic
  (now unreachable externally); TALK registration in
  `onConnectionSynchronized` (`:667-669`) gated on the new
  default-on setting; disconnect path already tolerates
  never-registered (`:685-688`).
- Accept: external `am startservice` control/CONNECT attempts ignored
  (logcat shows the warning, no crash, no state change); TALK works
  with toggle on, inert with toggle off; `./scripts/check.sh` green.

### Slice B — import/export secrets + parcel (H9, M8, M9, L6-filename)

- H9: delete `saveCertificateClassic` + permission flow
  (`CertificateExportActivity.java:127-177`); SAF-only via
  `documentCreator` — the export tap plus the SAF picker is consent,
  no extra confirmation dialog; suggested name through a pure
  `sanitizeExportFilename` (strip separators, reserved names, trim,
  cap length, `.p12` fallback, UUID on empty) covering L6-filename;
  manifest storage/legacy lines per C7. Defer re-encryption with a
  user password (needs import-side flow; explicit tap + SAF picker
  suffice for an explicit-tap Low).
- M8: `CertificateImportActivity` keeps `char[]` from field through
  `KeyStore.load`, zeroing immediately (`:304-312` already zeroes the
  dialog copy — extend to `storeKeystore` `:193-249`, replacing the
  `new String(password)` at `:234` with conversion at the DB
  boundary only); saved state stores the source `Uri`, never
  `STATE_CERT_BYTES`/`STATE_PREVIOUS_PASSWORD` — re-read (5 MB cap
  kept) on restore.
- M9 (bounded per C5): no `Parcelable`/extra shape changes; avoid new
  copies; document the residual.
- Accept: rotation during password prompt loses no secret into the
  Bundle; traversal/reserved/empty cert names sanitize; export on a
  pre-R device uses SAF; `./scripts/check.sh` green.

### Slice C — TLS floor + signing + crypt one-liner (M1, M16, L6-crypt)

- M1: `filterTlsProtocols` pure helper + apply post-creation in
  `createSocket` (C8; onion path needs no exemption — protocols are
  not identity).
- M16: execution-time fail-closed for release tasks without
  `signingConfigs.release` (C10 trap); debug/CI configuration
  unaffected; error message per P3.
- L6-crypt: `INT_MAX - 4` guard before `NewByteArray`.
- Accept: TLS≤1.1 handshake refused; release assemble without
  `signing.gradle` fails with the documented error while
  `assembleFossDebug` still configures; `./scripts/check.sh` green.

### Slice D — deep link + trust-store streams (L4, L2)

- L4: password-embedded deep links show the warning row in
  `ServerEditFragment` (C9); parser untouched unless the warning
  needs a carried flag.
- L2: try-with-resources in `MumlaTrustStore.java:45-61`.
- Accept: deep link with `user:pass@host` warns, without stays as
  today; `./scripts/check.sh` green.

## 4. Non-interaction contract (enforce before fan-out)

- App manifest split: A touches service stanzas only; B touches
  `requestLegacyExternalStorage`, storage permissions only. Neither
  reformats the other's lines.
- `HumlaService.java` is A's alone; `Server.java` parcel hunks are
  B's (B MUST NOT reformat the SRV block, A MUST NOT touch `init()`).
- `app/build.gradle` signing hunk is C's alone.
- `strings.xml` additions only, disjoint names per slice; never edit
  another slice's strings.
- Slice C MUST NOT change `createSocket` signatures — Phase 1 callers
  (`HumlaTCP`) stay untouched.

## 5. Test matrix (JVM unit tests unless noted; manual where stated)

| Case | Expects |
|---|---|
| External `am startservice` MUTE/DEAFEN/DISCONNECT (manual) | ignored, warning logged, call state unchanged |
| External CONNECT without `EXTRAS_SERVER` (manual) | no crash, `START_NOT_STICKY` |
| `EXTRAS_TRUST_STORE` outside filesDir | rejected in `configureExtras` |
| TALK toggle off + broadcast (manual) | no mic state change; on = status honored |
| Export filename `../../x`, `CON`, empty | sanitized / fallback, no traversal |
| Rotation during import password prompt (manual) | re-prompts from Uri, Bundle holds no secret bytes |
| `filterTlsProtocols` incl. pre-29 sets | `{1.2}` kept, `1.0/1.1` dropped, `1.3` kept iff supported |
| TLS 1.0-only server (manual) | handshake refused |
| Release assemble without `signing.gradle` | explicit failure; debug still configures |
| `length > INT_MAX - 4` encrypt call | `nullptr`, no overflow |
| `mumble://user:pass@host/` VIEW (manual) | warning row; no-password URL unchanged |
| Existing suite | `./scripts/check.sh` green from each worktree |

## 6. Residuals and non-goals (explicit, not overlooked)

- M10 won't-fix (C6): lockscreen visibility unchanged. Voice comes out
  the speakers, so lockscreen proximity implies audio proximity — no
  channel-ID bump, no importance reset.

- TALK stays exported by design (C1): any installed app can drive
  mic state while connected when the toggle is on (default). Accepted
  as the documented automation surface; toggle + visible mute state
  are the mitigation.
- M9 `system_server`/root Binder parcel visibility remains (C5);
  `Server` parcel and DB Strings persist per the at-rest won't-fix
  boundary ([secrets-at-rest-plan.md](secrets-at-rest-plan.md)).
- Export re-encryption deferred (slice B); generated certs have no
  PBE password and the SAF picker is the consent for an explicit-tap
  Low — no extra confirmation dialog.
- M13/M14 deferred to routine dep bumps (C11); dep rot re-accumulates
  without automated bump tooling (audit gap 6 stays open).
- TLS floor drops pre-1.2 servers (intended).
- No `FLAG_SECURE` (I2 non-goal, C10); I1 no action.
- No per-message remote-origin surfacing, no `customtabs`, no
  notification opt-out setting beyond the TALK toggle.

## 7. Execution (worktree + commit + merge order)

- Create: `./scripts/worktree.py add phase4-ipc-services`,
  `phase4-secrets-export`, `phase4-tls-signing`,
  `phase4-surface-hygiene` (root stays on `master`; stagger
  `check.sh` runs — nix gradle is heavy in parallel).
- Commits via `scripts/commit.py` with the three-section body; every
  code commit leaves `./scripts/check.sh` green inside its worktree.
- No autonomous merging, pushing, or deletion (per `AGENTS.md`): leave
  branches and worktrees intact and unpushed, report for review.
- Suggested review/merge order: surface-hygiene, tls-signing,
  secrets-export, ipc-services last (C7 + largest blast radius).
