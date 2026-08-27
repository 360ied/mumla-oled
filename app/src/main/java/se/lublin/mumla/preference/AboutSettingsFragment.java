package se.lublin.mumla.preference;

import static java.util.Objects.requireNonNull;
import static se.lublin.mumla.app.DialogUtils.showAllNewsDialog;

import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.Preference;

import se.lublin.mumla.BuildConfig;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;

public class AboutSettingsFragment extends MumlaPreferenceFragment {
    private static final String VERSION_KEY = "version";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_about, rootKey);

        String summary = String.format("%s (code %s)\nFOSS flavor", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
        Preference versionPreference = getPreferenceScreen().findPreference(VERSION_KEY);
        requireNonNull(versionPreference).setSummary(summary);
        requireNonNull(versionPreference).setOnPreferenceClickListener(preference -> {
            Settings.getInstance(requireContext()).resetNewsShownVersion();
            return true;
        });
        Preference showNewsPreference = getPreferenceScreen().findPreference("showNews");
        requireNonNull(showNewsPreference).setOnPreferenceClickListener(preference -> {
            showAllNewsDialog(requireContext());
            return true;
        });
    }
}
