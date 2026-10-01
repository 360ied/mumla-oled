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

package se.lublin.mumla.service.ipc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Proxy;

import se.lublin.humla.Constants;
import se.lublin.humla.IHumlaService;
import se.lublin.humla.IHumlaSession;
import se.lublin.humla.util.HumlaDisconnectedException;

/**
 * Verifies the ODD-19 receiver hardening: unknown actions are ignored (never
 * thrown), unknown statuses are ignored, and the broadcast only drives talk
 * state under push-to-talk transmit mode.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TalkBroadcastReceiverTest {

    private boolean mConnected = true;
    private boolean mThrowOnSession;
    private boolean mNullSession;
    private int mTransmitMode = Constants.TRANSMIT_PUSH_TO_TALK;
    private boolean mTalking;
    private int mSetTalkingStateCalls;

    private IHumlaSession mSession;
    private IHumlaService mService;
    private TalkBroadcastReceiver mReceiver;

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        return 0;
    }

    @Before
    public void setUp() {
        mSession = (IHumlaSession) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{IHumlaSession.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getTransmitMode":
                            return mTransmitMode;
                        case "isTalking":
                            return mTalking;
                        case "setTalkingState":
                            mTalking = (Boolean) args[0];
                            mSetTalkingStateCalls++;
                            return null;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
        mService = (IHumlaService) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{IHumlaService.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isConnected":
                            return mConnected;
                        case "HumlaSession":
                            if (mThrowOnSession) {
                                throw new HumlaDisconnectedException("gone");
                            }
                            return mNullSession ? null : mSession;
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
        mReceiver = new TalkBroadcastReceiver(mService);
    }

    private void receive(Intent intent) {
        // The implementation never touches the Context parameter.
        mReceiver.onReceive(null, intent);
    }

    private static Intent talkIntent(String status) {
        Intent intent = new Intent(TalkBroadcastReceiver.BROADCAST_TALK);
        if (status != null) {
            intent.putExtra(TalkBroadcastReceiver.EXTRA_TALK_STATUS, status);
        }
        return intent;
    }

    @Test
    public void unknownActionIgnoredWithoutThrowing() {
        receive(new Intent("se.lublin.mumla.action.BOGUS"));
        assertFalse(mTalking);
        assertEquals(0, mSetTalkingStateCalls);
    }

    @Test
    public void unknownStatusIgnored() {
        mTalking = true;
        receive(talkIntent("bogus"));
        assertTrue(mTalking);
        assertEquals(0, mSetTalkingStateCalls);
    }

    @Test
    public void missingStatusToggles() {
        receive(talkIntent(null));
        assertTrue(mTalking);
        assertEquals(1, mSetTalkingStateCalls);
    }

    @Test
    public void onSetsTalkingAndOffClears() {
        receive(talkIntent(TalkBroadcastReceiver.TALK_STATUS_ON));
        assertTrue(mTalking);
        receive(talkIntent(TalkBroadcastReceiver.TALK_STATUS_OFF));
        assertFalse(mTalking);
        assertEquals(2, mSetTalkingStateCalls);
    }

    @Test
    public void toggleInvertsCurrentState() {
        mTalking = true;
        receive(talkIntent(TalkBroadcastReceiver.TALK_STATUS_TOGGLE));
        assertFalse(mTalking);
        assertEquals(1, mSetTalkingStateCalls);
    }

    @Test
    public void voiceActivityTransmitModeIgnored() {
        mTransmitMode = Constants.TRANSMIT_VOICE_ACTIVITY;
        receive(talkIntent(TalkBroadcastReceiver.TALK_STATUS_ON));
        assertFalse(mTalking);
        assertEquals(0, mSetTalkingStateCalls);
    }

    @Test
    public void continuousTransmitModeIgnored() {
        mTransmitMode = Constants.TRANSMIT_CONTINUOUS;
        receive(talkIntent(TalkBroadcastReceiver.TALK_STATUS_TOGGLE));
        assertFalse(mTalking);
        assertEquals(0, mSetTalkingStateCalls);
    }

    @Test
    public void disconnectedServiceIgnored() {
        mConnected = false;
        receive(talkIntent(TalkBroadcastReceiver.TALK_STATUS_ON));
        assertFalse(mTalking);
        assertEquals(0, mSetTalkingStateCalls);
    }

    @Test
    public void nullIntentIgnored() {
        receive(null);
        assertFalse(mTalking);
        assertEquals(0, mSetTalkingStateCalls);
    }

    @Test
    public void nullActionIgnored() {
        receive(new Intent());
        assertFalse(mTalking);
        assertEquals(0, mSetTalkingStateCalls);
    }

    @Test
    public void nullSessionIgnored() {
        mNullSession = true;
        receive(talkIntent(TalkBroadcastReceiver.TALK_STATUS_ON));
        assertFalse(mTalking);
        assertEquals(0, mSetTalkingStateCalls);
    }

    @Test
    public void disconnectRaceIgnored() {
        mThrowOnSession = true;
        receive(talkIntent(TalkBroadcastReceiver.TALK_STATUS_ON));
        assertFalse(mTalking);
        assertEquals(0, mSetTalkingStateCalls);
    }
}
