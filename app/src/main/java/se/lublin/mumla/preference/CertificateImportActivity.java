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
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
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

        storeKeystore(new char[0], displayName, certBytes);
    }

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

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            keyStore.store(output, new char[0]);
        } catch (KeyStoreException|IOException|NoSuchAlgorithmException|CertificateException e) {
            e.printStackTrace();
            Toast.makeText(this, R.string.certificate_load_failed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        MumlaDatabase database = new MumlaSQLiteDatabase(this);
        DatabaseCertificate certificate = database.addCertificate(fileName, output.toByteArray());
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
}
