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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.BundleCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.NoSuchAlgorithmException
import java.security.UnrecoverableKeyException
import java.security.cert.CertificateException
import java.util.Arrays
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.regex.Pattern
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.app.BaseActivity
import se.lublin.mumla.db.DatabaseCertificate
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.MumlaRoomDatabase

/**
 * Created by andrew on 11/01/16.
 */
class CertificateImportActivity : BaseActivity() {

    private var mPendingCertUri: Uri? = null
    private var mPendingFileName: String? = null
    private var mPendingIsRetry: Boolean = false
    private var mWaitingForPassword: Boolean = false
    private var mPasswordDialog: AlertDialog? = null
    private var mPasswordLayout: TextInputLayout? = null
    private var mPasswordField: TextInputEditText? = null
    /** Guards the dialog submit against double-taps while a parse is in flight. */
    private var mStoring: Boolean = false
    /** Off-main-thread reader for picked certificate files (5 MB cap); shut down in onDestroy. */
    private val mCertIoExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState != null) {
            mWaitingForPassword = savedInstanceState.getBoolean(STATE_WAITING_PASSWORD)
            if (mWaitingForPassword) {
                // Rotation residual: the Bundle holds only the source Uri (plus display
                // metadata), never cert bytes or passwords. Re-read the file on restore.
                mPendingCertUri = BundleCompat.getParcelable(savedInstanceState, STATE_CERT_URI, Uri::class.java)
                mPendingFileName = savedInstanceState.getString(STATE_FILE_NAME)
                mPendingIsRetry = savedInstanceState.getBoolean(STATE_IS_RETRY)
                val uri = mPendingCertUri
                val fileName = mPendingFileName
                if (uri != null && fileName != null) {
                    // Re-read off the main thread; the Bundle holds only the source Uri
                    // (plus display metadata), never cert bytes or passwords.
                    val isRetry = mPendingIsRetry
                    mCertIoExecutor.execute {
                        val certBytes: ByteArray
                        try {
                            certBytes = readCertBytes(uri)
                        } catch (e: IOException) {
                            Log.w(TAG, "Could not re-read certificate after activity recreation", e)
                            runOnUiThread {
                                if (isFinishing || isDestroyed) {
                                    return@runOnUiThread
                                }
                                mWaitingForPassword = false
                                Toast.makeText(this@CertificateImportActivity, R.string.invalid_certificate, Toast.LENGTH_LONG).show()
                                finish()
                            }
                            return@execute
                        }
                        runOnUiThread {
                            if (isFinishing || isDestroyed) {
                                return@runOnUiThread
                            }
                            showPasswordDialog(uri, fileName, certBytes, isRetry)
                        }
                    }
                    return
                }
                // Inconsistent saved state; nothing to restore.
                mWaitingForPassword = false
                finish()
                return
            }
            // Recreated while the file picker is open: its result is delivered to
            // this instance, so do not launch a second picker.
            return
        }

        val fileIntent = Intent(Intent.ACTION_GET_CONTENT)
        fileIntent.type = "*/*"
        fileIntent.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(fileIntent, REQUEST_FILE)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_WAITING_PASSWORD, mWaitingForPassword)
        if (mWaitingForPassword) {
            // Never persist cert bytes or passwords in the Bundle; the Uri is
            // re-read (with the size cap) when the activity is recreated.
            outState.putParcelable(STATE_CERT_URI, mPendingCertUri)
            outState.putString(STATE_FILE_NAME, mPendingFileName)
            outState.putBoolean(STATE_IS_RETRY, mPendingIsRetry)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        for (dropped in mCertIoExecutor.shutdownNow()) {
            // Dropped runnables never execute; zero here what the task would have zeroed.
            if (dropped is PasswordRunnable) {
                dropped.zeroHeldPassword()
            }
        }
        val dialog = mPasswordDialog
        if (dialog != null && dialog.isShowing) {
            dialog.dismiss()
            mPasswordDialog = null
        }
        mPasswordLayout = null
        mPasswordField = null
        // The in-memory file bytes are dialog-scoped locals; the Uri in saved
        // state is the restore path, so there is nothing to retain here.
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_FILE) {
            return
        }

        if (resultCode == RESULT_CANCELED || data?.data == null) {
            finish()
            return
        }

        val uri = data?.data ?: throw NullPointerException("data")
        // Read and probe off the main thread; only Toast/finish run on the UI thread.
        mCertIoExecutor.execute {
            val certBytes: ByteArray
            try {
                certBytes = readCertBytes(uri)
            } catch (e: IOException) {
                Log.w(TAG, "Could not read picked certificate", e)
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    Toast.makeText(this@CertificateImportActivity, R.string.invalid_certificate, Toast.LENGTH_LONG).show()
                    finish()
                }
                return@execute
            }

            var displayName: String? = null
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null).use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) {
                        displayName = cursor.getString(0)
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not query display name of picked certificate", e)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Could not query display name of picked certificate", e)
            }
            var fileName = displayName
            if (fileName == null || fileName.isEmpty()) {
                fileName = UUID.randomUUID().toString() + ".p12"
            }
            val resolved = fileName ?: throw NullPointerException("fileName")
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    return@runOnUiThread
                }
                storeKeystore(CharArray(0), uri, resolved, certBytes, false)
            }
        }
    }

    /** Reads the picked certificate with the 5 MB cap; used for both first read and restore. */
    @Throws(IOException::class)
    private fun readCertBytes(uri: Uri): ByteArray {
        openCertificateStream(uri).use { input ->
            if (input == null) {
                throw IOException("Could not open certificate")
            }
            val baos = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var read: Int
            var totalBytes = 0
            while (input.read(buffer).also { read = it } != -1) {
                totalBytes += read
                if (totalBytes > MAX_CERT_SIZE) {
                    throw IOException("Certificate file exceeds maximum allowed size")
                }
                baos.write(buffer, 0, read)
            }
            return baos.toByteArray()
        }
    }

    /** Opens the Uri, reporting provider failures (lost grant, bad Uri) as {@link IOException}. */
    @Throws(IOException::class)
    private fun openCertificateStream(uri: Uri): InputStream? {
        try {
            return contentResolver.openInputStream(uri)
        } catch (e: SecurityException) {
            throw IOException("Could not open certificate", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("Could not open certificate", e)
        } catch (e: UnsupportedOperationException) {
            throw IOException("Could not open certificate", e)
        }
    }

    private fun storeKeystore(
        password: CharArray,
        sourceUri: Uri,
        fileName: String,
        certBytes: ByteArray,
        isRetry: Boolean
    ) {
        // KeyStore parsing (PBKDF/MAC over up-to-5 MB input) and the database insert
        // run off the main thread; only dialog, Toast, and finish run on the UI thread.
        // storeKeystore remains the single password-zeroing owner on every executed path;
        // a runnable discarded by shutdownNow is zeroed in onDestroy instead.
        mCertIoExecutor.execute(object : PasswordRunnable(password) {
            override fun run() {
                try {
                    openKeystore(certBytes, password)
                } catch (e: Exception) {
                    if (isPasswordFailure(e)) {
                        zeroPassword(password)
                        runOnUiThread {
                            if (isFinishing || isDestroyed) {
                                return@runOnUiThread
                            }
                            // Release the in-flight submit so the reshown dialog accepts a retry.
                            mStoring = false
                            showPasswordDialog(sourceUri, fileName, certBytes, isRetry)
                        }
                    } else {
                        Log.w(TAG, "Certificate keystore could not be opened", e)
                        zeroPassword(password)
                        runOnUiThread {
                            if (isFinishing || isDestroyed) {
                                return@runOnUiThread
                            }
                            dismissPasswordDialog()
                            mWaitingForPassword = false
                            Toast.makeText(
                                this@CertificateImportActivity, R.string.invalid_certificate, Toast.LENGTH_LONG
                            ).show()
                            finish()
                        }
                    }
                    return
                }

                // Destroyed while parsing (e.g. rotation): the restore path re-reads from
                // the Uri, so bail before inserting to avoid a duplicate certificate row.
                // shutdownNow interrupts the worker, which sets this flag on the runner.
                if (Thread.currentThread().isInterrupted) {
                    zeroPassword(password)
                    return
                }

                // DB boundary only: the at-rest store keeps a String password by design
                // (secrets-at-rest-plan), so convert here and zero the char[] immediately.
                val passwordStr = if (password.isNotEmpty()) String(password) else null
                zeroPassword(password)
                val database: MumlaDatabase = MumlaRoomDatabase.getInstance(this@CertificateImportActivity)
                val certificate = database.addCertificate(fileName, certBytes, passwordStr)
                database.close()

                val imported = certificate != null && certificate.id >= 0
                if (imported) {
                    val settings = Settings.getInstance(this@CertificateImportActivity)
                    settings.defaultCertificateId = certificate.id
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    mWaitingForPassword = false
                    dismissPasswordDialog()
                    if (imported) {
                        Toast.makeText(
                            this@CertificateImportActivity,
                            getString(R.string.certificate_import_success, fileName),
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this@CertificateImportActivity, R.string.certificate_load_failed, Toast.LENGTH_LONG
                        ).show()
                    }
                    finish()
                }
            }
        })
    }

    /** Executor task holding a password char[]; zeroed if the executor discards it. */
    private abstract class PasswordRunnable(private val mPassword: CharArray) : Runnable {
        fun zeroHeldPassword() {
            zeroPassword(mPassword)
        }
    }

    /**
     * Parses and verifies the PKCS#12 keystore, throwing on any failure. Runs off-thread.
     * Narrower than parse errors allow: keystore failures are security exceptions,
     * transport failures are IO exceptions.
     */
    @Throws(GeneralSecurityException::class, IOException::class)
    private fun openKeystore(certBytes: ByteArray, password: CharArray) {
        ByteArrayInputStream(certBytes).use { input ->
            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(input, password)
            val aliases = keyStore.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                if (keyStore.isKeyEntry(alias)) {
                    val key = keyStore.getKey(alias, password)
                    if (key == null) {
                        throw UnrecoverableKeyException("Key could not be recovered for alias $alias")
                    }
                }
            }
        }
    }

    /** Dismisses the password dialog and releases its view references. Runs on the UI thread. */
    private fun dismissPasswordDialog() {
        val dialog = mPasswordDialog
        if (dialog != null) {
            dialog.dismiss()
            mPasswordDialog = null
        }
        mPasswordLayout = null
        mPasswordField = null
    }

    private fun showPasswordDialog(
        sourceUri: Uri,
        fileName: String,
        certBytes: ByteArray,
        isRetry: Boolean
    ) {
        mWaitingForPassword = true
        mPendingCertUri = sourceUri
        mPendingFileName = fileName
        mPendingIsRetry = isRetry

        val existing = mPasswordDialog
        if (existing != null && existing.isShowing &&
            mPasswordLayout != null && mPasswordField != null
        ) {
            if (isRetry) {
                // Never refill a previous password; clear the rejected one so the
                // user re-enters it from scratch.
                val field = mPasswordField ?: throw NullPointerException("mPasswordField")
                val layout = mPasswordLayout ?: throw NullPointerException("mPasswordLayout")
                field.setText("")
                layout.error = getString(R.string.invalid_password)
                field.requestFocus()
            }
            return
        }

        if (existing != null && existing.isShowing) {
            existing.dismiss()
        }

        val builder = MaterialAlertDialogBuilder(this)
        val inflater = LayoutInflater.from(builder.context)
        val dialogView = inflater.inflate(R.layout.dialog_certificate_password, null)
        // NPE-exact: the original dereferenced the lookups unconditionally.
        val layout = dialogView.findViewById(R.id.certificate_password_layout) as TextInputLayout?
            ?: throw NullPointerException("certificate_password_layout")
        val field = dialogView.findViewById(R.id.certificate_password_field) as TextInputEditText?
            ?: throw NullPointerException("certificate_password_field")
        mPasswordLayout = layout
        mPasswordField = field

        if (isRetry) {
            layout.error = getString(R.string.invalid_password)
        }

        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val current = mPasswordLayout
                if (current != null && current.error != null) {
                    current.error = null
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        val submitAction = {
            if (mPasswordField == null || mStoring) {
                // Field gone or submit in flight: ignore the tap.
            } else {
                mStoring = true
                // Copy chars straight from the field into a char[] (no intermediate
                // String) for KeyStore.load.
                val current = mPasswordField ?: throw NullPointerException("mPasswordField")
                val text = current.text
                val length = text?.length ?: 0
                val passChars = CharArray(length)
                if (length > 0) {
                    text?.getChars(0, length, passChars, 0)
                }
                // Clear the field copy: the zeroed char[] below is the only retained form.
                current.setText("")
                // Single zeroing owner is the callee: storeKeystore zeroes the array on
                // every path, so no fill is needed here.
                storeKeystore(passChars, sourceUri, fileName, certBytes, true)
            }
        }

        field.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                submitAction()
                true
            } else {
                false
            }
        }

        mPasswordDialog = builder
            .setTitle(R.string.decrypt_certificate)
            .setView(dialogView)
            .setOnCancelListener {
                mWaitingForPassword = false
                finish()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                mWaitingForPassword = false
                finish()
            }
            .setPositiveButton(android.R.string.ok, null)
            .create()

        val dialog = mPasswordDialog ?: throw NullPointerException("mPasswordDialog")
        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            if (positiveButton != null) {
                positiveButton.setOnClickListener { submitAction() }
            }
        }

        if (dialog.window != null) {
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            )
        }
        dialog.show()
        field.requestFocus()
    }

    companion object {
        private val TAG: String = CertificateImportActivity::class.java.name

        const val REQUEST_FILE = 0

        private const val STATE_CERT_URI = "state_cert_uri"
        private const val STATE_FILE_NAME = "state_file_name"
        private const val STATE_IS_RETRY = "state_is_retry"
        private const val STATE_WAITING_PASSWORD = "state_waiting_password"
        private const val MAX_CERT_SIZE = 5 * 1024 * 1024 // 5 MB

        // Heuristic only: PKCS#12 implementations report wrong-password failures with
        // varied messages ("mac", "password", "padding", ...). The typed exceptions
        // in isPasswordFailure take precedence; this pattern is a last-resort
        // fallback, not a precise classifier.
        private val MAC_PATTERN: Pattern = Pattern.compile("\\bmac\\b", Pattern.CASE_INSENSITIVE)

        private fun zeroPassword(password: CharArray?) {
            if (password != null) {
                Arrays.fill(password, '\u0000')
            }
        }

        /**
         * Widened to public: Kotlin has no package visibility (was package-visible
         * static in Java for the same-package tests).
         */
        @JvmStatic
        fun isPasswordFailure(t: Throwable?): Boolean {
            var cur = t
            while (cur != null) {
                if (cur is CertificateException ||
                    cur is NoSuchAlgorithmException
                ) {
                    return false
                }
                if (cur is UnrecoverableKeyException ||
                    cur is BadPaddingException ||
                    cur is AEADBadTagException
                ) {
                    return true
                }
                val msg = cur.message
                if (msg != null) {
                    val lower = msg.lowercase(Locale.ROOT)
                    if (lower.contains("password") ||
                        MAC_PATTERN.matcher(msg).find() ||
                        lower.contains("decrypt") ||
                        lower.contains("padding") ||
                        lower.contains("bad key") ||
                        lower.contains("key failed")
                    ) {
                        return true
                    }
                }
                cur = cur.cause
            }
            return false
        }
    }
}
