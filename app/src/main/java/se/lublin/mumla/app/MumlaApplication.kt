package se.lublin.mumla.app

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import dagger.hilt.android.HiltAndroidApp
import se.lublin.humla.HumlaService
import se.lublin.mumla.R
import se.lublin.mumla.Settings

@HiltAndroidApp
class MumlaApplication : Application(), Settings.OnSettingChangedListener {
    private var mSettings: Settings? = null

    override fun onCreate() {
        super.onCreate()
        val settings = Settings.getInstance(this)
        mSettings = settings
        applyTheme(settings)
        settings.registerListener(this)

        HumlaService.init(this)
    }

    override fun onSettingChanged(key: String?) {
        if (key == null) {
            return
        }
        // NPE-exact: the original dereferenced mSettings unconditionally.
        val settings = mSettings ?: throw NullPointerException("mSettings")
        when (key) {
            Settings.PREF_LANGUAGE -> {
                val language = settings.language
                AppCompatDelegate.setApplicationLocales(
                    if (language == Settings.DEFAULT_LANGUAGE) {
                        androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                    } else {
                        androidx.core.os.LocaleListCompat.forLanguageTags(language)
                    }
                )
            }
            Settings.PREF_THEME -> applyTheme(settings)
        }
    }

    companion object {
        @JvmStatic
        fun applyTheme(context: Context) {
            val settings = Settings.getInstance(context)
            applyTheme(settings)
            val theme = settings.theme
            if (Settings.DEFAULT_THEME == theme) {
                context.setTheme(R.style.Theme_Mumla_Oled)
            }
        }

        private fun applyTheme(settings: Settings) {
            // The "system" and "force*" values are new (see preference_notranslate.xml).
            // We let other (older) value result in system default theme, and write that
            // to the preference store.
            when (settings.theme) {
                "forceLight" ->
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                "forceDark", Settings.DEFAULT_THEME ->
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                Settings.DEFAULT_LANGUAGE ->
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                else -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    settings.theme = Settings.DEFAULT_THEME
                }
            }
        }
    }
}
