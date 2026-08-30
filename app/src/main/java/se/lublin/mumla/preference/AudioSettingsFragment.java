/*
 * Copyright (C) 2026 Mumla Developers
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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.preference;


import static se.lublin.mumla.Settings.DEFAULT_ECHO_CANCELLATION_METHOD;
import static se.lublin.mumla.Settings.PREF_ECHO_CANCELLATION_METHOD;

import android.media.audiofx.AcousticEchoCanceler;
import android.os.Bundle;

import androidx.preference.ListPreference;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import se.lublin.mumla.R;

public class AudioSettingsFragment extends MumlaPreferenceFragment {
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.settings_audio, rootKey);

        ListPreference echoCancellationPref = findPreference(PREF_ECHO_CANCELLATION_METHOD);
        if (echoCancellationPref != null) {
            if (!AcousticEchoCanceler.isAvailable()) {
                // android.media.audiofx.AcousticEchoCanceler is unavailable
                removeListEntry(echoCancellationPref, "system");
            }
            // Fallback to default ("none") if unset, or current value is no longer available
            String current = echoCancellationPref.getValue();
            if (current == null || !Arrays.asList(echoCancellationPref.getEntryValues()).contains(current)) {
                echoCancellationPref.setValue(DEFAULT_ECHO_CANCELLATION_METHOD);
            }
        }
    }

    private void removeListEntry(ListPreference pref, String valueToRemove) {
        List<CharSequence> entries = new ArrayList<>(Arrays.asList(pref.getEntries()));
        List<CharSequence> values = new ArrayList<>(Arrays.asList(pref.getEntryValues()));
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i).toString().equals(valueToRemove)) {
                entries.remove(i);
                values.remove(i);
            }
        }
        pref.setEntries(entries.toArray(new CharSequence[0]));
        pref.setEntryValues(values.toArray(new CharSequence[0]));
    }
}
