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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.preference

import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import se.lublin.mumla.R

class AuthenticationSettingsFragment : MumlaPreferenceFragment() {
    override fun onCreateMumlaPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_authentication, rootKey)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        val key = preference.key
        if ("certificateGenerate" == key) {
            startActivity(Intent(requireContext(), CertificateGenerateActivity::class.java))
            return true
        } else if ("certificateSelect" == key) {
            startActivity(Intent(requireContext(), CertificateSelectActivity::class.java))
            return true
        } else if ("certificateImport" == key) {
            startActivity(Intent(requireContext(), CertificateImportActivity::class.java))
            return true
        } else if ("certificateExport" == key) {
            startActivity(Intent(requireContext(), CertificateExportActivity::class.java))
            return true
        } else if ("clearTrust" == key) {
            startActivity(Intent(requireContext(), ServerCertificateClearActivity::class.java))
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }
}
