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
        val saved = savedInstanceState?.getInt(KEY_CRASH_TAPS, 0) ?: 0
        // Bricked-state reset: taps >= 5 with the host relocked (rotation in
        // the old build, or process death) can never reach == 5 again, so
        // re-arm to zero and require a fresh five. Partial progress (< 5)
        // is preserved across rotation.
        crashTaps = if (saved >= 5 && (activity as? SettingsActivity)?.isCrashTestUnlocked() != true) 0 else saved

        val summary = String.format(
            "%s (code %s)\nFOSS flavor", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE
        )
        val versionPreference = preferenceScreen.findPreference<androidx.preference.Preference>(VERSION_KEY)
        // NPE-exact: the original required non-null unconditionally.
        val preference = versionPreference ?: throw NullPointerException("versionPreference")
        preference.summary = summary
        preference.setOnPreferenceClickListener {
            crashTaps++
            // Only the first tap resets news state; the hidden gesture must
            // not re-arm news dialogs on every tap.
            if (crashTaps == 1) {
                Settings.getInstance(requireContext()).resetNewsShownVersion()
            }
            if (crashTaps >= 5) {
                (activity as? SettingsActivity)?.unlockCrashTest()
                if (crashTaps == 5) {
                    Toast.makeText(requireContext(), R.string.crash_test_unlocked, Toast.LENGTH_SHORT).show()
                    // Hint-only navigation: the fragment argument carries a hint,
                    // but the host session flag gates — process death relocks.
                    val bundle = Bundle()
                    bundle.putString("fragmentClassName", CrashReportsFragment::class.java.name)
                    bundle.putCharSequence("title", getString(R.string.crash_reports))
                    bundle.putBoolean("crash_test_unlocked_hint", true)
                    parentFragmentManager.setFragmentResult("launchFragment", bundle)
                }
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
