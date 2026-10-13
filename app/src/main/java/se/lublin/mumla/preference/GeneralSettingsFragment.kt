package se.lublin.mumla.preference

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.speech.tts.TextToSpeech
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import se.lublin.mumla.R
import se.lublin.mumla.Settings

class GeneralSettingsFragment : MumlaPreferenceFragment() {

    override fun onCreateMumlaPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_general, rootKey)

        setupMediaKeyMuteDependency()
        setupTtsEnginePreference()
    }

    /**
     * Grays out the pause-key mute toggle unless the media notification
     * style is active: without a media session there are no transport keys
     * to bind, so showing it enabled would promise a dead option. The
     * announce toggle additionally follows the parent mute toggle.
     */
    private fun setupMediaKeyMuteDependency() {
        val stylePreference =
            preferenceScreen.findPreference<ListPreference>(Settings.PREF_NOTIFICATION_STYLE)
        val mediaKeyMutePreference =
            preferenceScreen.findPreference<CheckBoxPreference>(Settings.PREF_MEDIA_KEY_MUTE)
        val mediaKeyMuteTtsPreference =
            preferenceScreen.findPreference<CheckBoxPreference>(Settings.PREF_MEDIA_KEY_MUTE_TTS)
        // NPE-exact: the original required non-null unconditionally.
        val style = stylePreference ?: throw NullPointerException(Settings.PREF_NOTIFICATION_STYLE)
        val mute = mediaKeyMutePreference ?: throw NullPointerException(Settings.PREF_MEDIA_KEY_MUTE)
        val muteTts = mediaKeyMuteTtsPreference ?: throw NullPointerException(Settings.PREF_MEDIA_KEY_MUTE_TTS)

        syncMediaKeyMuteEnabledStates(mute, muteTts, style.value)
        style.onPreferenceChangeListener = androidx.preference.Preference.OnPreferenceChangeListener { _, newValue ->
            syncMediaKeyMuteEnabledStates(mute, muteTts, newValue)
            true
        }
        mute.onPreferenceChangeListener = androidx.preference.Preference.OnPreferenceChangeListener { _, newValue ->
            muteTts.isEnabled =
                Settings.NOTIFICATION_STYLE_MEDIA == style.value && newValue as? Boolean == true
            true
        }
    }

    /**
     * Populates the TTS engine picker with the installed engines.
     * The first entry is always "System default", letting the user fall
     * back to whatever engine the ROM prefers. When no alternative
     * engines are installed, the picker is disabled with a hint.
     */
    private fun setupTtsEnginePreference() {
        val context = requireContext()
        val ttsEnginePreference =
            preferenceScreen.findPreference<ListPreference>(Settings.PREF_TTS_ENGINE)
        // NPE-exact: the original required non-null unconditionally.
        val picker = ttsEnginePreference ?: throw NullPointerException(Settings.PREF_TTS_ENGINE)

        val engines = enumerateTtsEngines(context)

        if (engines.size <= 1) {
            picker.isEnabled = false
            picker.setSummary(R.string.ttsEngineNoneAvailable)
            return
        }

        val systemDefault = AndroidSettings.Secure.getString(
            context.contentResolver, AndroidSettings.Secure.TTS_DEFAULT_SYNTH
        )

        val labels = ArrayList<CharSequence>()
        val values = ArrayList<CharSequence>()
        labels.add(getString(R.string.ttsEngineSystemDefault))
        values.add(Settings.TTS_ENGINE_SYSTEM_DEFAULT)

        for (engine in engines) {
            var label = engine.label
            if (engine.name == systemDefault) {
                label += " " + getString(R.string.ttsEngineDefaultSuffix)
            }
            labels.add(label)
            values.add(engine.name)
        }

        picker.entries = labels.toTypedArray()
        picker.entryValues = values.toTypedArray()

        // Show the selected entry's label; fall back to "System default"
        // when the stored value does not resolve to an entry.
        picker.summaryProvider =
            Preference.SummaryProvider<ListPreference> { preference ->
                val entry = preference.entry
                if (entry != null) {
                    getString(R.string.ttsEngineSum, entry)
                } else {
                    getString(R.string.ttsEngineSystemDefault)
                }
            }

        // Recover from a stored engine that has since been uninstalled:
        // reset to "System default" instead of leaving a selection that
        // matches no entry and can never initialize.
        val current = picker.value
        if (current == null || !values.contains(current)) {
            picker.value = Settings.TTS_ENGINE_SYSTEM_DEFAULT
        }
    }

    companion object {
        private fun syncMediaKeyMuteEnabledStates(
            mediaKeyMutePreference: CheckBoxPreference,
            mediaKeyMuteTtsPreference: CheckBoxPreference,
            styleValue: Any?
        ) {
            val mediaStyle = Settings.NOTIFICATION_STYLE_MEDIA == styleValue
            mediaKeyMutePreference.isEnabled = mediaStyle
            mediaKeyMuteTtsPreference.isEnabled = mediaStyle && mediaKeyMutePreference.isChecked
        }

        /** A single installed TTS engine with a non-null display label. */
        private class Engine(val name: String, val label: String)

        /**
         * Enumerates the installed TTS engines via the package manager,
         * mirroring TextToSpeech.getEngines() without binding any engine.
         * Packages are deduplicated and the result is sorted
         * case-insensitively by label.
         */
        private fun enumerateTtsEngines(context: Context): List<Engine> {
            val packageManager: PackageManager = context.packageManager
            val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            val services: List<ResolveInfo> = packageManager.queryIntentServices(intent, 0)

            val engines = LinkedHashMap<String, Engine>()
            for (resolveInfo in services) {
                val serviceInfo = resolveInfo.serviceInfo
                if (serviceInfo == null || engines.containsKey(serviceInfo.packageName)) {
                    continue
                }
                val label = serviceInfo.loadLabel(packageManager)
                val name = serviceInfo.packageName
                engines[name] = Engine(name, label?.toString() ?: name)
            }

            val list = ArrayList(engines.values)
            list.sortWith { a, b -> a.label.compareTo(b.label, ignoreCase = true) }
            return list
        }
    }
}
