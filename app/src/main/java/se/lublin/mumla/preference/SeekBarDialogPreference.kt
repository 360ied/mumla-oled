package se.lublin.mumla.preference

import android.content.Context
import android.util.AttributeSet
import androidx.preference.DialogPreference
import se.lublin.mumla.R

class SeekBarDialogPreference(context: Context, attrs: AttributeSet?) : DialogPreference(context, attrs) {
    val mMax: Int
    val mMin: Int
    val mMultiplier: Int
    val mDisplayDivider: Int
    val mSuffix: String?
    val mDefaultValue: Int

    init {
        val a = context.theme.obtainStyledAttributes(
            attrs, R.styleable.SeekBarDialogPreference, 0, 0
        )
        try {
            mMax = a.getInt(R.styleable.SeekBarDialogPreference_max, 100)
            mMin = a.getInt(R.styleable.SeekBarDialogPreference_min, 0)
            mMultiplier = a.getInt(R.styleable.SeekBarDialogPreference_multiplier, 1)
            mDisplayDivider = a.getInt(R.styleable.SeekBarDialogPreference_displayDivider, 1)
            mSuffix = a.getString(R.styleable.SeekBarDialogPreference_android_text)
            mDefaultValue = a.getInt(R.styleable.SeekBarDialogPreference_android_defaultValue, 0)
        } finally {
            a.recycle()
        }

        dialogLayoutResource = R.layout.dialog_seekbar_preference
    }
}
