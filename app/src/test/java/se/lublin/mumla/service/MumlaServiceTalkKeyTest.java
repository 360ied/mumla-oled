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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.service;

import android.content.SharedPreferences;

import junit.framework.TestCase;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import se.lublin.humla.Constants;
import se.lublin.humla.model.TalkState;
import se.lublin.humla.model.User;
import se.lublin.mumla.Settings;

public class MumlaServiceTalkKeyTest extends TestCase {

    private static class FakeEditor implements SharedPreferences.Editor {
        private final Map<String, Object> mValues;
        private final Map<String, Object> mTemp = new HashMap<>();

        FakeEditor(Map<String, Object> values) {
            mValues = values;
        }

        @Override
        public SharedPreferences.Editor putString(String key, String value) {
            mTemp.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putStringSet(String key, Set<String> values) {
            mTemp.put(key, values);
            return this;
        }

        @Override
        public SharedPreferences.Editor putInt(String key, int value) {
            mTemp.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putLong(String key, long value) {
            mTemp.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putFloat(String key, float value) {
            mTemp.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor putBoolean(String key, boolean value) {
            mTemp.put(key, value);
            return this;
        }

        @Override
        public SharedPreferences.Editor remove(String key) {
            mTemp.remove(key);
            mValues.remove(key);
            return this;
        }

        @Override
        public SharedPreferences.Editor clear() {
            mTemp.clear();
            mValues.clear();
            return this;
        }

        @Override
        public boolean commit() {
            apply();
            return true;
        }

        @Override
        public void apply() {
            mValues.putAll(mTemp);
            mTemp.clear();
        }
    }

    private static class FakeSharedPreferences implements SharedPreferences {
        private final Map<String, Object> mValues = new HashMap<>();

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(mValues);
        }

        @Override
        public String getString(String key, String defValue) {
            Object v = mValues.get(key);
            return v instanceof String ? (String) v : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            return defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object v = mValues.get(key);
            return v instanceof Integer ? (Integer) v : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object v = mValues.get(key);
            return v instanceof Long ? (Long) v : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object v = mValues.get(key);
            return v instanceof Float ? (Float) v : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object v = mValues.get(key);
            return v instanceof Boolean ? (Boolean) v : defValue;
        }

        @Override
        public boolean contains(String key) {
            return mValues.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new FakeEditor(mValues);
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {}
    }

    private static class TestTalkKeyService extends MumlaService {
        private boolean mConnected = true;
        private boolean mTalking = false;
        private int mSetTalkingStateCalls = 0;

        TestTalkKeyService(Settings settings) {
            mSettings = settings;
        }

        public void setConnected(boolean connected) {
            mConnected = connected;
        }

        public void setTalking(boolean talking) {
            mTalking = talking;
        }

        public int getSetTalkingStateCalls() {
            return mSetTalkingStateCalls;
        }

        @Override
        public boolean isConnectionEstablished() {
            return mConnected;
        }

        @Override
        public boolean isTalking() {
            return mTalking;
        }

        @Override
        public void setTalkingState(boolean talking) {
            mTalking = talking;
            mSetTalkingStateCalls++;
        }

        private int mSessionId = 42;
        private int mTransmitMode = Constants.TRANSMIT_PUSH_TO_TALK;
        private int mPttOnCueCalls = 0;
        private int mPttOffCueCalls = 0;

        public void setSessionId(int sessionId) {
            mSessionId = sessionId;
        }

        @Override
        public int getSessionId() {
            return mSessionId;
        }

        public void setTransmitMode(int transmitMode) {
            mTransmitMode = transmitMode;
        }

        @Override
        public int getTransmitMode() {
            return mTransmitMode;
        }

        @Override
        void playPttSound(boolean on) {
            if (on) {
                mPttOnCueCalls++;
            } else {
                mPttOffCueCalls++;
            }
        }

        public int getPttOnCueCalls() {
            return mPttOnCueCalls;
        }

        public int getPttOffCueCalls() {
            return mPttOffCueCalls;
        }
    }

    public void testOnTalkKeyCancel_WhenTalkingAndHoldPtt_StopsTalking() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = Settings.createForTesting(prefs);
        settings.setInputMethod(Settings.ARRAY_INPUT_METHOD_PTT);
        prefs.edit().putBoolean(Settings.PREF_PTT_TOGGLE, false).commit();

        TestTalkKeyService service = new TestTalkKeyService(settings);
        service.setTalking(true);

        service.onTalkKeyCancel();

        assertFalse("Hold-mode PTT must stop talking on cancel", service.isTalking());
        assertEquals(1, service.getSetTalkingStateCalls());
    }

    public void testOnTalkKeyCancel_WhenTogglePtt_PreservesTalking() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = Settings.createForTesting(prefs);
        settings.setInputMethod(Settings.ARRAY_INPUT_METHOD_PTT);
        prefs.edit().putBoolean(Settings.PREF_PTT_TOGGLE, true).commit();

        TestTalkKeyService service = new TestTalkKeyService(settings);
        service.setTalking(true);

        service.onTalkKeyCancel();

        assertTrue("Toggle-mode PTT must preserve talking state on cancel", service.isTalking());
        assertEquals(0, service.getSetTalkingStateCalls());
    }

    public void testOnTalkKeyCancel_WhenNotConnected_NoOp() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = Settings.createForTesting(prefs);
        settings.setInputMethod(Settings.ARRAY_INPUT_METHOD_PTT);
        prefs.edit().putBoolean(Settings.PREF_PTT_TOGGLE, false).commit();

        TestTalkKeyService service = new TestTalkKeyService(settings);
        service.setConnected(false);
        service.setTalking(true);

        service.onTalkKeyCancel();

        assertTrue("Disconnected service must not alter talk state", service.isTalking());
        assertEquals(0, service.getSetTalkingStateCalls());
    }

