/*
 * Copyright (C) 2014 Andrew Comminos
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

import android.content.Context
import android.os.AsyncTask
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import se.lublin.humla.net.HumlaCertificateGenerator
import se.lublin.mumla.R
import se.lublin.mumla.db.DatabaseCertificate
import se.lublin.mumla.db.MumlaRoomDatabase

// Open: CertificateGenerateActivity mounts an anonymous subclass for the result dialog.
open class MumlaCertificateGenerateTask(context: Context) : AsyncTask<Void, Void, DatabaseCertificate>() {
    private val mContext: Context = context
    private var loadingDialog: AlertDialog? = null

    override fun onPreExecute() {
        super.onPreExecute()

        loadingDialog = MaterialAlertDialogBuilder(mContext)
            .setTitle(R.string.generateCertProgress)
            .setView(R.layout.dialog_progress)
            .setCancelable(false)
            .create()
        val dialog = loadingDialog ?: throw NullPointerException("loadingDialog")
        dialog.show()
    }

    override fun doInBackground(vararg params: Void?): DatabaseCertificate? {
        try {
            val baos = ByteArrayOutputStream()
            HumlaCertificateGenerator.generateCertificate(baos)

            val dateFormat = SimpleDateFormat(DATE_FORMAT, Locale.getDefault())
            val fileName = mContext.getString(R.string.certificate_export_format, dateFormat.format(Date()))

            val database = MumlaRoomDatabase.getInstance(mContext)
            val dc = database.addCertificate(fileName, baos.toByteArray())
            database.close()
            return dc
        } catch (e: Exception) {
            Log.w(TAG, "Certificate generation failed", e)
            return null
        }
    }

    override fun onPostExecute(result: DatabaseCertificate?) {
        super.onPostExecute(result)
        if (result == null) {
            Toast.makeText(mContext, R.string.generateCertFailure, Toast.LENGTH_SHORT).show()
        }

        val dialog = loadingDialog
        if (dialog != null) {
            dialog.dismiss()
        }
    }

    companion object {
        private val TAG: String = MumlaCertificateGenerateTask::class.java.simpleName
        private const val DATE_FORMAT = "yyyy-MM-dd-HH-mm-ss"
    }
}
