/*
 * Copyright (C) 2026 Brian Zhu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import se.lublin.mumla.AppContainer
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.container
import se.lublin.mumla.util.CrashReporter

open class MumlaApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        container = createContainer()
        val settings = container.settings
        applyTheme(settings)
        applyLanguage(settings)
        // Theme and locale follow the settings flow. Only re-apply on actual
        // theme/language change: unrelated pref writes must not poke the
        // night-mode delegate or locale manager. Screens collect the same
        // flow for their own theme changes.
        container.appScope.launch(Dispatchers.Main.immediate) {
            var lastTheme: String? = settings.theme
            var lastLanguage: String? = settings.language
            container.settingsRepository.state.collect {
                val theme = settings.theme
                val language = settings.language
                if (theme != lastTheme) {
                    lastTheme = theme
                    applyTheme(settings)
                }
                if (language != lastLanguage) {
                    lastLanguage = language
                    applyLanguage(settings)
                }
            }
        }
    }

    /** Test seam: a test application can build the real container with fake edges. */
    protected open fun createContainer(): AppContainer = AppContainer(this)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::container.isInitialized) {
            container.updateConfiguration(newConfig)
        }
    }

    companion object {
        @JvmStatic
        fun applyTheme(context: Context) {
            val settings = context.container.settings
            applyTheme(settings)
            if (Settings.DEFAULT_THEME == settings.theme) {
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
                Settings.DEFAULT_THEME_SYSTEM ->
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                else -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    settings.theme = Settings.DEFAULT_THEME
                }
            }
        }

        private fun applyLanguage(settings: Settings) {
            val language = settings.language
            AppCompatDelegate.setApplicationLocales(
                if (language == Settings.DEFAULT_LANGUAGE) {
                    androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                } else {
                    androidx.core.os.LocaleListCompat.forLanguageTags(language)
                },
            )
        }
    }
}
