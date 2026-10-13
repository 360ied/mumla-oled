package se.lublin.mumla.preference

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceDialogFragmentCompat
import se.lublin.mumla.R
import se.lublin.mumla.Settings

class KeySelectPreferenceDialogFragment : PreferenceDialogFragmentCompat(), View.OnKeyListener {
    private var mValueView: TextView? = null
    private var mCurrentValue: Int = 0

    override fun onPrepareDialogBuilder(builder: AlertDialog.Builder) {
        super.onPrepareDialogBuilder(builder)

        builder.setNeutralButton(R.string.reset_key) { _, _ ->
            // NPE-exact: the original cast getPreference() unconditionally.
            val preference = preference as KeySelectDialogPreference?
                ?: throw NullPointerException("preference")
            mCurrentValue = Settings.DEFAULT_PUSH_KEY
            updateValueView()
            // A NeutralButton causes onDialogClosed to be called with positiveResult==false,
            // so we persist manually here.
            if (preference.callChangeListener(mCurrentValue)) {
                // NPE-exact: the original required a non-null data store unconditionally.
                val store = preference.preferenceDataStore ?: throw NullPointerException("dataStore")
                store.putInt(preference.key, mCurrentValue)
            }
        }
    }

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)

        view.setOnKeyListener(this)
        view.isFocusableInTouchMode = true
        view.requestFocus()

        // NPE-exact: the original dereferenced the lookup unconditionally.
        val valueView = view.findViewById(R.id.key_select_value_view) as TextView?
            ?: throw NullPointerException("key_select_value_view")
        mValueView = valueView
        // NPE-exact: the original cast getPreference() unconditionally.
        val preference = preference as KeySelectDialogPreference?
            ?: throw NullPointerException("preference")
        // NPE-exact: the original required a non-null data store unconditionally.
        val store = preference.preferenceDataStore ?: throw NullPointerException("dataStore")
        val stored = store.getInt(preference.key, Settings.DEFAULT_PUSH_KEY)
        // Normalize the legacy pre-ODD-07 reset value (0) to the -1 sentinel on
        // load, so pressing OK re-persists -1 and storage converges (round-2 D4).
        mCurrentValue = if (stored <= KeyEvent.KEYCODE_UNKNOWN) Settings.DEFAULT_PUSH_KEY else stored
        updateValueView()
    }

    override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
        // NPE-exact: the original dereferenced event unconditionally.
        val e = event ?: throw NullPointerException("event")
        if (e.action != KeyEvent.ACTION_DOWN) {
            return false
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            dismiss()
        } else if (keyCode > KeyEvent.KEYCODE_UNKNOWN) {
            // Ignore KEYCODE_UNKNOWN (0): persisting it would recreate the legacy
            // pre-ODD-07 sentinel that Settings.isPttKeyBound treats as unbound.
            mCurrentValue = keyCode
            updateValueView()
        }
        return true
    }

    private fun updateValueView() {
        // NPE-exact: the original dereferenced mValueView unconditionally.
        val valueView = mValueView ?: throw NullPointerException("mValueView")
        if (mCurrentValue <= KeyEvent.KEYCODE_UNKNOWN) {
            valueView.setText(R.string.no_ptt_key)
        } else {
            var keyName = KeyEvent.keyCodeToString(mCurrentValue)
            if (keyName.startsWith(KEYCODE_PREFIX)) {
                keyName = keyName.substring(KEYCODE_PREFIX.length)
            }
            valueView.text = keyName
        }
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if (positiveResult) {
            // NPE-exact: the original cast getPreference() unconditionally.
            val preference = preference as KeySelectDialogPreference?
                ?: throw NullPointerException("preference")
            if (preference.callChangeListener(mCurrentValue)) {
                // NPE-exact: the original required a non-null data store unconditionally.
                val store = preference.preferenceDataStore ?: throw NullPointerException("dataStore")
                store.putInt(preference.key, mCurrentValue)
            }
        }
    }

    companion object {
        private const val KEYCODE_PREFIX = "KEYCODE_"

        fun newInstance(key: String?): KeySelectPreferenceDialogFragment {
            val fragment = KeySelectPreferenceDialogFragment()
            val b = Bundle(1)
            b.putString(ARG_KEY, key)
            fragment.arguments = b
            return fragment
        }
    }
}
