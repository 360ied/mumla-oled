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
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.app.BaseActivity
import se.lublin.mumla.db.DatabaseCertificate
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.MumlaRoomDatabase

/**
 * Created by andrew on 11/01/16.
 */
class CertificateSelectActivity : BaseActivity(), DialogInterface.OnClickListener,
    DialogInterface.OnDismissListener {
    private var mCertificates: MutableList<ICertificateItem>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settings = Settings.getInstance(this)
        val certificates = ArrayList<ICertificateItem>()
        mCertificates = certificates
        certificates.add(NoCertificateItem(getString(R.string.no_certificate), settings))
        val database: MumlaDatabase = MumlaRoomDatabase.getInstance(this)
        for (certificate in database.getCertificates()) {
            certificates.add(CertificateItem(certificate, settings))
        }
        database.close()

        showCertificateSelectionDialog()
    }

    override fun onClick(dialog: DialogInterface?, which: Int) {
        // NPE-exact: the original dereferenced mCertificates unconditionally.
        val certificates = mCertificates ?: throw NullPointerException("mCertificates")
        val certificate = certificates[which]
        certificate.onActivate()
        finish()
    }

    private fun showCertificateSelectionDialog() {
        // NPE-exact: the original dereferenced mCertificates unconditionally.
        val certificates = mCertificates ?: throw NullPointerException("mCertificates")
        var defaultCertificatePosition = -1
        for (i in certificates.indices) {
            if (certificates[i].isDefault()) {
                defaultCertificatePosition = i
                break
            }
        }

        val builder = MaterialAlertDialogBuilder(this)
        builder.setTitle(R.string.pref_certificate_title)
        val adapter = object : ArrayAdapter<ICertificateItem>(
            this, android.R.layout.select_dialog_singlechoice, certificates
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val textView = view.findViewById(android.R.id.text1) as TextView
                textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                return view
            }
        }
        builder.setSingleChoiceItems(adapter, defaultCertificatePosition, this)
        builder.setNegativeButton(android.R.string.cancel, null)
        builder.show().setOnDismissListener(this)
    }

    override fun onDismiss(dialog: DialogInterface?) {
        finish()
    }

    private interface ICertificateItem {
        fun onActivate()
        fun isDefault(): Boolean
    }

    private class CertificateItem(certificate: DatabaseCertificate, settings: Settings) : ICertificateItem {
        private val mCertificate: DatabaseCertificate = certificate
        private val mSettings: Settings = settings

        override fun onActivate() {
            mSettings.setDefaultCertificateId(mCertificate.id)
        }

        override fun isDefault(): Boolean {
            return mSettings.getDefaultCertificate() == mCertificate.id
        }

        override fun toString(): String {
            return mCertificate.name
        }
    }

    private class NoCertificateItem(text: String, settings: Settings) : ICertificateItem {
        private val mText: String = text
        private val mSettings: Settings = settings

        override fun onActivate() {
            mSettings.disableCertificate()
        }

        override fun isDefault(): Boolean {
            return !mSettings.isUsingCertificate
        }

        override fun toString(): String {
            return mText
        }
    }
}
