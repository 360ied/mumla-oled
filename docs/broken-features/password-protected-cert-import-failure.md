# Broken Feature: Password-Protected Certificate Import Fails Immediately

**Status:** confirmed bug  
**Severity:** high (breaks core authentication feature)  
**Component:** `app` Security / Certificate Management  
**Files Affected:**
- [`CertificateImportActivity.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/preference/CertificateImportActivity.java)

---

## 1. Problem Description

Mumla allows users to import PKCS#12 (`.p12` / `.pfx`) user certificates for client authentication. When an imported certificate file is protected by a passphrase, the import process immediately fails with an "Invalid certificate" Toast notification, and the user is never prompted to enter the password.

As a consequence, **no password-encrypted certificate can be imported into Mumla**.

---

## 2. Technical Root Cause

In `CertificateImportActivity.java` (lines 112–135), `storeKeystore()` attempts to open the keystore with an empty password (`new char[0]`):

```java
private void storeKeystore(final char[] password, final String fileName, final byte[] certBytes) {
    KeyStore keyStore;
    try (ByteArrayInputStream input = new ByteArrayInputStream(certBytes)) {
        keyStore = KeyStore.getInstance("PKCS12", new BouncyCastleProvider());
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
   In Java / BouncyCastle (`PKCS12KeyStoreSpi`), when a PKCS#12 keystore requires a password or when the supplied password fails MAC verification, `keyStore.load()` throws `java.io.IOException: PKCS12 key store mac invalid - wrong password or corrupted file`.
   `CertificateException` is only thrown if individual X.509 certificates inside the keystore fail ASN.1 decoding.
2. **Falling into the Generic Abort Catch Block:**
   Because `keyStore.load()` throws an `IOException`, execution never enters `catch (CertificateException e)`. Instead, it falls into `catch (KeyStoreException|IOException|NoSuchAlgorithmException e)`, which toasts `R.string.invalid_certificate` and immediately finishes the activity.
3. **Plaintext Password Input Masking Glitch:**
   `passwordField.setInputType(InputType.TYPE_TEXT_VARIATION_PASSWORD)` sets the variation without `InputType.TYPE_CLASS_TEXT`. On Android, `TYPE_TEXT_VARIATION_PASSWORD` alone does not reliably mask user input into dots/asterisks unless combined with `TYPE_CLASS_TEXT`.

---

## 3. Remediation Plan

1. **Distinguish Password Failures from Corrupted Files:**
   In `storeKeystore()`, catch `IOException` and inspect the exception message/cause (or check if `password.length == 0` on first load). If password decryption failed, trigger the password prompt dialog.
2. **Proper Input Masking:**
   Set `passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)`.
3. **Password Retry Feedback:**
   If a user submits an incorrect password in the prompt dialog, distinguish between initial load and subsequent bad password attempts so the user can be informed that the entered password was incorrect.
