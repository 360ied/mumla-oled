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
import android.text.InputType;
import android.util.TypedValue;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;
import java.util.Enumeration;
import java.util.Locale;
import java.util.UUID;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent fileIntent = new Intent(Intent.ACTION_GET_CONTENT);
        fileIntent.setType("*/*");
        fileIntent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(fileIntent, REQUEST_FILE);
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
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                finish();
                return;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = is.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            certBytes = baos.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, R.string.invalid_certificate, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        String displayName;
        Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            displayName = cursor.getString(0);
        } else {
            displayName = UUID.randomUUID().toString() + ".p12";
        }
        if (cursor != null)
            cursor.close();

        storeKeystore(new char[0], displayName, certBytes, false, null);
    }

    private void storeKeystore(final char[] password, final String fileName, final byte[] certBytes,
                               final boolean isRetry, final String previousPassword) {
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
                showPasswordDialog(fileName, certBytes, isRetry, previousPassword);
            } else {
                e.printStackTrace();
                Toast.makeText(this, R.string.invalid_certificate, Toast.LENGTH_LONG).show();
                finish();
            }
            return;
        }

        String passwordStr = (password != null && password.length > 0) ? new String(password) : null;
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

    private void showPasswordDialog(final String fileName, final byte[] certBytes,
                                    final boolean isRetry, final String previousPassword) {
        final FrameLayout container = new FrameLayout(this);
        final EditText passwordField = new EditText(this);
        passwordField.setHint(R.string.password);
        passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        int horizontalPadding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
        int verticalPadding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 16, getResources().getDisplayMetrics());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = horizontalPadding;
        params.rightMargin = horizontalPadding;
        params.topMargin = verticalPadding;
        params.bottomMargin = verticalPadding;
        passwordField.setLayoutParams(params);
        container.addView(passwordField);

        if (isRetry) {
            if (previousPassword != null) {
                passwordField.setText(previousPassword);
                passwordField.selectAll();
            }
            passwordField.setError(getString(R.string.invalid_password));
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.decrypt_certificate)
                .setView(container)
                .setOnCancelListener(dialog -> finish())
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String entered = passwordField.getText().toString();
                    storeKeystore(entered.toCharArray(), fileName, certBytes, true, entered);
                })
                .show();
    }

    static boolean isPasswordFailure(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof UnrecoverableKeyException ||
                cur instanceof GeneralSecurityException) {
                return true;
            }
            String msg = cur.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("password") ||
                    lower.contains("mac") ||
                    lower.contains("decrypt") ||
                    lower.contains("padding") ||
                    lower.contains("bad key")) {
                    return true;
                }
            }
            cur = cur.getCause();
        }
        return false;
    }
}
