package se.lublin.mumla.preference

import android.os.Bundle
import androidx.preference.ListPreference
import se.lublin.mumla.R
import se.lublin.mumla.Settings

class AppearanceSettingsFragment : MumlaPreferenceFragment() {
    override fun onCreateMumlaPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_appearance, rootKey)

        val languagePreference = findPreference<ListPreference>(Settings.PREF_LANGUAGE)
        if (languagePreference != null) {
            val codes = resources.getStringArray(R.array.languageValues)
            val listNames = mutableListOf<CharSequence>(getString(R.string.language_system))
            val listCodes = mutableListOf<CharSequence>(Settings.DEFAULT_LANGUAGE)
            for (code in codes) {
                val locale = java.util.Locale.forLanguageTag(code)
                listNames.add(locale.getDisplayName(locale))
                listCodes.add(code)
            }
            languagePreference.entries = listNames.toTypedArray()
            languagePreference.entryValues = listCodes.toTypedArray()
        }
    }
}
