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
        int failed;
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
            failed++;
            lastFailure = reason;
        }
    }

    // Type-only endpoints: production reads getType() and nothing else, so
    // address/product fields stay unset by design. Revisit if the manager
    // ever keys off more than the type.
    // Type-only endpoints: production reads getType() and nothing else, so
    // address/product fields stay unset by design. Revisit if the manager
    // ever keys off more than the type.
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
        // Single attempt and a short budget. The exercised failures below
        // are synchronous (no-device and refusal fail before any timeout is
        // armed); the budget only bounds wall-clock worst cases.
        mManager = new BluetoothScoManager(mApplication, mListener, 1000, 1);
    }

    @After
    public void tearDown() {
        if (mManager != null) {
            mManager.stop();
        }
        // Explicitly release test-set shadow state rather than relying on
        // sandbox reset alone, so no test depends on execution order.
        // JUnit still runs @After after an early setUp failure, hence the
        // guard: without it a failed setUp would mask itself with an NPE.
        if (mShadowAudioManager == null) {
            idleMain();
            return;
        }
        mShadowAudioManager.lockCommunicationDevice(false);
        mShadowAudioManager.setAvailableCommunicationDevices(
                Collections.<AudioDeviceInfo>emptyList());
        idleMain();
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

        // Re-firing the same endpoint must be a no-op: a stale SCO-only
        // listener would read BLE as a route loss and drop the live link.
        // This is the regression net for widening selection and listener
        // atomically.
        mShadowAudioManager.callOnCommunicationDeviceChangedListeners(ble);
        idleMain();
        assertEquals("Re-confirmed BLE route must stay connected",
                1, mListener.connected);
        assertEquals("Re-confirmed BLE route must not drop",
                0, mListener.disconnected);
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

        mShadowAudioManager.callOnCommunicationDeviceChangedListeners(ble);
        idleMain();
        assertEquals("Dual-mode BLE confirmation must report connected",
                1, mListener.connected);
        mShadowAudioManager.callOnCommunicationDeviceChangedListeners(ble);
        idleMain();
        assertEquals("Re-fired dual-mode route must stay connected",
                1, mListener.connected);
        assertEquals("Re-fired dual-mode route must not drop",
                0, mListener.disconnected);
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
        assertEquals("Precondition: BLE link must be up before route loss",
                1, mListener.connected);

        // The OS moved the route elsewhere (e.g. speaker): the live link
        // must drop rather than linger on a dead selection.
        mShadowAudioManager.callOnCommunicationDeviceChangedListeners(
                device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER));
        idleMain();
        assertEquals("Route loss must report disconnected", 1, mListener.disconnected);
        assertEquals("Dropped link must keep its single connect", 1, mListener.connected);
        assertNull("Dropped link must release the route",
                mAudioManager.getCommunicationDevice());
    }

    @Test
    public void testNoDeviceFailsWithReason() {
        mShadowAudioManager.setAvailableCommunicationDevices(
                Collections.<AudioDeviceInfo>emptyList());

        mManager.start();
        idleMain();
        assertEquals("Empty device list must fail as no-device",
                BluetoothScoManager.REASON_NO_DEVICE, mListener.lastFailure);
        assertEquals("Failed bring-up must fail exactly once", 1, mListener.failed);
        assertEquals("Failed bring-up must not connect", 0, mListener.connected);
        assertEquals("Failed bring-up must not drop", 0, mListener.disconnected);
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
        assertEquals("Refused bring-up must fail exactly once", 1, mListener.failed);
        assertEquals("Refused bring-up must not connect", 0, mListener.connected);
        assertEquals("Refused bring-up must not drop", 0, mListener.disconnected);
        assertNull("Refused bring-up must hold no route",
                mAudioManager.getCommunicationDevice());
    }

    @Test
    public void testDualModeRefusalFailsSession() {
        AudioDeviceInfo sco = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        AudioDeviceInfo ble = device(AudioDeviceInfo.TYPE_BLE_HEADSET);
        mShadowAudioManager.setAvailableCommunicationDevices(Arrays.asList(sco, ble));
        mShadowAudioManager.lockCommunicationDevice(true);

        mManager.start();
        idleMain();
        // Fail-fast is intentional (see findScoDevice): recovery is the
        // phone-audio fallback plus user retry, never a silent cascade to
        // the less-preferred transport. Limitation: the shadow lock fails
        // every set, so this pins the observable contract (REFUSED, silent
        // channels) rather than distinguishing fail-fast from a cascade
        // that would end identically refused.
        assertEquals("Preferred-endpoint refusal must fail the session",
                BluetoothScoManager.REASON_REFUSED, mListener.lastFailure);
        assertEquals("Failed session must fail exactly once", 1, mListener.failed);
        assertEquals("Failed session must not connect", 0, mListener.connected);
        assertEquals("Failed session must not drop", 0, mListener.disconnected);
    }
}
