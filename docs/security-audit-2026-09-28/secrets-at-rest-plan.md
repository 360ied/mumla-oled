# Secrets at rest: C1/H6/H7/L1 do not fix, H8 open

Companion to [findings.md](findings.md) (C1, H6, H7, H8, L1) and
[notes.md](notes.md). Investigation/plan only; no code changed on master.

Supersedes all previous revisions of this plan (full-column encryption,
trust-store re-passwording + remember-password checkbox, and
backup-exclusion-only — all 2026-09-28) for C1/H6/H7/L1. None of those
will be revived without a new at-rest vector or a policy demand. H8
remains open; the backup-exclusion attempt is abandoned and will not be
merged.

## Decision

- C1 (client keys + PKCS#12 passwords in `mumble.db`): do not fix.
  Tier-3 single-server voice identity behind `MODE_PRIVATE`; reaching it
  needs root or unlocked-physical read.
- H6 (server passwords + access tokens): do not fix. Shared,
  low-entropy, single-server revocable strings — weaker than
  browser-saved passwords behind OS lock.
- H7 (empty PBE password on generated certs): do not fix. Duplicate of
  C1; it only matters after the DB is already read.
- H8 (backup includes secret DB): OPEN, not accepted. No working fix
  yet — the exclusion attempt below did not close the `adb` path on the
  tested build, and the branch will not be merged.
- L1 (empty trust-store password): do not fix (Info). Integrity-only
  once `MODE_PRIVATE` holds; KeyStore backing is disproportionate for
  per-host TOFU pins.

`findings.md` stays untouched as the point-in-time record; the downgrade
(C1/H6/H7 to Low, L1 to Info) lives here. H8 keeps its severity and
stays open.

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
  revocable bearer strings.
- PKCS#12 passwords (H7) and the trust-store empty password (L1,
  `MumlaTrustStore.java:38`): integrity-only once `MODE_PRIVATE` holds.

Column encryption would not even cover the live paths: `ServerConnectTask`
parcels the `Server` (password included), the full PKCS#12, its password,
and the tokens into the exported `HumlaService`
(`app/.../app/ServerConnectTask.java:53-101`), and `Server.writeToParcel`
keeps the password (`libraries/humla/.../model/Server.java:84-90`). M9
stays fully open under any at-rest scheme, so no plan here may claim
bug-report/Binder/heap coverage.

## What was tried: backup exclusion did not close `adb backup`

An experimental `backup-exclusion` branch (unmerged, will not be merged)
added `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`
(cloud + device-transfer) excluding `databases/mumble.db` (+
journal/WAL) and `files/mumla-store.bks`, wired via `fullBackupContent`
and `dataExtractionRules` with `allowBackup=true` kept. The merged
manifest carried all three attributes and the APK packaged both rule
files.

Device check on an SDK 36 device with a debuggable FOSS build: an `adb
backup` payload still contained `db/mumble.db` and `f/mumla-store.bks`,
and canary server password/token rows seeded in the live DB read back
out of the payload DB. So that branch is dead — it will not be merged.
Cause undetermined on the tested configuration (debuggable build vs
release, `adb`-backup vs Auto-Backup semantics); no retest is planned
under this plan. If H8 is picked up later, that track starts from this
observation, not from the abandoned branch. Canary rows were deleted
from the device DB afterwards and no payload files were kept.

## Threat model

Fixes: none in this plan.
Open: `adb`/cloud backup readers (H8 — not accepted, separate track).
Accepted residual: root or unlocked-physical file read of the
`MODE_PRIVATE` sandbox (platform control, out of scope); live-process
memory, Binder parcels to the exported `HumlaService`, bug-report
scrapes (the M9 track, untouched by anything here).

## Explicit non-goals

- No `SecretStore`/KeyStore column encryption (deferred, next section).
- No trust-store re-passwording (L1 won't-fix, next section).
- No remember-password checkbox — server passwords keep persisting as
  today behind OS lock, which is proportionate for shared low-entropy
  server passwords.
- The `backup-exclusion` branch will not be merged; no release/cloud
  retest of its rules under this plan.
- No `exported=false` or TALK-receiver changes — `se.lublin.mumla.action.TALK`
  is a documented Tasker/Automate surface, separate track with its own
  release note.
- No generated-cert re-passwording — a random PBE password the user
  doesn't know breaks `CertificateExportActivity` (exports a file the
  user can't open).
- No destructive wipe of cert blobs on any failure — the
  `certificates.data` column is `BLOB NOT NULL`; wiping is irreversible
  identity loss, worse than the accepted threat.
- No SQLCipher, no `allowBackup=false`.

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

Dropped as theater / UX tax with no boundary gain:

- Random `STORE_PASS` in a second `MODE_PRIVATE` prefs file moves the
  secret across the same UID boundary. A reader of `mumla-store.bks`
  reads the prefs file too; sandbox, SELinux, FBE, and backup-E2EE treat
  them identically. Real fix would be KeyStore-backed, not another
  `MODE_PRIVATE` file — and that is not worth it for per-host TOFU pins.
  L1 stays Info / won't-fix.
- A remember-password checkbox charges every normal-flow user (re-enter
  once, new toggle to learn) to protect a shared low-entropy server
  password that is weaker than what browsers auto-save behind OS lock.

## Verification

- None required on master (no code changed).
- The `backup-exclusion` branch passed `./scripts/check.sh` before
  abandonment; the device observation above is the record of its
  ineffectiveness on the `adb` path.
- No new unit tests; existing suite unaffected.
