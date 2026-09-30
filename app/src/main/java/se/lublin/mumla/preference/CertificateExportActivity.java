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

import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument;
import androidx.documentfile.provider.DocumentFile;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

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

    private static final String STATE_PENDING_CERT_ID = "state_pending_cert_id";
    private static final String P12_SUFFIX = ".p12";
    /** Maximum UTF-16 units kept in the filename base (sans extension) for the SAF suggestion. */
    private static final int MAX_EXPORT_BASENAME = 64;
    /** Control characters plus the characters reserved by common filesystems. */
    private static final Pattern INVALID_FILENAME_CHARS = Pattern.compile("[\\x00-\\x1f\\x7f:*?\"<>|]");
    /** Windows device names, matched against the part of the name before the first dot. */
    private static final Pattern WINDOWS_RESERVED_NAME =
            Pattern.compile("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$", Pattern.CASE_INSENSITIVE);

    private MumlaDatabase mDatabase;
    private List<DatabaseCertificate> mCertificates;
    private DatabaseCertificate mCertificatePending = null;

    private final ActivityResultLauncher<String> documentCreator =
            registerForActivityResult(new CreateDocument(), this::onDocumentCreated);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDatabase = new MumlaSQLiteDatabase(this);
        mCertificates = mDatabase.getCertificates();

        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_PENDING_CERT_ID)) {
            // Recreated while the document picker was open: its result is delivered to
            // the new instance, which must still know which certificate to write.
            long pendingId = savedInstanceState.getLong(STATE_PENDING_CERT_ID);
            for (DatabaseCertificate certificate : mCertificates) {
                if (certificate.getId() == pendingId) {
                    mCertificatePending = certificate;
                    break;
                }
            }
            return;
        }

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
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mCertificatePending != null) {
            outState.putLong(STATE_PENDING_CERT_ID, mCertificatePending.getId());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mDatabase.close();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        // The tap plus the SAF picker is the consent.
        mCertificatePending = mCertificates.get(which);
        documentCreator.launch(sanitizeExportFilename(mCertificatePending.getName()));
    }

    private static String randomExportName() {
        return UUID.randomUUID().toString() + P12_SUFFIX;
    }

    /**
     * Sanitizes a certificate name into a safe SAF suggestion with a {@code .p12} suffix.
     *
     * <p>Steps: trim; take the trailing path segment (splitting on slash/backslash) so
     * traversal prefixes cannot survive; replace control characters and
     * {@code : * ? " < > |} with {@code '_'}; strip leading dots (hidden-file/traversal
     * remnants); drop a trailing {@code .p12} or any other single extension; strip trailing
     * dots and spaces; truncate the stem to 64 UTF-16 units without splitting a surrogate
     * pair; prefix {@code '_'} when the part of the stem before its first dot is a Windows
     * reserved device name; append {@code .p12}. Blank input (or nothing left after
     * sanitizing) falls back to {@code <random-uuid>.p12}.
     */
    static String sanitizeExportFilename(String name) {
        String trimmed = name == null ? "" : name.trim();
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
        basename = INVALID_FILENAME_CHARS.matcher(basename).replaceAll("_");
        int start = 0;
        while (start < basename.length() && basename.charAt(start) == '.') {
            start++;
        }
        String stem = basename.substring(start);

        if (stem.toLowerCase(Locale.ROOT).endsWith(P12_SUFFIX)) {
            stem = stem.substring(0, stem.length() - P12_SUFFIX.length());
        } else {
            int extDot = stem.lastIndexOf('.');
            if (extDot > 0) {
                stem = stem.substring(0, extDot);
            }
        }
        stem = stripTrailingDotsAndSpaces(stem);
        if (stem.length() > MAX_EXPORT_BASENAME) {
            int cut = MAX_EXPORT_BASENAME;
            if (Character.isHighSurrogate(stem.charAt(cut - 1))) {
                cut--;
            }
            stem = stripTrailingDotsAndSpaces(stem.substring(0, cut));
        }
        if (stem.isEmpty()) {
            return randomExportName();
        }

        int firstDot = stem.indexOf('.');
        String deviceName = stripTrailingDotsAndSpaces(firstDot < 0 ? stem : stem.substring(0, firstDot));
        if (WINDOWS_RESERVED_NAME.matcher(deviceName).matches()) {
            stem = "_" + stem;
        }
        return stem + P12_SUFFIX;
    }

    private static String stripTrailingDotsAndSpaces(String value) {
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == '.' || value.charAt(end - 1) == ' ')) {
            end--;
        }
        return value.substring(0, end);
    }

    private void onDocumentCreated(Uri uri) {
        if (uri == null) {
            // User cancelled the picker.
        } else if (mCertificatePending == null) {
            Log.w(TAG, "No pending certificate after user picked output file");
        } else {
            DocumentFile df = DocumentFile.fromSingleUri(this, uri);
            writeCertificate(uri, mCertificatePending, df != null ? df.getName() : "<unknown>");
        }
        mCertificatePending = null;
        finish();
    }

    private void writeCertificate(Uri uri, DatabaseCertificate cert, String displayName) {
        byte[] data = mDatabase.getCertificateData(cert.getId());
        if (data == null) {
            Log.w(TAG, "Certificate data missing for id " + cert.getId());
            showError(R.string.error_writing_to_storage);
            return;
        }
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) {
                Log.w(TAG, "No output stream for document picked by user");
                showError(R.string.error_writing_to_storage);
                return;
            }
            os.write(data);
            os.flush();
        } catch (IOException e) {
            Log.w(TAG, "Failed to write exported certificate", e);
            showError(R.string.error_writing_to_storage);
            return;
        }
        Toast.makeText(this, getString(R.string.export_success, displayName), Toast.LENGTH_LONG).show();
    }

    /** Toast rather than a dialog: the activity finishes right after the export attempt. */
    private void showError(int resourceId) {
        Toast.makeText(this, resourceId, Toast.LENGTH_LONG).show();
    }
}
