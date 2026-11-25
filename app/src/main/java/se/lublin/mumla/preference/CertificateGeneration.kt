/*
 * Copyright (C) 2026 Brian Zhu
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.preference

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.lublin.humla.net.HumlaCertificateGenerator
import se.lublin.mumla.R
import se.lublin.mumla.container
import se.lublin.mumla.db.DatabaseCertificate

private const val TAG = "CertificateGeneration"
private const val DATE_FORMAT = "yyyy-MM-dd-HH-mm-ss"

/**
 * Generates a client certificate and stores it, on Dispatchers.Default.
 * @return the stored certificate, or null when generation or the insert
 * failed (today's toast-and-continue behavior).
 */
suspend fun generateClientCertificate(context: Context): DatabaseCertificate? =
    withContext(Dispatchers.Default) {
        try {
            val baos = ByteArrayOutputStream()
            HumlaCertificateGenerator.generateCertificate(baos)

            val dateFormat = SimpleDateFormat(DATE_FORMAT, Locale.getDefault())
            val fileName = context.getString(
                R.string.certificate_export_format,
                dateFormat.format(Date()),
            )
            context.container.database.addCertificate(fileName, baos.toByteArray())
        } catch (e: Exception) {
            Log.w(TAG, "Certificate generation failed", e)
            null
        }
    }
