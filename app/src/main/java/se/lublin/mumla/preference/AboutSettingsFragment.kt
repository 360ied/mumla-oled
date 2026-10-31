package se.lublin.mumla.preference

import android.os.Bundle
import se.lublin.mumla.BuildConfig
import se.lublin.mumla.R
import se.lublin.mumla.Settings

class AboutSettingsFragment : MumlaPreferenceFragment() {
    override fun onCreateMumlaPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_about, rootKey)

        val summary = String.format(
            "%s (code %s)\nFOSS flavor", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE
        )
        val versionPreference = preferenceScreen.findPreference<androidx.preference.Preference>(VERSION_KEY)
        // NPE-exact: the original required non-null unconditionally.
        val preference = versionPreference ?: throw NullPointerException("versionPreference")
        preference.summary = summary
        preference.setOnPreferenceClickListener {
            Settings.getInstance(requireContext()).resetNewsShownVersion()
            true
        }
    }

    companion object {
        private const val VERSION_KEY = "version"
    }
}
