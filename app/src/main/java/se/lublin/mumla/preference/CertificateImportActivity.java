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
import android.util.Log;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.spongycastle.jce.provider.BouncyCastleProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.Enumeration;
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
    private static final String TAG = CertificateImportActivity.class.getName();
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
        byte[] fileBytes;
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                Toast.makeText(this, R.string.certificate_load_failed, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                buffer.write(buf, 0, n);
            }
            fileBytes = buffer.toByteArray();
        } catch (IOException e) {
            Log.e(TAG, "Failed to read certificate uri: " + uri, e);
            Toast.makeText(this, R.string.certificate_load_failed, Toast.LENGTH_LONG).show();
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

        storeKeystore(new char[0], displayName, fileBytes);
    }

    private void storeKeystore(final char[] password, final String fileName, final byte[] fileBytes) {
        KeyStore keyStore = null;
        boolean loaded = false;
        Exception lastException = null;

        // 1. Try standard platform PKCS12 provider first (handles modern PBES2 PKCS12)
        try {
            keyStore = KeyStore.getInstance("PKCS12");
            keyStore.load(new ByteArrayInputStream(fileBytes), password);
            loaded = true;
        } catch (Exception e) {
            lastException = e;
        }

        // 2. Fall back to SpongyCastle / BouncyCastle provider if platform provider fails
        if (!loaded) {
            try {
                keyStore = KeyStore.getInstance("PKCS12", new BouncyCastleProvider());
                keyStore.load(new ByteArrayInputStream(fileBytes), password);
                loaded = true;
            } catch (Exception e) {
                lastException = e;
            }
        }

        if (!loaded) {
            // If failed with empty password, prompt user for password
            if (password == null || password.length == 0) {
                final EditText passwordField = new EditText(this);
                passwordField.setHint(R.string.password);
                passwordField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                new MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.decrypt_certificate)
                        .setView(passwordField)
                        .setOnCancelListener(dialog -> finish())
                        .setPositiveButton(android.R.string.ok, (dialog, which) ->
                                storeKeystore(passwordField.getText().toString().toCharArray(), fileName, fileBytes))
                        .show();
                return;
            } else {
                Log.e(TAG, "Failed to load PKCS12 with supplied password", lastException);
                Toast.makeText(this, R.string.invalid_certificate, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            // Create a clean PKCS12 keystore so that private keys are re-stored unencrypted for internal use
            KeyStore cleanKeyStore = KeyStore.getInstance("PKCS12", new BouncyCastleProvider());
            cleanKeyStore.load(null, null);

            Enumeration<String> aliases = keyStore.aliases();
            boolean hasKeys = false;
            while (aliases != null && aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (keyStore.isKeyEntry(alias)) {
                    Key key = null;
                    try {
                        key = keyStore.getKey(alias, password);
                    } catch (Exception e) {
                        try {
                            key = keyStore.getKey(alias, new char[0]);
                        } catch (Exception ignored) {}
                    }
                    if (key != null) {
                        Certificate[] chain = keyStore.getCertificateChain(alias);
                        cleanKeyStore.setKeyEntry(alias, key, null, chain);
                        hasKeys = true;
                    }
                } else if (keyStore.isCertificateEntry(alias)) {
                    Certificate cert = keyStore.getCertificate(alias);
                    cleanKeyStore.setCertificateEntry(alias, cert);
                }
            }

            if (hasKeys) {
                cleanKeyStore.store(output, "".toCharArray());
            } else {
                keyStore.store(output, new char[0]);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to re-store clean keystore", e);
            Toast.makeText(this, R.string.certificate_load_failed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        MumlaDatabase database = new MumlaSQLiteDatabase(this);
        DatabaseCertificate certificate = database.addCertificate(fileName, output.toByteArray());
        database.close();

        if (certificate != null) {
            Settings settings = Settings.getInstance(this);
            settings.setDefaultCertificateId(certificate.getId());
        }

        Toast.makeText(this, getString(R.string.certificate_import_success, fileName),
                       Toast.LENGTH_LONG).show();
        finish();
    }
}
