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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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
 * selection and preference, end-state connect plus drop transitions, and
 * the no-device and refusal failure paths. Connect asserts pin the live
 * end-state, not the listener branch in isolation (see below); listener
 * widening is pinned by the re-fire no-drop halves.
 *
 * <p>JUnit 4 style is mandatory here: {@link RobolectricTestRunner} is
 * incompatible with the module's {@code TestCase} convention (see the
 * `:app` Robolectric pilot). The sandbox runs at API 34, matching the
 * pilot's {@code @Config(sdk)} pin. Handler posts and the main-executor
 * listener fan-out are delivered by idling the main looper; the manager is
 * stopped after each test so no timeout outlives its sandbox. Timing-based
 * paths (connect timeout, retry) are deliberately untested: advancing a
 * shadow clock with idleFor would exercise Robolectric scheduling, not
 * hardware bring-up.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BluetoothScoManagerLeAudioTest {

    /**
     * Short budget: the exercised failures are synchronous (no-device and
     * refusal fail before any timeout is armed), so this only bounds
     * wall-clock worst cases.
     */
    private static final long TEST_CONNECT_TIMEOUT_MS = 1000;
    /**
     * Single attempt still exercises the refusal/no-device paths, which
     * ignore the budget entirely.
     */
    private static final int TEST_MAX_ATTEMPTS = 1;

    private Application mApplication;
    private AudioManager mAudioManager;
    private ShadowAudioManager mShadowAudioManager;
    private RecordingListener mListener;
    private BluetoothScoManager mManager;

    private static class RecordingListener implements BluetoothScoManager.Listener {
        private int mConnected;
        private int mDisconnected;
        private int mFailed;
        private String mLastFailure;

        @Override
        public void onScoConnected() {
            mConnected++;
        }

        @Override
        public void onScoDisconnected() {
            mDisconnected++;
        }

        @Override
        public void onScoFailed(String reason) {
            mFailed++;
            mLastFailure = reason;
        }
    }

    // Type-only endpoints: production reads getType() and nothing else (see
    // findScoDevice), so address/product fields stay unset by design.
    // Revisit if the manager ever keys off more than the type.
    private static AudioDeviceInfo deviceOfType(int type) {
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
        mManager = new BluetoothScoManager(
                mApplication, mListener, TEST_CONNECT_TIMEOUT_MS, TEST_MAX_ATTEMPTS);
    }

    @After
    public void tearDown() {
        if (mManager != null) {
            mManager.stop();
        }
        // Explicitly release the test-set shadow audio states (refusal lock
        // and device list) rather than relying on sandbox reset alone, so
        // no test depends on execution order. The permission grant is left
        // to sandbox reset: it has no revoke affordance.
        // setUp failed before shadows existed: nothing was seized, and
        // idling here would NPE on the unprepared looper, masking the
        // setUp failure this guard claims to protect.
        if (mShadowAudioManager == null) {
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

    // Production contracts on getType() only (see findScoDevice), so pin
    // the selected type rather than the shadow's object identity.
    private void assertSelectedType(int expectedType, String message) {
        AudioDeviceInfo selected = mAudioManager.getCommunicationDevice();
        assertNotNull("No communication device selected: " + message, selected);
        assertEquals(message, expectedType, selected.getType());
    }

    @Test
    public void testBleHeadsetSelectedAndConfirmed() {
        AudioDeviceInfo ble = deviceOfType(AudioDeviceInfo.TYPE_BLE_HEADSET);
        mShadowAudioManager.setAvailableCommunicationDevices(
                Collections.singletonList(ble));

        mManager.start();
        assertSelectedType(AudioDeviceInfo.TYPE_BLE_HEADSET,
                "BLE headset must be selected as the communication device");

        mShadowAudioManager.callOnCommunicationDeviceChangedListeners(ble);
        idleMain();
        assertEquals("BLE confirmation must report connected", 1, mListener.mConnected);

        // Re-firing the same endpoint must be a no-op: a stale SCO-only
        // listener would read BLE as a route loss and drop the live link.
        // This is the regression net for widening selection and listener
        // atomically.
        mShadowAudioManager.callOnCommunicationDeviceChangedListeners(ble);
        idleMain();
        assertEquals("Re-confirmed BLE route must stay connected",
                1, mListener.mConnected);
        assertEquals("Re-confirmed BLE route must not drop",
                0, mListener.mDisconnected);
        assertEquals("Re-confirmed BLE route must never fail",
                0, mListener.mFailed);
    }

    @Test
    public void testBlePreferredOverScoForDualMode() {
        AudioDeviceInfo sco = deviceOfType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        AudioDeviceInfo ble = deviceOfType(AudioDeviceInfo.TYPE_BLE_HEADSET);
        // SCO first: preference must not depend on list order.
        mShadowAudioManager.setAvailableCommunicationDevices(Arrays.asList(sco, ble));

        mManager.start();
        assertSelectedType(AudioDeviceInfo.TYPE_BLE_HEADSET,
                "Dual-mode list must resolve to the BLE endpoint");

        mShadowAudioManager.callOnCommunicationDeviceChangedListeners(ble);
        idleMain();
        assertEquals("Dual-mode BLE confirmation must report connected",
                1, mListener.mConnected);
        mShadowAudioManager.callOnCommunicationDeviceChangedListeners(ble);
        idleMain();
        assertEquals("Re-fired dual-mode route must stay connected",
                1, mListener.mConnected);
        assertEquals("Re-fired dual-mode route must not drop",
                0, mListener.mDisconnected);
        assertEquals("Re-fired dual-mode route must never fail", 0, mListener.mFailed);
    }

    @Test
    public void testScoStillSelectedWhenBleAbsent() {
        AudioDeviceInfo sco = deviceOfType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        mShadowAudioManager.setAvailableCommunicationDevices(
                Collections.singletonList(sco));

        mManager.start();
        assertSelectedType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                "SCO remains the fallback with no BLE endpoint");

        mShadowAudioManager.callOnCommunicationDeviceChangedListeners(sco);
        idleMain();
        assertEquals("SCO confirmation must still report connected",
                1, mListener.mConnected);
        mShadowAudioManager.callOnCommunicationDeviceChangedListeners(sco);
        idleMain();
        assertEquals("Re-fired SCO route must stay connected",
                1, mListener.mConnected);
        assertEquals("Re-fired SCO route must not drop",
                0, mListener.mDisconnected);
        assertEquals("Re-fired SCO route must never fail", 0, mListener.mFailed);
    }

    @Test
    public void testRouteLossDropsActiveLink() {
        AudioDeviceInfo ble = deviceOfType(AudioDeviceInfo.TYPE_BLE_HEADSET);
        mShadowAudioManager.setAvailableCommunicationDevices(
                Collections.singletonList(ble));
        mManager.start();
        mShadowAudioManager.callOnCommunicationDeviceChangedListeners(ble);
        idleMain();
        assertEquals("Precondition: BLE link must be up before route loss",
                1, mListener.mConnected);

        // The OS moved the route elsewhere (e.g. speaker): the live link
        // must drop rather than linger on a dead selection.
        mShadowAudioManager.callOnCommunicationDeviceChangedListeners(
                deviceOfType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER));
        idleMain();
        assertEquals("Route loss must report disconnected", 1, mListener.mDisconnected);
        assertEquals("Dropped link must keep its single connect", 1, mListener.mConnected);
        assertEquals("Dropped link must not fail", 0, mListener.mFailed);
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
                BluetoothScoManager.REASON_NO_DEVICE, mListener.mLastFailure);
        assertEquals("Failed bring-up must fail exactly once", 1, mListener.mFailed);
        assertEquals("Failed bring-up must not connect", 0, mListener.mConnected);
        assertEquals("Failed bring-up must not drop", 0, mListener.mDisconnected);
        assertNull("Failed bring-up must hold no route",
                mAudioManager.getCommunicationDevice());
    }

    @Test
    public void testRefusedDeviceFailsWithReason() {
        mShadowAudioManager.setAvailableCommunicationDevices(
                Collections.singletonList(
                        deviceOfType(AudioDeviceInfo.TYPE_BLE_HEADSET)));
        mShadowAudioManager.lockCommunicationDevice(true);

        mManager.start();
        idleMain();
        assertEquals("Platform refusal must fail as refused",
                BluetoothScoManager.REASON_REFUSED, mListener.mLastFailure);
        assertEquals("Refused bring-up must fail exactly once", 1, mListener.mFailed);
        assertEquals("Refused bring-up must not connect", 0, mListener.mConnected);
        assertEquals("Refused bring-up must not drop", 0, mListener.mDisconnected);
        assertNull("Refused bring-up must hold no route",
                mAudioManager.getCommunicationDevice());
    }

    @Test
    public void testDualModeRefusalFailsSession() {
        AudioDeviceInfo sco = deviceOfType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO);
        AudioDeviceInfo ble = deviceOfType(AudioDeviceInfo.TYPE_BLE_HEADSET);
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
                BluetoothScoManager.REASON_REFUSED, mListener.mLastFailure);
        assertEquals("Failed session must fail exactly once", 1, mListener.mFailed);
        assertEquals("Failed session must not connect", 0, mListener.mConnected);
        assertEquals("Failed session must not drop", 0, mListener.mDisconnected);
        assertNull("Failed session must hold no route",
                mAudioManager.getCommunicationDevice());
    }
}
