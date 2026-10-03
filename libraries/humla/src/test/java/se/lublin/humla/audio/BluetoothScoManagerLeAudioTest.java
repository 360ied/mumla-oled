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

package se.lublin.humla.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Looper;

import java.util.Arrays;
import java.util.Collections;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.AudioDeviceInfoBuilder;
import org.robolectric.shadows.ShadowAudioManager;

/**
 * Drives {@link BluetoothScoManager} through its modern
 * ({@code setCommunicationDevice}) backend under Robolectric: BLE endpoint
 * selection and preference, confirmed-connect and drop transitions, and
 * the no-device and refusal failure paths.
 *
 * <p>JUnit 4 style is mandatory here: {@link RobolectricTestRunner} is
 * incompatible with the module's {@code TestCase} convention (see the
 * `:app` Robolectric pilot). The sandbox runs at API 34, matching the
 * pilot's {@code @Config(sdk)} pin. Handler posts and the main-executor
 * listener fan-out are delivered by idling the main looper; the manager is
 * stopped after each test so no timeout outlives its sandbox. Timing-based
 * paths (connect timeout, retry) are deliberately untested — wall-clock
 * scheduling under a paused looper proves nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BluetoothScoManagerLeAudioTest {

    private Application mApplication;
    private AudioManager mAudioManager;
    private ShadowAudioManager mShadowAudioManager;
    private RecordingListener mListener;
    private BluetoothScoManager mManager;

    private static class RecordingListener implements BluetoothScoManager.Listener {
        int connected;
        int disconnected;
        String lastFailure;

        @Override
        public void onScoConnected() {
            connected++;
        }

        @Override
        public void onScoDisconnected() {
            disconnected++;
        }

        @Override
        public void onScoFailed(String reason) {
            lastFailure = reason;
        }
    }

    private static AudioDeviceInfo device(int type) {
        return AudioDeviceInfoBuilder.newBuilder().setType(type).build();
    }

    @Before
    public void setUp() {
        mApplication = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(mApplication).grantPermissions(
                Manifest.permission.BLUETOOTH_CONNECT);
        mAudioManager = (AudioManager) mApplication.getSystemService(
                Context.AUDIO_SERVICE);
        mShadowAudioManager = Shadows.shadowOf(mAudioManager);
        mListener = new RecordingListener();
        // Single attempt and a short budget: failures must surface without
        // waiting out production timeouts.
        mManager = new BluetoothScoManager(mApplication, mListener, 1000, 1);
    }

    @After
    public void tearDown() {
        if (mManager != null) {
            mManager.stop();
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private void idleMain() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void testBleHeadsetSelectedAndConfirmed() {
        AudioDeviceInfo ble = device(AudioDeviceInfo.TYPE_BLE_HEADSET);
        mShadowAudioManager.setAvailableCommunicationDevices(
                Collections.singletonList(ble));

        mManager.start();
        assertSame("BLE headset must be selected as the communication device",
                ble, mAudioManager.getCommunicationDevice());

        mShadowAudioManager.callOnCommunicationDeviceChangedListeners(ble);
        idleMain();
        assertEquals("BLE confirmation must report connected", 1, mListener.connected);
    }

    @Test
    public void testBlePreferredOverScoForDualMode() {
        AudioDeviceInfo sco = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        AudioDeviceInfo ble = device(AudioDeviceInfo.TYPE_BLE_HEADSET);
        // SCO first: preference must not depend on list order.
        mShadowAudioManager.setAvailableCommunicationDevices(Arrays.asList(sco, ble));

        mManager.start();
        assertSame("Dual-mode list must resolve to the BLE endpoint",
                ble, mAudioManager.getCommunicationDevice());
    }

    @Test
    public void testScoStillSelectedWhenBleAbsent() {
        AudioDeviceInfo sco = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        mShadowAudioManager.setAvailableCommunicationDevices(
                Collections.singletonList(sco));

        mManager.start();
        assertSame("SCO remains the fallback with no BLE endpoint",
                sco, mAudioManager.getCommunicationDevice());

        mShadowAudioManager.callOnCommunicationDeviceChangedListeners(sco);
        idleMain();
        assertEquals("SCO confirmation must still report connected",
                1, mListener.connected);
    }

    @Test
    public void testRouteLossDropsActiveLink() {
        AudioDeviceInfo ble = device(AudioDeviceInfo.TYPE_BLE_HEADSET);
        mShadowAudioManager.setAvailableCommunicationDevices(
                Collections.singletonList(ble));
        mManager.start();
        mShadowAudioManager.callOnCommunicationDeviceChangedListeners(ble);
        idleMain();
        assertEquals(1, mListener.connected);

        // The OS moved the route elsewhere (e.g. speaker): the live link
        // must drop rather than linger on a dead selection.
        mShadowAudioManager.callOnCommunicationDeviceChangedListeners(
                device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER));
        idleMain();
        assertEquals("Route loss must report disconnected", 1, mListener.disconnected);
    }

    @Test
    public void testNoDeviceFailsWithReason() {
        mShadowAudioManager.setAvailableCommunicationDevices(
                Collections.<AudioDeviceInfo>emptyList());

        mManager.start();
        idleMain();
        assertEquals("Empty device list must fail as no-device",
                BluetoothScoManager.REASON_NO_DEVICE, mListener.lastFailure);
        assertNull("Failed bring-up must hold no route",
                mAudioManager.getCommunicationDevice());
    }

    @Test
    public void testRefusedDeviceFailsWithReason() {
        mShadowAudioManager.setAvailableCommunicationDevices(
                Collections.singletonList(
                        device(AudioDeviceInfo.TYPE_BLE_HEADSET)));
        mShadowAudioManager.lockCommunicationDevice(true);

        mManager.start();
        idleMain();
        assertEquals("Platform refusal must fail as refused",
                BluetoothScoManager.REASON_REFUSED, mListener.lastFailure);
    }
}
