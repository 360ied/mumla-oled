# Bug: Certificate Export Error Dialog Dismissed Instantly by Premature Activity Finish

**Status:** confirmed bug  
**Severity:** medium (swallows error messages and prevents user troubleshooting)  
**Component:** `app` Preference / Certificate Management  
**Files Affected:**
- [`CertificateExportActivity.java`](file:///home/bualy/files/devel/mumla_dev/mumla-oled/app/src/main/java/se/lublin/mumla/preference/CertificateExportActivity.java)

---

## 1. Problem Description

When an error occurs during certificate export (for example, storage write failures or I/O exceptions), the error dialog intended to notify the user is shown and immediately destroyed before it can render on screen. The user is returned to the preferences screen with no indication of what went wrong.

---

## 2. Technical Root Cause

In `CertificateExportActivity.java`, both the Storage Access Framework callback (`onDocumentCreated`) and the classic external storage path (`saveCertificateClassic`) call `finish()` unconditionally:

```java
private void onDocumentCreated(Uri uri) {
    if (uri != null && mCertificatePending != null) {
        try {
            OutputStream os = getContentResolver().openOutputStream(uri);
            DocumentFile df = DocumentFile.fromSingleUri(this, uri);
            writeCertificate(os, mCertificatePending, df != null ? df.getName() : "<unknown>");
        } catch (FileNotFoundException e) {
            showErrorDialog(R.string.externalStorageUnavailable);
            Log.w(TAG, "FileNotFound on output file picked by user?!");
        }
    } else if (mCertificatePending == null) {
        Log.w(TAG, "No pending certificate after user picked output file");
    }
    finish(); // Destroys the activity immediately!
}
```

Inside `writeCertificate()`:

```java
private void writeCertificate(OutputStream fos, DatabaseCertificate cert, String path) {
    byte[] data = mDatabase.getCertificateData(cert.getId());
    try {
        BufferedOutputStream bos = new BufferedOutputStream(fos);
        bos.write(data);
        bos.close();
        Toast.makeText(this, getString(R.string.export_success, path), Toast.LENGTH_LONG).show();
    } catch (IOException e) {
        e.printStackTrace();
        showErrorDialog(R.string.error_writing_to_storage);
    }
}
```

`showErrorDialog()` creates and calls `.show()` on a `MaterialAlertDialogBuilder`:

```java
private void showErrorDialog(int resourceId) {
    new MaterialAlertDialogBuilder(this)
            .setMessage(resourceId)
            .setPositiveButton(android.R.string.ok, null)
            .show();
}
```

Because `finish()` is invoked immediately after `writeCertificate()`, Android destroys `CertificateExportActivity` synchronously in the same turn. The dialog's host window is torn down before the user can see or acknowledge the dialog.

---

## 3. Remediation Plan

1. **Defer `finish()` on Errors:**
   Modify `showErrorDialog()` to listen for dialog dismissal:
   ```java
   private void showErrorDialog(int resourceId) {
       new MaterialAlertDialogBuilder(this)
               .setMessage(resourceId)
               .setPositiveButton(android.R.string.ok, null)
               .setOnDismissListener(dialog -> finish())
               .show();
   }
   ```
2. **Remove Unconditional `finish()` on Failure Paths:**
   Ensure that `finish()` is only called immediately when the export operation succeeds, or let dialog dismissal trigger completion.
