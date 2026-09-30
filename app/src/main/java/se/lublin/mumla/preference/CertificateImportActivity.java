/*
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.preference;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;

import se.lublin.mumla.R;
import se.lublin.mumla.Settings;
import se.lublin.mumla.db.DatabaseCertificate;
import se.lublin.mumla.db.MumlaDatabase;
import se.lublin.mumla.db.MumlaSQLiteDatabase;
import se.lublin.mumla.app.BaseActivity;

/**
 * Created by andrew on 11/01/16.
 */
public class CertificateImportActivity extends BaseActivity {
    public static final int REQUEST_FILE = 0;

    private static final String STATE_CERT_URI = "state_cert_uri";
    private static final String STATE_FILE_NAME = "state_file_name";
    private static final String STATE_IS_RETRY = "state_is_retry";
    private static final String STATE_WAITING_PASSWORD = "state_waiting_password";
    private static final int MAX_CERT_SIZE = 5 * 1024 * 1024; // 5 MB

    private static final Pattern MAC_PATTERN = Pattern.compile("\\bmac\\b", Pattern.CASE_INSENSITIVE);

    private byte[] mPendingCertBytes;
    private Uri mPendingCertUri;
    private String mPendingFileName;
    private boolean mPendingIsRetry;
    private boolean mWaitingForPassword;
    private AlertDialog mPasswordDialog;
    private TextInputLayout mPasswordLayout;
    private TextInputEditText mPasswordField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mWaitingForPassword = savedInstanceState.getBoolean(STATE_WAITING_PASSWORD);
            if (mWaitingForPassword) {
                // Rotation residual: the Bundle holds only the source Uri (plus display
                // metadata), never cert bytes or passwords. Re-read the file on restore.
                mPendingCertUri = savedInstanceState.getParcelable(STATE_CERT_URI);
                mPendingFileName = savedInstanceState.getString(STATE_FILE_NAME);
                mPendingIsRetry = savedInstanceState.getBoolean(STATE_IS_RETRY);
                if (mPendingCertUri != null && mPendingFileName != null) {
                    try {
                        mPendingCertBytes = readCertBytes(mPendingCertUri);
                    } catch (IOException e) {
                        Toast.makeText(this, R.string.invalid_certificate, Toast.LENGTH_LONG).show();
                        finish();
                        return;
                    }
                    showPasswordDialog(mPendingCertUri, mPendingFileName, mPendingCertBytes, mPendingIsRetry);
                    return;
                }
            }
        }

        Intent fileIntent = new Intent(Intent.ACTION_GET_CONTENT);
        fileIntent.setType("*/*");
        fileIntent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(fileIntent, REQUEST_FILE);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_WAITING_PASSWORD, mWaitingForPassword);
        if (mWaitingForPassword) {
            // Never persist cert bytes or passwords in the Bundle; the Uri is
            // re-read (with the size cap) when the activity is recreated.
            outState.putParcelable(STATE_CERT_URI, mPendingCertUri);
            outState.putString(STATE_FILE_NAME, mPendingFileName);
            outState.putBoolean(STATE_IS_RETRY, mPendingIsRetry);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPasswordDialog != null && mPasswordDialog.isShowing()) {
            mPasswordDialog.dismiss();
            mPasswordDialog = null;
        }
        mPasswordLayout = null;
        mPasswordField = null;
        // Drop the in-memory file bytes; the Uri in saved state is the restore path.
        mPendingCertBytes = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_FILE)
            return;

        if (resultCode == RESULT_CANCELED || data == null || data.getData() == null) {
            finish();
            return;
        }

        Uri uri = data.getData();
        byte[] certBytes;
        try {
            certBytes = readCertBytes(uri);
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, R.string.invalid_certificate, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        mPendingCertUri = uri;

        String displayName;
        Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            displayName = cursor.getString(0);
        } else {
            displayName = UUID.randomUUID().toString() + ".p12";
        }
        if (cursor != null)
            cursor.close();

        storeKeystore(new char[0], uri, displayName, certBytes, false);
    }

    /** Reads the picked certificate with the 5 MB cap; used for both first read and restore. */
    private byte[] readCertBytes(Uri uri) throws IOException {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                throw new IOException("Could not open certificate");
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            int totalBytes = 0;
            while ((read = is.read(buffer)) != -1) {
                totalBytes += read;
                if (totalBytes > MAX_CERT_SIZE) {
                    throw new IOException("Certificate file exceeds maximum allowed size");
                }
                baos.write(buffer, 0, read);
            }
            return baos.toByteArray();
        }
    }

    private void storeKeystore(final char[] password, final Uri sourceUri, final String fileName,
                               final byte[] certBytes, final boolean isRetry) {
        KeyStore keyStore;
        try (ByteArrayInputStream input = new ByteArrayInputStream(certBytes)) {
            keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(input, password);
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (keyStore.isKeyEntry(alias)) {
                    Key key = keyStore.getKey(alias, password);
                    if (key == null) {
                        throw new UnrecoverableKeyException("Key could not be recovered for alias " + alias);
                    }
                }
            }
        } catch (Exception e) {
            if (isPasswordFailure(e)) {
                zeroPassword(password);
                showPasswordDialog(sourceUri, fileName, certBytes, isRetry);
            } else {
                e.printStackTrace();
                if (mPasswordDialog != null) {
                    mPasswordDialog.dismiss();
                    mPasswordDialog = null;
                }
                mPasswordLayout = null;
                mPasswordField = null;
                zeroPassword(password);
                Toast.makeText(this, R.string.invalid_certificate, Toast.LENGTH_LONG).show();
                mWaitingForPassword = false;
                finish();
            }
            return;
        }

        mWaitingForPassword = false;
        if (mPasswordDialog != null) {
            mPasswordDialog.dismiss();
            mPasswordDialog = null;
        }
        mPasswordLayout = null;
        mPasswordField = null;
        // DB boundary only: the at-rest store keeps a String password by design
        // (secrets-at-rest-plan), so convert here and zero the char[] immediately.
        String passwordStr = (password != null && password.length > 0) ? new String(password) : null;
        zeroPassword(password);
        MumlaDatabase database = new MumlaSQLiteDatabase(this);
        DatabaseCertificate certificate = database.addCertificate(fileName, certBytes, passwordStr);
        database.close();

        if (certificate != null && certificate.getId() >= 0) {
            Settings settings = Settings.getInstance(this);
            settings.setDefaultCertificateId(certificate.getId());
            Toast.makeText(this, getString(R.string.certificate_import_success, fileName),
                    Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, R.string.certificate_load_failed, Toast.LENGTH_LONG).show();
        }

        finish();
    }

    private void showPasswordDialog(final Uri sourceUri, final String fileName,
                                    final byte[] certBytes, final boolean isRetry) {
        mWaitingForPassword = true;
        mPendingCertUri = sourceUri;
        mPendingFileName = fileName;
        mPendingCertBytes = certBytes;
        mPendingIsRetry = isRetry;

        if (mPasswordDialog != null && mPasswordDialog.isShowing() && mPasswordLayout != null && mPasswordField != null) {
            if (isRetry) {
                // Never refill a previous password: the user re-enters it.
                mPasswordLayout.setError(getString(R.string.invalid_password));
                mPasswordField.requestFocus();
            }
            return;
        }

        if (mPasswordDialog != null && mPasswordDialog.isShowing()) {
            mPasswordDialog.dismiss();
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        LayoutInflater inflater = LayoutInflater.from(builder.getContext());
        View dialogView = inflater.inflate(R.layout.dialog_certificate_password, null);
        mPasswordLayout = dialogView.findViewById(R.id.certificate_password_layout);
        mPasswordField = dialogView.findViewById(R.id.certificate_password_field);

        if (isRetry) {
            mPasswordLayout.setError(getString(R.string.invalid_password));
        }

        mPasswordField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (mPasswordLayout != null && mPasswordLayout.getError() != null) {
                    mPasswordLayout.setError(null);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        Runnable submitAction = () -> {
            if (mPasswordField == null) {
                return;
            }
            // char[] from the field through KeyStore.load; never retain the String copy.
            Editable text = mPasswordField.getText();
            String entered = text != null ? text.toString() : "";
            char[] passChars = entered.toCharArray();
            try {
                storeKeystore(passChars, sourceUri, fileName, certBytes, true);
            } finally {
                Arrays.fill(passChars, '\0');
            }
        };

        mPasswordField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                submitAction.run();
                return true;
            }
            return false;
        });

        mPasswordDialog = builder
                .setTitle(R.string.decrypt_certificate)
                .setView(dialogView)
                .setOnCancelListener(dialog -> {
                    mWaitingForPassword = false;
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    mWaitingForPassword = false;
                    finish();
                })
                .setPositiveButton(android.R.string.ok, null)
                .create();

        mPasswordDialog.setOnShowListener(dialog -> {
            Button positiveButton = mPasswordDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (positiveButton != null) {
                positiveButton.setOnClickListener(v -> submitAction.run());
            }
        });

        if (mPasswordDialog.getWindow() != null) {
            mPasswordDialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        mPasswordDialog.show();
        mPasswordField.requestFocus();
    }

    private static void zeroPassword(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }

    static boolean isPasswordFailure(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof CertificateException ||
                cur instanceof NoSuchAlgorithmException) {
                return false;
            }
            if (cur instanceof UnrecoverableKeyException ||
                cur instanceof BadPaddingException ||
                cur instanceof AEADBadTagException) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("password") ||
                    MAC_PATTERN.matcher(msg).find() ||
                    lower.contains("decrypt") ||
                    lower.contains("padding") ||
                    lower.contains("bad key") ||
                    lower.contains("key failed")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }
}
