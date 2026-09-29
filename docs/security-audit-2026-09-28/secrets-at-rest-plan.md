# Secrets at rest: proportionate fix plan (backup exclusion only)

Companion to [findings.md](findings.md) (C1, H6, H7, H8, L1) and
[notes.md](notes.md). Investigation/plan only; no code changed.

Supersedes the previous zero-UX full-column-encryption revision and the
smaller no-crypto revision with trust-store re-passwording +
remember-password checkbox (both 2026-09-28). Those designs are
deferred/dropped — see [Why not full-column
encryption](#why-not-full-column-encryption) and [Why not trust-store
re-passwording or a remember-password
checkbox](#why-not-trust-store-re-passwording-or-a-remember-password-checkbox)
— because the finding is over-scored relative to its cheapest fix.

## Reassessment: over-scored, wrong fix shape

`mumble.db` is `MODE_PRIVATE`, no `MODE_WORLD_*` anywhere, sandbox +
SELinux deny cross-app reads. Reaching the plaintext needs root,
unlocked-physical `adb` tap-through, or Google-account + device PIN
(Auto Backup has been E2EE since Pie). Not remotely reachable, not
Critical.

Secret value tier, worst-first — all low:

- Client self-signed voice identity (`certificates.data BLOB`,
  `MumlaSQLiteDatabase.java:101-111`): impersonation on Mumble servers
  only. No CA chain, no financial scope, revocable per-server. Not a
  bank key, long-lived OAuth token, or E2E key.
- Server passwords + access tokens (`SERVER_PASSWORD TEXT`,
  `TOKENS_VALUE TEXT NOT NULL`): shared, low-entropy, single-server
  revocable bearer strings. Weaker than a browser-saved password behind
  OS lock.
- PKCS#12 passwords (H7) and the trust-store empty password (L1,
  `MumlaTrustStore.java:38`): integrity-only once `MODE_PRIVATE` holds.
  The empty PBE password only matters after the DB is already read —
  a duplicate of C1, not a second finding.

This plan treats C1 as Low, H6 as Low, H7 as Low (duplicate of C1), H8
as Low-but-cheap-to-fix (the only at-rest vector reachable without root
or unlocked-physical access), and L1 as Info / won't-fix.
`findings.md` stays untouched as the point-in-time record; the downgrade
lives here.

Column encryption would not even cover the live paths: `ServerConnectTask`
parcels the `Server` (password included), the full PKCS#12, its password,
and the tokens into the exported `HumlaService`
(`app/.../app/ServerConnectTask.java:53-101`), and `Server.writeToParcel`
keeps the password (`libraries/humla/.../model/Server.java:84-90`). M9
stays fully open under any at-rest scheme, so no plan here may claim
bug-report/Binder/heap coverage.

## Goal

Kill the backup-exfil path with no crypto, no DB migration, no UX
change, no new prompts. Settings and non-secret state in Mumla OLED
still restore.

## Threat model

Fixes: `adb`/cloud backup readers (H8 — the only at-rest vector
reachable without root or unlocked-physical access).
Accepted residual: root or unlocked-physical file read of the
`MODE_PRIVATE` sandbox (platform control, out of scope); live-process
memory, Binder parcels to the exported `HumlaService`, bug-report
scrapes (the M9 track, untouched by anything here).

## Phase 1 design (backup exclusion only, ~10 lines)

Add `res/xml/backup_rules.xml` (`fullBackupContent`, API ≤ 30) and
`res/xml/data_extraction_rules.xml` (API 31+; required now that
`targetSdk` is 36) excluding `databases/mumble.db` (+ journal/WAL) and
`files/mumla-store.bks`. Wire both attributes into the `:app` manifest;
keep `allowBackup=true` so settings and non-secret state still restore.
The `:humla` manifest's `allowBackup=true` merges harmlessly.

That is the whole change. No password checkbox, no trust-store
re-passwording, no token/cert handling, no schema change, no `onUpgrade`
version bump.

## Explicit non-goals

- No `SecretStore`/KeyStore column encryption (deferred, next section).
- No trust-store re-passwording (L1 won't-fix, next section).
- No remember-password checkbox — server passwords keep persisting as
  today once backup-excluded; auto-save behind OS lock is proportionate
  for shared low-entropy server passwords.
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

## Why not trust-store re-passwording or a remember-password checkbox

Dropped from the previous revision as theater / UX tax with no boundary
gain:

- Random `STORE_PASS` in a second `MODE_PRIVATE` prefs file moves the
  secret across the same UID boundary. A reader of `mumla-store.bks`
  reads the prefs file too; sandbox, SELinux, FBE, and backup-E2EE treat
  them identically. Real fix would be KeyStore-backed, not another
  `MODE_PRIVATE` file — and that is not worth it for per-host TOFU pins.
  L1 stays Info / won't-fix.
- A remember-password checkbox charges every normal-flow user (re-enter
  once, new toggle to learn) to protect a shared low-entropy server
  password that is weaker than what browsers auto-save behind OS lock.
  Once backup-excluded, current persist behavior is proportionate.

## Verification

- `adb backup` / backup-rules inspector with a known
  password/token/cert: all strings and both secret files absent from the
  payload.
- New-device restore: app usable, server list/certs absent for restored
  users (documented as the cost of exclusion), re-add once.
- Existing-device upgrade: connect, TOFU allow, cert
  import/export/generate flows unchanged; no new prompts, no decay.
- No new unit tests (no crypto or migration logic added); existing
  suite must pass.
