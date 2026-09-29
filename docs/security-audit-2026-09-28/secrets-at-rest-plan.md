# Secrets at rest: zero-UX fix plan

Companion to [findings.md](findings.md) (C1, H6, H7, H8, M8, M9, L1) and
[notes.md](notes.md). Investigation/plan only; no code changed.

## Goal

Encrypt all at-rest secrets — client PKCS#12 blobs + passphrases, server passwords,
access tokens, TOFU trust-store password — with zero change to user-visible flows.
No new prompts, no re-entry, same screens. Only exception: restore onto a new device
re-prompts once (fail-closed, rare).

## Threat model

Fixes: `adb`/cloud backup readers, file readers, bug-report scrapes.
Does NOT fix: root with live key-use oracle, live-process memory dump. State this in
any user-facing wording; zero-UX changes cannot achieve those.

## Design

- New `db/SecretStore.java` (~150 LOC, no new dependency): AES-256-GCM key in
  AndroidKeyStore, e.g. alias `mumla-secrets`,
  `KeyGenParameterSpec(PURPOSE_ENCRYPT | PURPOSE_DECRYPT, GCM, NONE)` with
  `setUserAuthenticationRequired(false)` (mandatory — `true` means a biometric
  prompt, which is a UX change; reject), `setRandomizedEncryptionRequired(true)`,
  12-byte random IV per value, wire format `IV || ciphertext` (base64) stored in
  the existing `TEXT`/`BLOB` columns.
- Hand-rolled over `androidx.security`: no APK bloat, auditable, avoids the
  API-23-only `EncryptedSharedPreferences` trap. Tradeoff: ~150 LOC owned in-tree.
  Accept.
- API 21–22 fallback: KeyStore AES needs API 23+. Fallback is an RSA KeyStore key
  (`KeyPairGeneratorSpec`) wrapping a random AES key held in `MODE_PRIVATE`
  prefs. If that complexity bites, the alternative is a minSdk bump — a
  UX/device-drop decision, not to be snuck in here.
- Encrypt inside `MumlaSQLiteDatabase` only, so callers are untouched:
  `addServer`/`updateServer`/`getServers` (`SERVER_PASSWORD`),
  `addAccessToken`/`getAccessTokens` (`TOKENS_VALUE`),
  `addCertificate`/`getCertificateData`/`getCertificatePassword`
  (`COLUMN_CERTIFICATES_DATA`, `COLUMN_CERTIFICATES_PASSWORD`). Schema unchanged;
  `ServerConnectTask`, `HumlaService`, cert activities keep working. Non-secret
  columns (host, name) stay plaintext so lists render even if the key is
  temporarily unavailable.
- Generated certs (H7): migrate transparently — generate a random 32-char PBE
  password, re-`store()` the PKCS#12, persist the password via the encrypted
  column. Removes the empty-`""` password
  (`libraries/humla/.../net/HumlaCertificateGenerator.java`) with zero user
  visibility. RSA-2048 + `SecureRandom` strength is already fine.
- Trust store (L1): generate a random 32-byte `STORE_PASS` on first access,
  persist it in `SecretStore`-encrypted prefs; keep the
  `getTrustStorePassword()` signature unchanged. Re-`store()` the existing
  `mumla-store.bks` (`app/.../util/MumlaTrustStore.java`) under the new password
  during migration. File stays `MODE_PRIVATE`.
- Backup: keep `allowBackup=true` (preserves restore UX). Ciphertext in a backup
  is safe — the KeyStore key never backs up. On restore to a new device, decrypt
  fails, secrets read as null, user re-enters once. Better than excluding
  `mumble.db`, which would also wipe the server list and favourites.
- Migration (`onUpgrade` to DB version 10, single transaction): read each secret
  row, encrypt, update in place, `VACUUM`. An old plaintext backup restored over
  the new app re-encrypts on next `open()`. Corrupt/lost KeyStore fails closed:
  wipe ciphertext rows, regenerate the key, log and continue (one-time re-entry,
  not a brick).
- Memory/IPC hygiene, all invisible: stop putting PKCS#12 bytes/password in
  `onSaveInstanceState` (`CertificateImportActivity` — re-read the file on
  rotation), drop `exported=true` on the services/TALK receiver, stop parcelling
  passwords (`humla/.../model/Server.java`, `ServerConnectTask`) — pass certs by
  handle later. Keep the public `MumlaDatabase` returning `String` for now; push
  `char[]` + wipe inward where cheap. No UI change.

## Explicit non-goals (phase 2 or rejected)

- No biometric/device-credential gate (UX change).
- No `allowBackup=false` (would wipe server list/favourites on restore).
- No SQLCipher full-DB encryption (APK size + migration risk + query churn).
- No non-exportable KeyStore cert entries yet (breaks the export flow — visible
  UX, defer).

## Verification

- Unit test: plaintext-to-ciphertext migration round-trips every secret column.
- `adb backup` then `strings | grep` for a known password/token: must miss.
- Restore on a new device fails closed (secrets null, app usable, one re-entry).
- Manual once: connect, TOFU allow, cert import/export/generate flows unchanged.
