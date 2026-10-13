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

import android.os.Bundle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.app.BaseActivity
import se.lublin.mumla.db.DatabaseCertificate

/**
 * Created by andrew on 12/01/16.
 */
class CertificateGenerateActivity : BaseActivity() {
    // Retained so onDestroy can cancel a rotation-in-flight task: without
    // this the AsyncTask completes on the dead instance and shows its dialog
    // on a dead window token (BadTokenException).
    private var generateTask: MumlaCertificateGenerateTask? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val task = object : MumlaCertificateGenerateTask(this@CertificateGenerateActivity) {
            override fun onPostExecute(result: DatabaseCertificate?) {
                super.onPostExecute(result)
                generateTask = null
                if (result == null) {
                    finish()
                    return
                }

                val settings = Settings.getInstance(this@CertificateGenerateActivity)
                settings.defaultCertificateId = result.id
                // Rotation during generate() leaves this instance dead: never
                // show on a dead window token (base onPostExecute already
                // guards its own Toast the same way).
                if (isFinishing || isDestroyed) {
                    finish()
                    return
                }
                showCompletionDialog(result)
            }
        }
        generateTask = task
        task.execute()
    }

    override fun onDestroy() {
        // Cancel first: a completing task would otherwise drive onPostExecute
        // (and its dialog) on this dying instance.
        val task = generateTask
        generateTask = null
        task?.cancel(true)
        super.onDestroy()
    }

    private fun showCompletionDialog(result: DatabaseCertificate) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.generateCertSuccess, result.name))
            .setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { finish() }
            .show()
    }
}
