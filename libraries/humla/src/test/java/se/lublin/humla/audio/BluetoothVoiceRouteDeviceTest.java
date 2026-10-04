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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.AudioDeviceInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Pins the voice-route device predicate in {@link BluetoothScoManager}:
 * classic SCO is a voice route on every API level, LE Audio headsets only
 * on API 33+, and output-only, broadcast, hearing-aid, and unknown
 * endpoints never are.
 *
 * <p>JUnit 4 style is mandatory here: {@link RobolectricTestRunner} drives
 * per-sandbox API levels via {@link Config}, which is incompatible with the
 * module's {@code TestCase} convention. The predicate reads
 * {@code Build.VERSION.SDK_INT}, so the version gate is pinned by a
 * dedicated SDK-30 sandbox method rather than by branching test helpers.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BluetoothVoiceRouteDeviceTest {

    @Test
    public void testScoIsVoiceRoute() {
        assertTrue("TYPE_BLUETOOTH_SCO must be a voice route",
                BluetoothScoManager.isVoiceRouteDevice(
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO));
    }

    @Test
    public void testBleHeadsetIsVoiceRouteOn33Plus() {
        assertTrue("TYPE_BLE_HEADSET must be a voice route on API 33+",
                BluetoothScoManager.isVoiceRouteDevice(
                        AudioDeviceInfo.TYPE_BLE_HEADSET));
    }

    @Test
    public void testNonVoiceEndpointsRejected() {
        assertFalse("A2DP is output-only, never a voice route",
                BluetoothScoManager.isVoiceRouteDevice(
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP));
        assertFalse("BLE speaker is output-only, never a voice route",
                BluetoothScoManager.isVoiceRouteDevice(
                        AudioDeviceInfo.TYPE_BLE_SPEAKER));
        assertFalse("BLE broadcast is receive-only, never a voice route",
                BluetoothScoManager.isVoiceRouteDevice(
                        AudioDeviceInfo.TYPE_BLE_BROADCAST));
        assertFalse("Hearing aids are not voice routes",
                BluetoothScoManager.isVoiceRouteDevice(
                        AudioDeviceInfo.TYPE_HEARING_AID));
        assertFalse("Builtin speaker is not a voice route",
                BluetoothScoManager.isVoiceRouteDevice(
                        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER));
        assertFalse("Unrelated endpoints are not voice routes",
                BluetoothScoManager.isVoiceRouteDevice(
                        AudioDeviceInfo.TYPE_FM));
    }

    @Test
    @Config(sdk = 32)
    public void testBleHeadsetRejectedBelow33() {
        assertFalse("TYPE_BLE_HEADSET must not be a voice route below API 33",
                BluetoothScoManager.isVoiceRouteDevice(
                        AudioDeviceInfo.TYPE_BLE_HEADSET));
        assertTrue("TYPE_BLUETOOTH_SCO stays a voice route below API 33",
                BluetoothScoManager.isVoiceRouteDevice(
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO));
    }

    @Test
    @Config(sdk = 33)
    public void testBleHeadsetAcceptedOn33() {
        assertTrue("TYPE_BLE_HEADSET must be a voice route on API 33",
                BluetoothScoManager.isVoiceRouteDevice(
                        AudioDeviceInfo.TYPE_BLE_HEADSET));
    }
}
