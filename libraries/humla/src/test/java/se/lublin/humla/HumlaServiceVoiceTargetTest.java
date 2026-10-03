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

package se.lublin.humla;

import junit.framework.TestCase;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import se.lublin.humla.util.HumlaCallbacks;
import se.lublin.humla.util.HumlaObserver;
import se.lublin.humla.util.VoiceTargetMode;

/**
 * Verifies the {@link HumlaService#setVoiceTargetId} null-guard (ODD-25):
 * with no audio pipeline (disconnected), the call must store the target and
 * return instead of throwing on the null {@code mAudioHandler}, mirroring
 * the {@code setTalkingState} guard on the same field. The stored value is
 * re-applied at pipeline init, and per-connection resets still apply.
 */
public class HumlaServiceVoiceTargetTest extends TestCase {

    private static HumlaService newDisconnectedService() throws Exception {
        HumlaService service = new HumlaService();
        // onCreate() normally installs this; the disconnected path under test
        // must not depend on service startup having run.
        Field callbacks = HumlaService.class.getDeclaredField("mCallbacks");
        callbacks.setAccessible(true);
        callbacks.set(service, new HumlaCallbacks());
        return service;
    }

    public void testSetVoiceTargetIdWhileDisconnectedStoresTarget() throws Exception {
        HumlaService service = newDisconnectedService();
        service.setVoiceTargetId((byte) 1);
        assertEquals("Disconnected set must store the target for pipeline init",
                (byte) 1, service.getVoiceTargetId());
    }

    public void testRangeCheckStillAppliesWhileDisconnected() throws Exception {
        HumlaService service = newDisconnectedService();
        try {
            service.setVoiceTargetId((byte) 0x20);
            fail("Target IDs above 5 bits must still be rejected");
        } catch (IllegalArgumentException expected) {
        }
    }

    public void testBoundaryTargetIdsAccepted() throws Exception {
        HumlaService service = newDisconnectedService();
        service.setVoiceTargetId((byte) 0);
        assertEquals((byte) 0, service.getVoiceTargetId());
        service.setVoiceTargetId((byte) 31);
        assertEquals((byte) 31, service.getVoiceTargetId());
    }

    // Sign-extended bytes must not slip past the 5-bit check and poison
    // the stored target before fromId() throws (pedantic review).
    public void testNegativeTargetIdRejectedBeforeStateChange() throws Exception {
        HumlaService service = newDisconnectedService();
        try {
            service.setVoiceTargetId((byte) -1);
            fail("Negative target IDs must be rejected");
        } catch (IllegalArgumentException expected) {
        }
        assertEquals("Rejected set must leave the previous target intact",
                (byte) 0, service.getVoiceTargetId());
    }

    public void testAcceptedSetNotifiesObserver() throws Exception {
        HumlaService service = newDisconnectedService();
        final AtomicReference<VoiceTargetMode> notified = new AtomicReference<>();
        Field callbacks = HumlaService.class.getDeclaredField("mCallbacks");
        callbacks.setAccessible(true);
        HumlaCallbacks router = new HumlaCallbacks();
        router.registerObserver(new HumlaObserver() {
            @Override
            public void onVoiceTargetChanged(VoiceTargetMode mode) {
                notified.set(mode);
            }
        });
        callbacks.set(service, router);
        service.setVoiceTargetId((byte) 1);
        assertEquals("Accepted set must notify with the mapped mode",
                VoiceTargetMode.WHISPER, notified.get());
    }
}
