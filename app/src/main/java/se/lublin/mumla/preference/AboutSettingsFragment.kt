package se.lublin.mumla.preference

import android.os.Bundle
import android.widget.Toast
import se.lublin.mumla.BuildConfig
import se.lublin.mumla.R
import se.lublin.mumla.Settings

class AboutSettingsFragment : MumlaPreferenceFragment() {
    private var crashTaps: Int = 0

    override fun onCreateMumlaPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_about, rootKey)
        crashTaps = savedInstanceState?.getInt(KEY_CRASH_TAPS, 0) ?: 0

        val summary = String.format(
            "%s (code %s)\nFOSS flavor", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE
        )
        val versionPreference = preferenceScreen.findPreference<androidx.preference.Preference>(VERSION_KEY)
        // NPE-exact: the original required non-null unconditionally.
        val preference = versionPreference ?: throw NullPointerException("versionPreference")
        preference.summary = summary
        preference.setOnPreferenceClickListener {
            // Every tap still fires the row's existing news-reset, so
            // unlocking re-arms the news dialogs once — accepted as harmless.
            Settings.getInstance(requireContext()).resetNewsShownVersion()
            crashTaps++
            if (crashTaps == 5) {
                (activity as? SettingsActivity)?.unlockCrashTest()
                Toast.makeText(requireContext(), R.string.crash_test_unlocked, Toast.LENGTH_SHORT).show()
                // Hint-only navigation: the fragment argument carries a hint,
                // but the host session flag gates — process death relocks.
                val bundle = Bundle()
                bundle.putString("fragmentClassName", "se.lublin.mumla.preference.CrashReportsFragment")
                bundle.putCharSequence("title", getString(R.string.crash_reports))
                bundle.putBoolean("crash_test_unlocked_hint", true)
                parentFragmentManager.setFragmentResult("launchFragment", bundle)
            }
            true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CRASH_TAPS, crashTaps)
    }

    companion object {
        private const val VERSION_KEY = "version"
        private const val KEY_CRASH_TAPS = "crash_test_taps"
    }
}
