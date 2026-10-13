package se.lublin.mumla.preference

import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import se.lublin.mumla.R

class SeekBarPreferenceDialogFragment : androidx.preference.PreferenceDialogFragmentCompat() {

    private var mValueView: TextView? = null
    private var mMultiplier: Int = 0
    private var mDisplayDivider: Int = 0
    private var mMin: Int = 0
    private var mCurrentValue: Int = 0
    private var mSuffix: String? = null

    override fun onBindDialogView(view: View) {
        super.onBindDialogView(view)

        val seekBar = view.findViewById(R.id.seek_bar) as SeekBar
        // NPE-exact: the original dereferenced the lookup unconditionally.
        val valueView = view.findViewById(R.id.seek_bar_value_view) as TextView?
            ?: throw NullPointerException("seek_bar_value_view")
        mValueView = valueView

        // NPE-exact: the original cast getPreference() unconditionally.
        val preference = preference as SeekBarDialogPreference?
            ?: throw NullPointerException("preference")

        mMultiplier = preference.mMultiplier
        mDisplayDivider = preference.mDisplayDivider
        mMin = preference.mMin
        mSuffix = preference.mSuffix
        // The persisted value is always multiplied, but the default value in the XML is not (nor
        // are the min and max values in the XML). Make this variable contain the correct
        // multiplier value in both cases.
        // NPE-exact: the original required a non-null data store unconditionally.
        val store = preference.preferenceDataStore ?: throw NullPointerException("dataStore")
        val rawValue = store.getInt(preference.key, preference.mDefaultValue * mMultiplier)
        mCurrentValue = Math.min(
            preference.mMax * mMultiplier,
            Math.max(preference.mMin * mMultiplier, rawValue)
        )
        updateValueView()

        seekBar.max = preference.mMax - mMin
        // The persisted value is multiplied, scale it down
        seekBar.progress = mCurrentValue / mMultiplier - mMin

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mCurrentValue = (mMin + progress) * mMultiplier
                    updateValueView()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
            }
        })
    }

    private fun updateValueView() {
        // NPE-exact: the original dereferenced mValueView unconditionally.
        val valueView = mValueView ?: throw NullPointerException("mValueView")
        valueView.text = formatValue(mCurrentValue, mDisplayDivider, mSuffix)
    }

    override fun onDialogClosed(positiveResult: Boolean) {
        if (positiveResult) {
            // NPE-exact: the original cast getPreference() unconditionally.
            val preference = preference as SeekBarDialogPreference?
                ?: throw NullPointerException("preference")
            // Persisting the multiplied value, as was always done
            if (preference.callChangeListener(mCurrentValue)) {
                // NPE-exact: the original required a non-null data store unconditionally.
                val store = preference.preferenceDataStore ?: throw NullPointerException("dataStore")
                store.putInt(preference.key, mCurrentValue)
            }
        }
    }

    companion object {
        fun newInstance(key: String?): SeekBarPreferenceDialogFragment {
            val fragment = SeekBarPreferenceDialogFragment()
            val b = Bundle(1)
            b.putString(ARG_KEY, key)
            fragment.arguments = b
            return fragment
        }

        /**
         * Widened to public: Kotlin has no package visibility (was package-visible
         * static in Java for the same-package tests).
         */
        @JvmStatic
        fun formatValue(currentValue: Int, displayDivider: Int, suffix: String?): String {
            val displayVal = if (displayDivider > 0) currentValue / displayDivider else currentValue
            val t = displayVal.toString()
            return if (suffix == null) t else t + suffix
        }
    }
}
