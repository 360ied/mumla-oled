package se.lublin.mumla.app;

import static androidx.appcompat.app.AppCompatDelegate.setApplicationLocales;
import static androidx.core.os.LocaleListCompat.forLanguageTags;
import static androidx.core.os.LocaleListCompat.getEmptyLocaleList;

import android.app.Application;
import android.content.Context;

import androidx.appcompat.app.AppCompatDelegate;

import dagger.hilt.android.HiltAndroidApp;
import se.lublin.humla.HumlaService;
import se.lublin.mumla.R;
import se.lublin.mumla.Settings;

@HiltAndroidApp
public class MumlaApplication extends Application implements Settings.OnSettingChangedListener {
    private Settings mSettings;

    @Override
    public void onCreate() {
        super.onCreate();
        mSettings = Settings.getInstance(this);
        applyTheme(mSettings);
        mSettings.registerListener(this);

        HumlaService.init(this);
    }

    @Override
    public void onSettingChanged(String key) {
        if (key == null) {
            return;
        }
        switch (key) {
            case Settings.PREF_LANGUAGE:
                String language = mSettings.getLanguage();
                setApplicationLocales(language.equals("system") ? getEmptyLocaleList() : forLanguageTags(language));
                break;
            case Settings.PREF_THEME:
                applyTheme(mSettings);
                break;
        }
    }

    public static void applyTheme(Context context) {
        Settings settings = Settings.getInstance(context);
        applyTheme(settings);
        String theme = settings.getTheme();
        if ("forceBlack".equals(theme)) {
            context.setTheme(R.style.Theme_Mumla_Oled);
        }
    }

    private static void applyTheme(Settings settings) {
        // The "system" and "force*" values are new (see preference_notranslate.xml).
        // We let other (older) value result in system default theme, and write that
        // to the preference store.
        switch (settings.getTheme()) {
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
                settings.setTheme("forceBlack");
                break;
        }
    }
}
