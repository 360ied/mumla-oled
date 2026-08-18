package se.lublin.mumla.app;

import static androidx.appcompat.app.AppCompatDelegate.setApplicationLocales;
import static androidx.core.os.LocaleListCompat.forLanguageTags;
import static androidx.core.os.LocaleListCompat.getEmptyLocaleList;
import static se.lublin.mumla.Settings.PREF_LANGUAGE;
import static se.lublin.mumla.Settings.PREF_THEME;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import se.lublin.humla.HumlaService;
import se.lublin.mumla.R;

public class MumlaApplication extends Application implements SharedPreferences.OnSharedPreferenceChangeListener {
    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        applyTheme(preferences);
        preferences.registerOnSharedPreferenceChangeListener(this);

        HumlaService.init(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, @Nullable String key) {
        if (key == null) {
            return;
        }
        switch (key) {
            case PREF_LANGUAGE:
                String language = preferences.getString(PREF_LANGUAGE, "system");
                setApplicationLocales(language.equals("system") ? getEmptyLocaleList() : forLanguageTags(language));
                break;
            case PREF_THEME:
                applyTheme(preferences);
                break;
        }
    }

    public static void applyTheme(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        applyTheme(preferences);
        String theme = preferences.getString(PREF_THEME, "forceBlack");
        if ("forceBlack".equals(theme)) {
            context.setTheme(R.style.Theme_Mumla_Oled);
        }
    }

    private static void applyTheme(SharedPreferences preferences) {
        // The "system" and "force*" values are new (see preference_notranslate.xml).
        // We let other (older) value result in system default theme, and write that
        // to the preference store.
        switch (preferences.getString(PREF_THEME, "forceBlack")) {
            case "forceLight":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case "forceDark":
            case "forceBlack":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case "system":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                preferences.edit().putString(PREF_THEME, "forceBlack").apply();
                break;
        }
    }
}
