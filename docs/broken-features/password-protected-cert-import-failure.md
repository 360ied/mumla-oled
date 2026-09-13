# Broken Feature: Password-Protected Certificate Import Fails Immediately

**Status:** resolved  
**Severity:** high (breaks core authentication feature)  
**Component:** `app` Security / Certificate Management  
**Files Affected:**
- [`CertificateImportActivity.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/preference/CertificateImportActivity.java)
- [`MumlaDatabase.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/db/MumlaDatabase.java)
- [`MumlaSQLiteDatabase.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/db/MumlaSQLiteDatabase.java)
- [`ServerConnectTask.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/app/ServerConnectTask.java)

---

## 1. Problem Description

Mumla allows users to import PKCS#12 (`.p12` / `.pfx`) user certificates for client authentication. When an imported certificate file is protected by a passphrase, the import process immediately fails with an "Invalid certificate" Toast notification, and the user is never prompted to enter the password.

As a consequence, **no password-encrypted certificate can be imported into Mumla**.

---

## 2. Technical Root Cause

In `CertificateImportActivity.java` (lines 110–133), `storeKeystore()` attempts to open the keystore with an empty password (`new char[0]`):

```java
private void storeKeystore(final char[] password, final String fileName, final byte[] certBytes) {
    KeyStore keyStore;
    try (ByteArrayInputStream input = new ByteArrayInputStream(certBytes)) {
        keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(input, password);
    } catch (CertificateException e) {
        final EditText passwordField = new EditText(this);
        passwordField.setHint(R.string.password);
        passwordField.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD);
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.decrypt_certificate)
                .setView(passwordField)
                .setOnCancelListener(dialog -> finish())
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        storeKeystore(passwordField.getText().toString().toCharArray(), fileName, certBytes))
                .show();
        return;
    } catch (KeyStoreException|IOException|NoSuchAlgorithmException e) {
        e.printStackTrace();
        Toast.makeText(this, R.string.invalid_certificate, Toast.LENGTH_LONG).show();
        finish();
        return;
    }
```

1. **Incorrect Exception Expected for Password Failures:**
   In standard Java / Android JCA (`sun.security.pkcs12.PKCS12KeyStore` or Conscrypt / AndroidOpenSSL), when a PKCS#12 keystore requires a password or when the supplied password fails MAC verification or SafeContents decryption, `keyStore.load()` throws `java.io.IOException: keystore password was incorrect` with cause `java.security.UnrecoverableKeyException` (wrapping `javax.crypto.BadPaddingException`).
   *(Historically prior to 0.18.5, BouncyCastle's `PKCS12KeyStoreSpi` likewise threw `java.io.IOException: PKCS12 key store mac invalid - wrong password or corrupted file`.)*
   Across all standard PKCS#12 provider implementations, `CertificateException` is strictly reserved for individual X.509 certificate decoding/parsing errors, and is **never** thrown for missing or incorrect passwords.
2. **Falling into the Generic Abort Catch Block:**
   Because `keyStore.load()` throws an `IOException`, execution never enters `catch (CertificateException e)`. Instead, it falls into `catch (KeyStoreException|IOException|NoSuchAlgorithmException e)`, which toasts `R.string.invalid_certificate` and immediately finishes the activity.
3. **Plaintext Password Input Masking Glitch:**
   `passwordField.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD)` sets the variation without `InputType.TYPE_CLASS_TEXT`. On Android, `TYPE_TEXT_VARIATION_PASSWORD` alone does not reliably mask user input into dots/asterisks unless combined with `TYPE_CLASS_TEXT`.

---

## 3. Remediation Plan

1. **Distinguish Password Failures from Corrupted Files:**
   In `storeKeystore()`, catch `IOException` and check whether decryption failed due to a missing or wrong password:
   - On initial load (`password.length == 0`): trigger the password prompt dialog.
   - On retry with a user-provided password: check if the failure is cryptographic (e.g. `e.getCause() instanceof UnrecoverableKeyException`, `e.getCause() instanceof GeneralSecurityException`, or exception message indicating wrong password / MAC failure). If so, inform the user that the entered password was incorrect and reprompt rather than immediately aborting.
   - If the file is unparseable or corrupted (e.g., malformed DER tags, invalid stream header), toast `R.string.invalid_certificate` and finish.
2. **Proper Input Masking:**
   Set `passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)`.
3. **Password Retry Feedback:**
   If a user submits an incorrect password in the prompt dialog, distinguish between initial load and subsequent bad password attempts so the user can be informed that the entered password was incorrect (e.g., showing helper/error text on the dialog).

---

## 4. Resolution

1. **Cryptographic Error Discrimination:**
   Added package-private `CertificateImportActivity.isPasswordFailure(Throwable t)` which checks for `UnrecoverableKeyException`, `BadPaddingException`, `AEADBadTagException`, or regex message matching (`\bmac\b`, decrypt, padding, key failure) across JVM and Android JCA providers. Explicitly excludes `CertificateException` and `NoSuchAlgorithmException` so corrupted or unsupported certificates fail fast with `R.string.invalid_certificate` rather than getting stuck in retry loops.
2. **Decryption Dialog, Theming & Lifecycle:**
   Adopted `TextInputLayout` (`dialog_certificate_password.xml`) styled with `Widget.Material3.TextInputLayout.OutlinedBox` and `endIconMode="password_toggle"`. Displaying errors via `passwordLayout.setError(...)` renders high-contrast inline error text in `colorError` directly below the input field, eliminating the framework `EditText.setError()` popup bubble which suffered from unreadable white-on-white text in dark and OLED themes. Configured `EditorInfo.IME_ACTION_DONE` for soft keyboard submission, ensured soft input visibility, zeroized in-memory password character buffers, enforced a 5 MB file size boundary, and preserved pending certificate state across configuration changes/rotations via `onSaveInstanceState()`.
3. **Bit-Identical Round-Trips & Database Schema:**
   Bumped SQLite database version from 8 to 9 (`CURRENT_DB_VERSION = 9`), adding an optional `password TEXT` column to `certificates`. Guarded upgrade step with `else if` to prevent duplicate column crashes when upgrading from schema versions $\le 7$. Raw imported `.p12` bytes are stored verbatim without re-encoding, preserving exact cryptographic attributes and guaranteeing bit-identical certificate export round-trips. Wrapped database cursor queries in try-with-resources.
4. **Service & Connection Integration:**
   `ServerConnectTask` fetches the certificate password from `MumlaDatabase` and passes `HumlaService.EXTRAS_CERTIFICATE_PASSWORD` to `HumlaService`, which forwards it to `HumlaConnection` and `HumlaSSLSocketFactory` during TLS handshakes.
5. **Unit Tests:**
   Added unit test suites in `CertificateImportTest.java` (testing positive and negative cryptographic exception discrimination including regex word boundary and corrupt certificate exclusion) and `MumlaDatabaseTest.java` (verifying database certificate and password handling).
