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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.KeyEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import se.lublin.mumla.FakeSharedPreferences;
import se.lublin.mumla.Settings;

/**
 * Drives {@link MumlaConnectionNotification.MediaSessionCallback} directly:
 * transport controls and hardware media keys must toggle self-mute, key-up
 * and auto-repeat must be consumed without toggling, a PTT-bound key must be
 * consumed silently, and unrelated keys must fall through to default dispatch.
 *
 * <p>JUnit 4 style is mandatory here: {@link RobolectricTestRunner} is
 * incompatible with the module's {@code TestCase} convention.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MumlaConnectionNotificationMediaKeyTest {

    private int mMuteToggles;
    private MumlaConnectionNotification.OnActionListener mListener;
    private MumlaConnectionNotification.MediaSessionCallback mCallback;

    private static Settings unboundPttSettings() {
        return Settings.createForTesting(new FakeSharedPreferences());
    }

    private static Settings pttSettings(int pttKey) {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit().putInt(Settings.PREF_PUSH_KEY, pttKey).commit();
        return Settings.createForTesting(prefs);
    }

    private static Settings disabledMediaKeySettings() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        prefs.edit().putBoolean(Settings.PREF_MEDIA_KEY_MUTE, false).commit();
        return Settings.createForTesting(prefs);
    }

    private static Intent mediaButtonIntent(int action, int keyCode, int repeatCount) {
        KeyEvent event = new KeyEvent(0, 0, action, keyCode, repeatCount);
        Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        intent.putExtra(Intent.EXTRA_KEY_EVENT, event);
        return intent;
    }

    @Before
    public void setUp() {
        mMuteToggles = 0;
        mListener = new MumlaConnectionNotification.OnActionListener() {
            @Override
            public void onMuteToggled() {
                mMuteToggles++;
            }

            @Override
            public void onDeafenToggled() {
            }

            @Override
            public void onOverlayToggled() {
            }

            @Override
            public void onCancelReconnect() {
            }

            @Override
            public void onDisconnect() {
            }
        };
        mCallback = new MumlaConnectionNotification.MediaSessionCallback(mListener, unboundPttSettings());
    }

    @Test
    public void playDelegatesToMuteToggle() {
        mCallback.onPlay();
        assertEquals(1, mMuteToggles);
    }

    @Test
    public void pauseDelegatesToMuteToggle() {
        mCallback.onPause();
        assertEquals(1, mMuteToggles);
    }

    @Test
    public void pauseDownTogglesAndConsumes() {
        assertTrue(mCallback.onMediaButtonEvent(
                mediaButtonIntent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)));
        assertEquals(1, mMuteToggles);
    }

    @Test
    public void playPauseAndHeadsetHookDownToggle() {
        assertTrue(mCallback.onMediaButtonEvent(
                mediaButtonIntent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0)));
        assertTrue(mCallback.onMediaButtonEvent(
                mediaButtonIntent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0)));
        assertTrue(mCallback.onMediaButtonEvent(
                mediaButtonIntent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0)));
        assertEquals(3, mMuteToggles);
    }

    @Test
    public void keyUpConsumedWithoutToggle() {
        assertTrue(mCallback.onMediaButtonEvent(
                mediaButtonIntent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)));
        assertEquals(0, mMuteToggles);
    }

    @Test
    public void autoRepeatDownConsumedWithoutToggle() {
        assertTrue(mCallback.onMediaButtonEvent(
                mediaButtonIntent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 1)));
        assertEquals(0, mMuteToggles);
    }

    @Test
    public void pttBoundKeyConsumedWithoutToggle() {
        MumlaConnectionNotification.MediaSessionCallback pttCallback =
                new MumlaConnectionNotification.MediaSessionCallback(
                        mListener, pttSettings(KeyEvent.KEYCODE_MEDIA_PAUSE));
        assertTrue(pttCallback.onMediaButtonEvent(
                mediaButtonIntent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)));
        assertEquals(0, mMuteToggles);
    }

    @Test
    public void unrelatedKeyFallsThrough() {
        assertFalse(mCallback.onMediaButtonEvent(
                mediaButtonIntent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 0)));
        assertEquals(0, mMuteToggles);
    }

    @Test
    public void nullIntentFallsThrough() {
        assertFalse(mCallback.onMediaButtonEvent(null));
        assertEquals(0, mMuteToggles);
    }

    @Test
    public void missingKeyEventFallsThrough() {
        assertFalse(mCallback.onMediaButtonEvent(new Intent(Intent.ACTION_MEDIA_BUTTON)));
        assertEquals(0, mMuteToggles);
    }

    @Test
    public void customMuteActionStillDelegates() {
        mCallback.onCustomAction(MumlaService.ACTION_MUTE, null);
        assertEquals(1, mMuteToggles);
    }

    @Test
    public void disabledTransportControlsDoNothing() {
        MumlaConnectionNotification.MediaSessionCallback disabledCallback =
                new MumlaConnectionNotification.MediaSessionCallback(
                        mListener, disabledMediaKeySettings());
        disabledCallback.onPlay();
        disabledCallback.onPause();
        assertEquals(0, mMuteToggles);
    }

    @Test
    public void disabledMediaKeyFallsThrough() {
        MumlaConnectionNotification.MediaSessionCallback disabledCallback =
                new MumlaConnectionNotification.MediaSessionCallback(
                        mListener, disabledMediaKeySettings());
        assertFalse(disabledCallback.onMediaButtonEvent(
                mediaButtonIntent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0)));
        assertEquals(0, mMuteToggles);
    }
}
