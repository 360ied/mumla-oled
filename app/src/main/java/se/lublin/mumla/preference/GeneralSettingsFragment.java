package se.lublin.mumla.preference;

import static java.util.Objects.requireNonNull;

import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;

import androidx.preference.ListPreference;
import androidx.preference.Preference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import info.guardianproject.netcipher.proxy.OrbotHelper;
import se.lublin.mumla.R;

public class GeneralSettingsFragment extends MumlaPreferenceFragment {
    private static final String USE_TOR_KEY = "useTor";
    private static final String TTS_ENGINE_KEY = "ttsEngine";
    /** Secure settings key holding the package name of the default TTS engine. */
    private static final String TTS_DEFAULT_SYNTH_KEY = "tts_default_synth";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_general, rootKey);

        Preference useOrbotPreference = getPreferenceScreen().findPreference(USE_TOR_KEY);
        requireNonNull(useOrbotPreference).setEnabled(OrbotHelper.isOrbotInstalled(requireContext()));

        setupTtsEnginePreference();
    }

    /**
     * Populates the TTS engine picker with the installed engines.
     * The first entry is always "System default", letting the user fall
     * back to whatever engine the ROM prefers. When no alternative
     * engines are installed, the picker is disabled with a hint.
     */
    private void setupTtsEnginePreference() {
        Context context = requireContext();
        ListPreference ttsEnginePreference =
                getPreferenceScreen().findPreference(TTS_ENGINE_KEY);
        requireNonNull(ttsEnginePreference);

        // Enumerate installed TTS engines. A null listener is acceptable;
        // getEngines() is synchronous and does not require initialization.
        TextToSpeech probe = new TextToSpeech(context, null);
        List<TextToSpeech.EngineInfo> engines = probe.getEngines();
        probe.shutdown();

        if (engines == null || engines.size() <= 1) {
            ttsEnginePreference.setEnabled(false);
            ttsEnginePreference.setSummary(R.string.ttsEngineNoneAvailable);
            return;
        }

        String systemDefault = Settings.Secure.getString(
                context.getContentResolver(), TTS_DEFAULT_SYNTH_KEY);

        Collections.sort(engines,
                (a, b) -> a.label.compareToIgnoreCase(b.label));

        List<CharSequence> labels = new ArrayList<>();
        List<CharSequence> values = new ArrayList<>();
        labels.add(getString(R.string.ttsEngineSystemDefault));
        values.add(se.lublin.mumla.Settings.TTS_ENGINE_SYSTEM_DEFAULT);

        for (TextToSpeech.EngineInfo engine : engines) {
            String label = engine.label != null ? engine.label : engine.name;
            if (engine.name.equals(systemDefault))
                label += " " + getString(R.string.ttsEngineDefaultSuffix);
            labels.add(label);
            values.add(engine.name);
        }

        ttsEnginePreference.setEntries(labels.toArray(new CharSequence[0]));
        ttsEnginePreference.setEntryValues(values.toArray(new CharSequence[0]));

        // Default the selection to "System default" when unset. The
        // summary is a %s template, automatically filled by ListPreference
        // with the selected entry's label.
        String selected = ttsEnginePreference.getValue();
        if (selected == null)
            ttsEnginePreference.setValueIndex(0);
    }
}
