/*
 * Copyright (C) 2026 Mumla Developers
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
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.preference.Preference;

import se.lublin.mumla.R;

public class AuthenticationSettingsFragment extends MumlaPreferenceFragment {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_authentication, rootKey);
    }

    @Override
    public boolean onPreferenceTreeClick(@NonNull Preference preference) {
        String key = preference.getKey();
        if ("certificateGenerate".equals(key)) {
            startActivity(new Intent(requireContext(), CertificateGenerateActivity.class));
            return true;
        } else if ("certificateSelect".equals(key)) {
            startActivity(new Intent(requireContext(), CertificateSelectActivity.class));
            return true;
        } else if ("certificateImport".equals(key)) {
            startActivity(new Intent(requireContext(), CertificateImportActivity.class));
            return true;
        } else if ("certificateExport".equals(key)) {
            startActivity(new Intent(requireContext(), CertificateExportActivity.class));
            return true;
        } else if ("clearTrust".equals(key)) {
            startActivity(new Intent(requireContext(), ServerCertificateClearActivity.class));
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }
}
