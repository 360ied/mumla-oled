package se.lublin.mumla.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import se.lublin.mumla.Settings

abstract class BaseActivity : AppCompatActivity(), Settings.OnSettingChangedListener {
    private var mSettings: Settings? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        MumlaApplication.applyTheme(this)
        super.onCreate(savedInstanceState)
        val settings = Settings.getInstance(this)
        mSettings = settings
        settings.registerListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        // NPE-exact: the original dereferenced mSettings unconditionally.
        val settings = mSettings ?: throw NullPointerException("mSettings")
        settings.unregisterListener(this)
    }

    override fun onSettingChanged(key: String?) {
        if (Settings.PREF_THEME == key) {
            recreate()
        }
    }
}
