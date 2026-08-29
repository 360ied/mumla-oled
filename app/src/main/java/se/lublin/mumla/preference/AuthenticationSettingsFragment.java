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
