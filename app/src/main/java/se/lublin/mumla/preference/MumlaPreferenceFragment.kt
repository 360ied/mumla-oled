package se.lublin.mumla.preference

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceDialogFragmentCompat
import androidx.preference.PreferenceFragmentCompat
import se.lublin.mumla.R

abstract class MumlaPreferenceFragment : PreferenceFragmentCompat() {
    final override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // The manager is created by PreferenceFragmentCompat.onCreate, which
        // invokes this before inflation: the only point where the store set
        // here feeds the initial bind (setting it later would seed displayed
        // values from the default SharedPreferences instead of DataStore).
        // Binding in onCreate is impossible — the manager is null pre-super.
        preferenceManager.setPreferenceDataStore(
            DataStorePreferenceDataStore(requireContext())
        )
        onCreateMumlaPreferences(savedInstanceState, rootKey)
    }

    /** Subclass hook: inflate with setPreferencesFromResource, then wire up. */
    protected abstract fun onCreateMumlaPreferences(savedInstanceState: Bundle?, rootKey: String?)

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        val fragment = preference.fragment
        if (fragment != null) {
            val bundle = Bundle()
            bundle.putString("fragmentClassName", fragment)
            bundle.putCharSequence("title", preference.title)
            parentFragmentManager.setFragmentResult("launchFragment", bundle)
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onDisplayPreferenceDialog(preference: Preference) {
        if (preference is SeekBarDialogPreference) {
            if (parentFragmentManager.findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG") != null) {
                return
            }
            val dialogFragment = SeekBarPreferenceDialogFragment.newInstance(preference.key)
            dialogFragment.setTargetFragment(this, 0)
            dialogFragment.show(parentFragmentManager, "androidx.preference.PreferenceFragment.DIALOG")
            return
        }
        if (preference is KeySelectDialogPreference) {
            if (parentFragmentManager.findFragmentByTag("androidx.preference.PreferenceFragment.DIALOG") != null) {
                return
            }
            val dialogFragment = KeySelectPreferenceDialogFragment.newInstance(preference.key)
            dialogFragment.setTargetFragment(this, 0)
            dialogFragment.show(parentFragmentManager, "androidx.preference.PreferenceFragment.DIALOG")
            return
        }
        super.onDisplayPreferenceDialog(preference)
    }

    override fun onResume() {
        super.onResume()

        val actionBar = (requireActivity() as AppCompatActivity).supportActionBar
        if (actionBar == null) {
            return
        }
        val title = arguments?.getCharSequence("title")
        if (title == null) {
            actionBar.setTitle(R.string.action_settings)
            return
        }
        actionBar.title = title
    }
}
