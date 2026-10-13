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

package se.lublin.mumla.preference

import android.content.DialogInterface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.regex.Pattern
import se.lublin.mumla.R
import se.lublin.mumla.app.BaseActivity
import se.lublin.mumla.db.DatabaseCertificate
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.MumlaRoomDatabase

/**
 * Created by andrew on 12/01/16.
 */
class CertificateExportActivity : BaseActivity(), DialogInterface.OnClickListener {

    private var mDatabase: MumlaDatabase? = null
    private var mCertificates: List<DatabaseCertificate>? = null
    private var mCertificatePending: DatabaseCertificate? = null

    private val documentCreator =
        registerForActivityResult(CreateDocument(), this::onDocumentCreated)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = MumlaRoomDatabase.getInstance(this)
        mDatabase = database
        val certificates = database.getCertificates()
        mCertificates = certificates

        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_PENDING_CERT_ID)) {
            // Recreated while the document picker was open: its result is delivered to
            // the new instance, which must still know which certificate to write.
            val pendingId = savedInstanceState.getLong(STATE_PENDING_CERT_ID)
            for (certificate in certificates) {
                if (certificate.id == pendingId) {
                    mCertificatePending = certificate
                    break
                }
            }
            return
        }

        val labels = arrayOfNulls<CharSequence>(certificates.size)
        for (i in labels.indices) {
            labels[i] = certificates[i].name
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pref_export_certificate_title)
            .setItems(labels, this)
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val pending = mCertificatePending
        if (pending != null) {
            outState.putLong(STATE_PENDING_CERT_ID, pending.id)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // NPE-exact: the original dereferenced mDatabase unconditionally.
        val database = mDatabase ?: throw NullPointerException("mDatabase")
        database.close()
    }

    override fun onClick(dialog: DialogInterface?, which: Int) {
        // The tap plus the SAF picker is the consent.
        // NPE-exact: the original dereferenced mCertificates unconditionally.
        val certificates = mCertificates ?: throw NullPointerException("mCertificates")
        val pending = certificates[which]
        mCertificatePending = pending
        documentCreator.launch(sanitizeExportFilename(pending.name))
    }

    private fun onDocumentCreated(uri: Uri?) {
        if (uri == null) {
            // User cancelled the picker.
        } else if (mCertificatePending == null) {
            Log.w(TAG, "No pending certificate after user picked output file")
        } else {
            val pending = mCertificatePending ?: throw NullPointerException("mCertificatePending")
            val df = DocumentFile.fromSingleUri(this, uri)
            writeCertificate(uri, pending, df?.name ?: "<unknown>")
        }
        mCertificatePending = null
        finish()
    }

    private fun writeCertificate(uri: Uri, cert: DatabaseCertificate, displayName: String) {
        // NPE-exact: the original dereferenced mDatabase unconditionally.
        val database = mDatabase ?: throw NullPointerException("mDatabase")
        val data = database.getCertificateData(cert.id)
        if (data == null) {
            Log.w(TAG, "Certificate data missing for id " + cert.id)
            showError(R.string.error_writing_to_storage)
            return
        }
        try {
            contentResolver.openOutputStream(uri).use { os ->
                if (os == null) {
                    Log.w(TAG, "No output stream for document picked by user")
                    showError(R.string.error_writing_to_storage)
                    return
                }
                os.write(data)
                os.flush()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to write exported certificate", e)
            showError(R.string.error_writing_to_storage)
            return
        }
        Toast.makeText(this, getString(R.string.export_success, displayName), Toast.LENGTH_LONG).show()
    }

    /** Toast rather than a dialog: the activity finishes right after the export attempt. */
    private fun showError(resourceId: Int) {
        Toast.makeText(this, resourceId, Toast.LENGTH_LONG).show()
    }

    companion object {
        private val TAG: String = CertificateExportActivity::class.java.name

        private const val STATE_PENDING_CERT_ID = "state_pending_cert_id"
        private const val P12_SUFFIX = ".p12"
        /** Maximum UTF-16 units kept in the filename base (sans extension) for the SAF suggestion. */
        private const val MAX_EXPORT_BASENAME = 64
        /** Control characters plus the characters reserved by common filesystems. */
        private val INVALID_FILENAME_CHARS: Pattern = Pattern.compile("[\\x00-\\x1f\\x7f:*?\"<>|]")
        /** Windows device names, matched against the part of the name before the first dot. */
        private val WINDOWS_RESERVED_NAME: Pattern =
            Pattern.compile("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$", Pattern.CASE_INSENSITIVE)

        private fun randomExportName(): String {
            return UUID.randomUUID().toString() + P12_SUFFIX
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
         *
         * Widened to public: Kotlin has no package visibility (was package-visible
         * static in Java for the same-package tests).
         */
        @JvmStatic
        fun sanitizeExportFilename(name: String?): String {
            val trimmed = name?.trim() ?: ""
            // Take the trailing segment so "../../x" suggests "x", not a mangled prefix.
            val segments = trimmed.split(Regex("[\\\\/]+"))
            var basename = ""
            for (i in segments.size - 1 downTo 0) {
                val seg = segments[i].trim()
                if (seg.isNotEmpty() && seg != "." && seg != "..") {
                    basename = seg
                    break
                }
            }
            basename = INVALID_FILENAME_CHARS.matcher(basename).replaceAll("_")
            var start = 0
            while (start < basename.length && basename[start] == '.') {
                start++
            }
            var stem = basename.substring(start)

            if (stem.lowercase(Locale.ROOT).endsWith(P12_SUFFIX)) {
                stem = stem.substring(0, stem.length - P12_SUFFIX.length)
            } else {
                val extDot = stem.lastIndexOf('.')
                if (extDot > 0) {
                    stem = stem.substring(0, extDot)
                }
            }
            stem = stripTrailingDotsAndSpaces(stem)
            if (stem.length > MAX_EXPORT_BASENAME) {
                var cut = MAX_EXPORT_BASENAME
                if (Character.isHighSurrogate(stem[cut - 1])) {
                    cut--
                }
                stem = stripTrailingDotsAndSpaces(stem.substring(0, cut))
            }
            if (stem.isEmpty()) {
                return randomExportName()
            }

            val firstDot = stem.indexOf('.')
            val deviceName = stripTrailingDotsAndSpaces(if (firstDot < 0) stem else stem.substring(0, firstDot))
            if (WINDOWS_RESERVED_NAME.matcher(deviceName).matches()) {
                stem = "_$stem"
            }
            return stem + P12_SUFFIX
        }

        private fun stripTrailingDotsAndSpaces(value: String): String {
            var end = value.length
            while (end > 0 && (value[end - 1] == '.' || value[end - 1] == ' ')) {
                end--
            }
            return value.substring(0, end)
        }
    }
}
