package se.lublin.mumla.preference

import android.content.Context
import android.util.AttributeSet
import androidx.preference.DialogPreference
import se.lublin.mumla.R

class KeySelectDialogPreference(context: Context, attrs: AttributeSet?) : DialogPreference(context, attrs) {
    init {
        dialogLayoutResource = R.layout.dialog_keyselect_preference
    }
}
