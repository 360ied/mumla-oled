package se.lublin.mumla.preference;

import static java.util.Objects.requireNonNull;
import static se.lublin.mumla.Settings.PREF_TTS_ENGINE;
import static se.lublin.mumla.Settings.TTS_ENGINE_SYSTEM_DEFAULT;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;

import androidx.preference.ListPreference;
import androidx.preference.Preference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.guardianproject.netcipher.proxy.OrbotHelper;
import se.lublin.mumla.R;

public class GeneralSettingsFragment extends MumlaPreferenceFragment {
    private static final String USE_TOR_KEY = "useTor";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_general, rootKey);

        Preference useOrbotPreference = getPreferenceScreen().findPreference(USE_TOR_KEY);
        requireNonNull(useOrbotPreference).setEnabled(OrbotHelper.isOrbotInstalled(requireContext()));

        setupTtsEnginePreference();
    }

    /** A single installed TTS engine with a non-null display label. */
    private static final class Engine {
        public final String name;
        public final String label;

        private Engine(String name, String label) {
            this.name = name;
            this.label = label;
        }
    }

    /**
     * Enumerates the installed TTS engines via the package manager,
     * mirroring TextToSpeech.getEngines() without binding any engine.
     * Packages are deduplicated and the result is sorted
     * case-insensitively by label.
     */
    private static List<Engine> enumerateTtsEngines(Context context) {
        PackageManager packageManager = context.getPackageManager();
        Intent intent = new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE);
        List<ResolveInfo> services = packageManager.queryIntentServices(intent, 0);

        Map<String, Engine> engines = new LinkedHashMap<>();
        for (ResolveInfo resolveInfo : services) {
            ServiceInfo serviceInfo = resolveInfo.serviceInfo;
            if (serviceInfo == null || engines.containsKey(serviceInfo.packageName))
                continue;
            CharSequence label = serviceInfo.loadLabel(packageManager);
            String name = serviceInfo.packageName;
            engines.put(name, new Engine(name, label != null ? label.toString() : name));
        }

        List<Engine> list = new ArrayList<>(engines.values());
        list.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return list;
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
                getPreferenceScreen().findPreference(PREF_TTS_ENGINE);
        requireNonNull(ttsEnginePreference);

        List<Engine> engines = enumerateTtsEngines(context);

        if (engines.size() <= 1) {
            ttsEnginePreference.setEnabled(false);
            ttsEnginePreference.setSummary(R.string.ttsEngineNoneAvailable);
            return;
        }

        String systemDefault = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.TTS_DEFAULT_SYNTH);

        List<CharSequence> labels = new ArrayList<>();
        List<CharSequence> values = new ArrayList<>();
        labels.add(getString(R.string.ttsEngineSystemDefault));
        values.add(TTS_ENGINE_SYSTEM_DEFAULT);

        for (Engine engine : engines) {
            String label = engine.label;
            if (engine.name.equals(systemDefault))
                label += " " + getString(R.string.ttsEngineDefaultSuffix);
            labels.add(label);
            values.add(engine.name);
        }

        ttsEnginePreference.setEntries(labels.toArray(new CharSequence[0]));
        ttsEnginePreference.setEntryValues(values.toArray(new CharSequence[0]));

        // Show the selected entry's label; fall back to "System default"
        // when the stored value does not resolve to an entry.
        ttsEnginePreference.setSummaryProvider(
                (Preference.SummaryProvider<ListPreference>) preference -> {
                    CharSequence entry = preference.getEntry();
                    return entry != null
                            ? getString(R.string.ttsEngineSum, entry)
                            : getString(R.string.ttsEngineSystemDefault);
                });

        // Recover from a stored engine that has since been uninstalled:
        // reset to "System default" instead of leaving a selection that
        // matches no entry and can never initialize.
        if (!values.contains(ttsEnginePreference.getValue()))
            ttsEnginePreference.setValue(TTS_ENGINE_SYSTEM_DEFAULT);
    }
}
