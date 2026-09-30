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

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import android.content.DialogInterface;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import se.lublin.mumla.R;
import se.lublin.mumla.db.DatabaseCertificate;
import se.lublin.mumla.db.MumlaDatabase;
import se.lublin.mumla.db.MumlaSQLiteDatabase;
import se.lublin.mumla.app.BaseActivity;

/**
 * Created by andrew on 12/01/16.
 */
public class CertificateExportActivity extends BaseActivity implements DialogInterface.OnClickListener {
    private static final String TAG = CertificateExportActivity.class.getName();

    private MumlaDatabase mDatabase;
    private List<DatabaseCertificate> mCertificates;

    private final ActivityResultLauncher<String> documentCreator =
            registerForActivityResult(new CreateDocument(), this::onDocumentCreated);

    /** Maximum characters kept in the filename base (sans extension) for the SAF suggestion. */
    private static final int MAX_EXPORT_BASENAME = 64;
    private static final Pattern WINDOWS_RESERVED_NAME =
            Pattern.compile("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$", Pattern.CASE_INSENSITIVE);
    private DatabaseCertificate mCertificatePending = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDatabase = new MumlaSQLiteDatabase(this);
        mCertificates = mDatabase.getCertificates();

        CharSequence[] labels = new CharSequence[mCertificates.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = mCertificates.get(i).getName();
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pref_export_certificate_title)
                .setItems(labels, this)
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mDatabase.close();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        DatabaseCertificate certificate = mCertificates.get(which);
        // SAF-only export on all API levels: the tap plus the SAF picker is the consent.
        mCertificatePending = certificate;
        documentCreator.launch(sanitizeExportFilename(certificate.getName()));
    }

    /**
     * Sanitizes a certificate name into a safe SAF suggestion with a {@code .p12} suffix.
     *
     * <p>Steps: trim; take the trailing path segment (splitting on slash/backslash) so
     * traversal prefixes cannot survive; replace backslash/slash/colon/NUL with {@code '_'};
     * strip leading dots (hidden-file/traversal remnants); prefix {@code '_'} when the base
     * (sans extension) is a Windows reserved name; truncate the base to 64 chars; enforce a
     * {@code .p12} suffix by appending or replacing any other extension. Blank input (or
     * nothing left after sanitizing) falls back to {@code <random-uuid>.p12}.
     */
    static String sanitizeExportFilename(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            return UUID.randomUUID().toString() + ".p12";
        }
        // Take the trailing segment so "../../x" suggests "x", not a mangled prefix.
        String[] segments = trimmed.split("[\\\\/]+");
        String basename = "";
        for (int i = segments.length - 1; i >= 0; i--) {
            String seg = segments[i].trim();
            if (!seg.isEmpty() && !seg.equals(".") && !seg.equals("..")) {
                basename = seg;
                break;
            }
        }
        if (basename.isEmpty()) {
            return UUID.randomUUID().toString() + ".p12";
        }
        basename = basename.replace('\\', '_').replace('/', '_')
                .replace(':', '_').replace('\0', '_');
        while (basename.startsWith(".")) {
            basename = basename.substring(1);
        }
        if (basename.isEmpty()) {
            return UUID.randomUUID().toString() + ".p12";
        }
        String baseSansExt = basename;
        int dot = basename.lastIndexOf('.');
        if (dot > 0) {
            baseSansExt = basename.substring(0, dot);
        }
        if (WINDOWS_RESERVED_NAME.matcher(baseSansExt).matches()) {
            basename = "_" + basename;
        }
        String lower = basename.toLowerCase(Locale.ROOT);
        String basePart;
        if (lower.endsWith(".p12")) {
            basePart = basename.substring(0, basename.length() - 4);
        } else {
            int extDot = basename.lastIndexOf('.');
            basePart = extDot > 0 ? basename.substring(0, extDot) : basename;
        }
        if (basePart.length() > MAX_EXPORT_BASENAME) {
            basePart = basePart.substring(0, MAX_EXPORT_BASENAME);
        }
        if (basePart.isEmpty()) {
            return UUID.randomUUID().toString() + ".p12";
        }
        return basePart + ".p12";
    }

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
        finish();
    }

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

    private void showErrorDialog(int resourceId) {
        new MaterialAlertDialogBuilder(this)
                .setMessage(resourceId)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