    public void testOnTalkKeyCancel_WhenVoiceActivityMode_NoOp() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = Settings.createForTesting(prefs);
        settings.setInputMethod(Settings.ARRAY_INPUT_METHOD_VOICE);
        prefs.edit().putBoolean(Settings.PREF_PTT_TOGGLE, false).commit();

        TestTalkKeyService service = new TestTalkKeyService(settings);
        service.setTalking(true);

        service.onTalkKeyCancel();

        assertTrue("Voice Activity mode must ignore talk key cancel", service.isTalking());
        assertEquals(0, service.getSetTalkingStateCalls());
    }

    public void testOnTalkKeyCancel_WhenContinuousMode_NoOp() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = Settings.createForTesting(prefs);
        settings.setInputMethod(Settings.ARRAY_INPUT_METHOD_CONTINUOUS);
        prefs.edit().putBoolean(Settings.PREF_PTT_TOGGLE, false).commit();

        TestTalkKeyService service = new TestTalkKeyService(settings);
        service.setTalking(true);

        service.onTalkKeyCancel();

        assertTrue("Continuous mode must ignore talk key cancel", service.isTalking());
        assertEquals(0, service.getSetTalkingStateCalls());
    }

    public void testOnTalkKeyCancel_WhenAlreadyNotTalking_NoOp() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = Settings.createForTesting(prefs);
        settings.setInputMethod(Settings.ARRAY_INPUT_METHOD_PTT);
        prefs.edit().putBoolean(Settings.PREF_PTT_TOGGLE, false).commit();

        TestTalkKeyService service = new TestTalkKeyService(settings);
        service.setTalking(false);

        service.onTalkKeyCancel();

        assertFalse(service.isTalking());
        assertEquals(0, service.getSetTalkingStateCalls());
    }

    public void testOnTalkKeyDownAndUp_HoldMode() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = Settings.createForTesting(prefs);
        settings.setInputMethod(Settings.ARRAY_INPUT_METHOD_PTT);
        prefs.edit().putBoolean(Settings.PREF_PTT_TOGGLE, false).commit();

        TestTalkKeyService service = new TestTalkKeyService(settings);
        assertFalse(service.isTalking());

        service.onTalkKeyDown();
        assertTrue("onTalkKeyDown must start talking in hold mode", service.isTalking());
        assertEquals(1, service.getSetTalkingStateCalls());

        service.onTalkKeyUp();
        assertFalse("onTalkKeyUp must stop talking in hold mode", service.isTalking());
        assertEquals(2, service.getSetTalkingStateCalls());
    }

    public void testOnTalkKeyDownAndUp_ToggleMode() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = Settings.createForTesting(prefs);
        settings.setInputMethod(Settings.ARRAY_INPUT_METHOD_PTT);
        prefs.edit().putBoolean(Settings.PREF_PTT_TOGGLE, true).commit();

        TestTalkKeyService service = new TestTalkKeyService(settings);
        assertFalse(service.isTalking());

        service.onTalkKeyDown();
        assertFalse("onTalkKeyDown must not start talking in toggle mode", service.isTalking());
        assertEquals(0, service.getSetTalkingStateCalls());

        service.onTalkKeyUp();
        assertTrue("First onTalkKeyUp must toggle talking ON", service.isTalking());
        assertEquals(1, service.getSetTalkingStateCalls());

        service.onTalkKeyUp();
        assertFalse("Second onTalkKeyUp must toggle talking OFF", service.isTalking());
        assertEquals(2, service.getSetTalkingStateCalls());
    }

    public void testPttAudioCue_ActivationAndDeactivation() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = Settings.createForTesting(prefs);
        TestTalkKeyService service = new TestTalkKeyService(settings);
        service.mPTTSoundEnabled = true;

        User user = new User(42, "localUser");

        user.setTalkState(TalkState.TALKING);
        service.handleUserTalkStateUpdated(user);
        assertEquals("Activation cue must play once on onset", 1, service.getPttOnCueCalls());
        assertEquals("Deactivation cue must not play on onset", 0, service.getPttOffCueCalls());

        user.setTalkState(TalkState.PASSIVE);
        service.handleUserTalkStateUpdated(user);
        assertEquals("Activation cue count preserved", 1, service.getPttOnCueCalls());
        assertEquals("Deactivation cue must play once on release", 1, service.getPttOffCueCalls());
    }

    public void testPttAudioCue_ConsecutiveStates_NoDuplicateCues() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = Settings.createForTesting(prefs);
        TestTalkKeyService service = new TestTalkKeyService(settings);
        service.mPTTSoundEnabled = true;

        User user = new User(42, "localUser");

        user.setTalkState(TalkState.TALKING);
        service.handleUserTalkStateUpdated(user);
        service.handleUserTalkStateUpdated(user);
        assertEquals("Consecutive talking states must not duplicate activation cue", 1, service.getPttOnCueCalls());

        user.setTalkState(TalkState.PASSIVE);
        service.handleUserTalkStateUpdated(user);
        service.handleUserTalkStateUpdated(user);
        assertEquals("Consecutive passive states must not duplicate deactivation cue", 1, service.getPttOffCueCalls());
    }

    public void testPttAudioCue_DisabledSetting_NoCues() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = Settings.createForTesting(prefs);
        TestTalkKeyService service = new TestTalkKeyService(settings);
        service.mPTTSoundEnabled = false;

        User user = new User(42, "localUser");

        user.setTalkState(TalkState.TALKING);
        service.handleUserTalkStateUpdated(user);
        assertEquals(0, service.getPttOnCueCalls());

        user.setTalkState(TalkState.PASSIVE);
        service.handleUserTalkStateUpdated(user);
        assertEquals(0, service.getPttOffCueCalls());
    }

    public void testPttAudioCue_NonPttMode_NoCues() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = Settings.createForTesting(prefs);
        TestTalkKeyService service = new TestTalkKeyService(settings);
        service.mPTTSoundEnabled = true;
        service.setTransmitMode(Constants.TRANSMIT_VOICE_ACTIVITY);

        User user = new User(42, "localUser");

        user.setTalkState(TalkState.TALKING);
        service.handleUserTalkStateUpdated(user);
        assertEquals(0, service.getPttOnCueCalls());

        user.setTalkState(TalkState.PASSIVE);
        service.handleUserTalkStateUpdated(user);
        assertEquals(0, service.getPttOffCueCalls());
    }

    public void testPttAudioCue_DifferentUser_Ignored() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = Settings.createForTesting(prefs);
        TestTalkKeyService service = new TestTalkKeyService(settings);
        service.mPTTSoundEnabled = true;

        User otherUser = new User(99, "remoteUser");

        otherUser.setTalkState(TalkState.TALKING);
        service.handleUserTalkStateUpdated(otherUser);
        assertEquals(0, service.getPttOnCueCalls());

        otherUser.setTalkState(TalkState.PASSIVE);
        service.handleUserTalkStateUpdated(otherUser);
        assertEquals(0, service.getPttOffCueCalls());
    }

    public void testPttAudioCue_ShoutingAndWhispering_TreatedAsTalking() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = Settings.createForTesting(prefs);
        TestTalkKeyService service = new TestTalkKeyService(settings);
        service.mPTTSoundEnabled = true;

        User user = new User(42, "localUser");

        user.setTalkState(TalkState.SHOUTING);
        service.handleUserTalkStateUpdated(user);
        assertEquals("SHOUTING must trigger activation cue", 1, service.getPttOnCueCalls());

        user.setTalkState(TalkState.PASSIVE);
        service.handleUserTalkStateUpdated(user);
        assertEquals("PASSIVE after SHOUTING must trigger deactivation cue", 1, service.getPttOffCueCalls());

        user.setTalkState(TalkState.WHISPERING);
        service.handleUserTalkStateUpdated(user);
        assertEquals("WHISPERING must trigger activation cue", 2, service.getPttOnCueCalls());
    }

    public void testPttAudioCue_DisconnectResetsState() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        Settings settings = Settings.createForTesting(prefs);
        TestTalkKeyService service = new TestTalkKeyService(settings);
        service.mPTTSoundEnabled = true;

        User user = new User(42, "localUser");

        user.setTalkState(TalkState.TALKING);
        service.handleUserTalkStateUpdated(user);
        assertEquals(1, service.getPttOnCueCalls());

        // Simulate disconnect resetting state
        service.mSelfTalking = false;

        // Reconnect and start talking again
        user.setTalkState(TalkState.TALKING);
        service.handleUserTalkStateUpdated(user);
        assertEquals("Reconnecting and talking must trigger activation cue again", 2, service.getPttOnCueCalls());
    }
}
