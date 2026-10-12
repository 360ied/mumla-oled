package se.lublin.mumla.app;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import se.lublin.mumla.Settings;

public abstract class BaseActivity extends AppCompatActivity implements Settings.OnSettingChangedListener {
    private Settings mSettings;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        MumlaApplication.applyTheme(this);
        super.onCreate(savedInstanceState);
        mSettings = Settings.getInstance(this);
        mSettings.registerListener(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mSettings.unregisterListener(this);
    }

    @Override
    public void onSettingChanged(String key) {
        if (Settings.PREF_THEME.equals(key)) {
            recreate();
        }
    }
}
