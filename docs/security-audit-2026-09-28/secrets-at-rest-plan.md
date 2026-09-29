# Secrets at rest: proportionate fix plan

Companion to [findings.md](findings.md) (C1, H6, H7, H8, L1) and
[notes.md](notes.md). Investigation/plan only; no code changed.

Supersedes the previous zero-UX full-column-encryption revision of this
plan (2026-09-28). That design is deferred — see
[Why not full-column encryption](#why-not-full-column-encryption) — because
the finding is over-scored relative to its cheapest fix.

## Reassessment: over-scored, wrong fix shape

`mumble.db` is `MODE_PRIVATE`, no `MODE_WORLD_*` anywhere, sandbox +
SELinux deny cross-app reads. Reaching the plaintext needs root,
unlocked-physical `adb` tap-through, or Google-account + device PIN
(Auto Backup has been E2EE since Pie). Not remotely reachable, not
Critical. This plan treats C1 as High, H6 as Medium (Mumble server
passwords/tokens are shared, low-entropy, single-server revocable —
not bank keys), H7 as a duplicate of C1 (the empty PBE password only
matters once the DB is already read), H8 as the one actionable vector,
and L1 as cheap to fix alongside. `findings.md` stays untouched as the
point-in-time record; the downgrade lives here.

Column encryption would not even cover the live paths: `ServerConnectTask`
parcels the `Server` (password included), the full PKCS#12, its password,
and the tokens into the exported `HumlaService`
(`app/.../app/ServerConnectTask.java:53-101`), and `Server.writeToParcel`
keeps the password (`libraries/humla/.../model/Server.java:84-90`). M9
stays fully open under any at-rest scheme, so no plan here may claim
bug-report/Binder/heap coverage.

## Goal

Kill the backup-exfil path and shrink persisted secrets with no crypto,
no DB migration, no new prompts in normal flows. Rare-path costs only:
new-device restore re-adds servers and re-imports certs once; users who
relied on silently-saved server passwords re-enter once via the opt-in
below.

## Threat model

Fixes: `adb`/cloud backup readers (H8 — the only at-rest vector
reachable without root or unlocked-physical access).
Accepted residual: root or unlocked-physical file read of the
`MODE_PRIVATE` sandbox (platform control, out of scope); live-process
memory, Binder parcels to the exported `HumlaService`, bug-report
scrapes (the M9 track, untouched by anything here).

## Phase 1 design (this plan — no crypto)

1. Backup exclusion (~10 lines): add `res/xml/backup_rules.xml`
   (`fullBackupContent`, API ≤ 30) and
   `res/xml/data_extraction_rules.xml` (API 31+; required now that
   `targetSdk` is 36) excluding `databases/mumble.db` (+ journal/WAL),
   `files/mumla-store.bks`, and the secret prefs file from item 3.
   Wire both attributes into the `:app` manifest; keep
   `allowBackup=true` so settings and non-secret state still restore.
   The `:humla` manifest's `allowBackup=true` merges harmlessly.
2. Server passwords opt-in: add a "remember password" checkbox to
   `dialog_server_edit.xml` (default unchecked). In
   `MumlaActivity.onServerEdited`, persist the password for ADD/EDIT
   only when checked; CONNECT_ACTION passes the typed password through
   for the session without storing. The edit dialog keeps prefilling
   the field from the stored value, so existing passwords decay
   naturally (saving with the box unchecked clears them) — no forced
   wipe, no upgrade surprise. Existing stored passwords stay
   sandbox-local in the meantime, which the threat model accepts.
3. Trust store (L1): generate a random 32-byte `STORE_PASS` via
   `SecureRandom` on first access, persist it in a dedicated
   `MODE_PRIVATE` prefs file excluded from backup, and re-`store()` the
   existing `mumla-store.bks` (`app/.../util/MumlaTrustStore.java`)
   under it. `getTrustStorePassword()` gains a `Context` parameter
   (the no-arg form cannot reach prefs/KeyStore — clean cutover at the
   `ServerConnectTask` call site). If the secret file is missing
   (new-device restore), regenerate and re-init an empty store:
   re-TOFU once, fail-closed, nothing irreplaceable lost.
4. Tokens and cert blobs: unchanged locally, backup-excluded with the
   DB. No encryption, no schema change, no `onUpgrade` version bump.

## Explicit non-goals

- No `SecretStore`/KeyStore column encryption (deferred, next section).
- No `exported=false` or TALK-receiver changes — `se.lublin.mumla.action.TALK`
  is a documented Tasker/Automate surface, separate track with its own
  release note.
- No generated-cert re-passwording — a random PBE password the user
  doesn't know breaks `CertificateExportActivity` (exports a file the
  user can't open).
- No destructive wipe of cert blobs on any failure — the
  `certificates.data` column is `BLOB NOT NULL`; wiping is irreversible
  identity loss, worse than the backup-only threat.
- No SQLCipher, no `allowBackup=false` (exclusion is granular; settings
  restore keeps working).

## Why not full-column encryption

Record of the earlier design's blockers, so it is not re-proposed
unmodified. Revive only if a new at-rest vector appears or policy
demands it.

- `removeAccessToken` deletes `WHERE value=?`, which never matches
  under a randomized IV — needs read-decrypt-delete-by-`TOKENS_ID`
  (`MumlaSQLiteDatabase.java:324-325`, `AccessTokenFragment.java:162`).
- Ciphertext needs version markers (`v1:` + Base64 for `TEXT`, magic
  byte + raw bytes — not Base64 — for the cert `BLOB`) plus pinned
  null-vs-`""` semantics, or re-encryption double-encrypts and restores
  misfire.
- `VACUUM` cannot run inside the migration transaction; the helper
  needs a stored `Context` for the store; GCM parameters (128-bit tag,
  12-byte `SecureRandom` IV, `NO_WRAP`, auth-required `false`) must be
  pinned in the spec, not left to the implementer.
- `minSdk 21` makes the API 21–22 RSA-wrap fallback real: it must spec
  OAEP, wrapped-blob storage, and backup exclusion of the wrapping key,
  or ciphertext in backups is theater.
- Generated-cert PBE belongs in the app layer
  (`MumlaCertificateGenerateTask`), not in pure-JVM `humla/`.
- It leaves M9 live exposure identical — the threat-model wording must
  say so upfront.

## Verification

- `adb backup` / backup-rules inspector with a known
  password/token/cert password: all strings and all three secret files
  absent from the payload.
- New-device restore: app usable, server list/certs absent
  (documented), re-add once.
- Existing-device upgrade: connect, TOFU allow, cert
  import/export/generate flows unchanged; saved-password users
  unaffected until their next server edit.
- No new unit tests (no crypto or migration logic added); existing
  suite must pass.
