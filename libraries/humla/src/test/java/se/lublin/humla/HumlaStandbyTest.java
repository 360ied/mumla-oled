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

import se.lublin.humla.model.User;

/**
 * Unit tests verifying zero-audio standby eligibility and transitions based on
 * transmission modes and user mute/deafen states.
 */
public class HumlaStandbyTest extends TestCase {

    public void testPushToTalkAllowsStandbyEvenWhenUnmuted() {
        User user = new User(1, "TestUser");
        assertFalse(user.isMuted());
        assertFalse(user.isSelfMuted());
        assertFalse(user.isDeafened());
        assertFalse(user.isSelfDeafened());
        assertFalse(user.isSuppressed());

        // In PTT mode, microphone capture is inactive until the PTT button is explicitly pressed.
        // Therefore, standby is permitted while unmuted.
        assertTrue(HumlaService.isTransmitModeStandbyEligible(Constants.TRANSMIT_PUSH_TO_TALK, user));
    }

    public void testVadInhibitsStandbyWhileUnmuted() {
        User user = new User(1, "TestUser");
        assertFalse(user.isMuted());
        assertFalse(user.isSelfMuted());

        // In VAD mode while unmuted, AudioRecord actively captures microphone frames.
        // Standby must be inhibited to avoid breaking audio capture during kernel suspend.
        assertFalse(HumlaService.isTransmitModeStandbyEligible(Constants.TRANSMIT_VOICE_ACTIVITY, user));
    }

    public void testContinuousInhibitsStandbyWhileUnmuted() {
        User user = new User(1, "TestUser");
        assertFalse(user.isMuted());
        assertFalse(user.isSelfMuted());

        // In Continuous mode while unmuted, microphone recording is active.
        assertFalse(HumlaService.isTransmitModeStandbyEligible(Constants.TRANSMIT_CONTINUOUS, user));
    }

    public void testVadAllowsStandbyWhenMutedOrDeafened() {
        User user = new User(1, "TestUser");

        // Self-muted
        user.setSelfMuted(true);
        assertTrue(HumlaService.isTransmitModeStandbyEligible(Constants.TRANSMIT_VOICE_ACTIVITY, user));
        user.setSelfMuted(false);

        // Server-muted
        user.setMuted(true);
        assertTrue(HumlaService.isTransmitModeStandbyEligible(Constants.TRANSMIT_VOICE_ACTIVITY, user));
        user.setMuted(false);

        // Suppressed
        user.setSuppressed(true);
        assertTrue(HumlaService.isTransmitModeStandbyEligible(Constants.TRANSMIT_VOICE_ACTIVITY, user));
        user.setSuppressed(false);

        // Self-deafened
        user.setSelfDeafened(true);
        assertTrue(HumlaService.isTransmitModeStandbyEligible(Constants.TRANSMIT_VOICE_ACTIVITY, user));
        user.setSelfDeafened(false);

        // Server-deafened
        user.setDeafened(true);
        assertTrue(HumlaService.isTransmitModeStandbyEligible(Constants.TRANSMIT_VOICE_ACTIVITY, user));
        user.setDeafened(false);
    }

    public void testContinuousAllowsStandbyWhenMutedOrDeafened() {
        User user = new User(1, "TestUser");

        user.setSelfMuted(true);
        assertTrue(HumlaService.isTransmitModeStandbyEligible(Constants.TRANSMIT_CONTINUOUS, user));

        user.setSelfMuted(false);
        user.setDeafened(true);
        assertTrue(HumlaService.isTransmitModeStandbyEligible(Constants.TRANSMIT_CONTINUOUS, user));
    }

    public void testNullUserInhibitsStandby() {
        assertFalse(HumlaService.isTransmitModeStandbyEligible(Constants.TRANSMIT_PUSH_TO_TALK, null));
        assertFalse(HumlaService.isTransmitModeStandbyEligible(Constants.TRANSMIT_VOICE_ACTIVITY, null));
        assertFalse(HumlaService.isTransmitModeStandbyEligible(Constants.TRANSMIT_CONTINUOUS, null));
    }

    public void testMutingDoesNotTriggerUndeafenTransition() {
        // Simulates an undeafened user in standby clicking Mute (mute=true, deaf=false).
        boolean wasDeafened = false;
        boolean wasMuted = false;
        int transmitMode = Constants.TRANSMIT_PUSH_TO_TALK;

        boolean newMute = true;
        boolean newDeaf = false;

        assertFalse(HumlaService.shouldExitStandbyOnStateChange(
                wasDeafened, wasMuted, newDeaf, newMute, transmitMode));
    }

    public void testUndeafeningTriggersStandbyExit() {
        // Simulates a deafened user in standby undeafening (deaf=false).
        boolean wasDeafened = true;
        boolean wasMuted = false;
        int transmitMode = Constants.TRANSMIT_PUSH_TO_TALK;

        boolean newMute = false;
        boolean newDeaf = false;

        assertTrue(HumlaService.shouldExitStandbyOnStateChange(
                wasDeafened, wasMuted, newDeaf, newMute, transmitMode));
    }

    public void testUnmutingInVadTriggersStandbyExit() {
        // Simulates a muted VAD user in standby clicking Unmute (mute=false).
        boolean wasMuted = true;
        boolean wasDeafened = false;
        int transmitMode = Constants.TRANSMIT_VOICE_ACTIVITY;

        boolean newMute = false;
        boolean newDeaf = false;

        assertTrue(HumlaService.shouldExitStandbyOnStateChange(
                wasDeafened, wasMuted, newDeaf, newMute, transmitMode));
    }

    public void testUnmutingInPttDoesNotTriggerStandbyExit() {
        // Simulates a muted PTT user clicking Unmute (mute=false).
        // Since PTT does not actively record without button press, unmuting does not exit standby.
        boolean wasMuted = true;
        boolean wasDeafened = false;
        int transmitMode = Constants.TRANSMIT_PUSH_TO_TALK;

        boolean newMute = false;
        boolean newDeaf = false;

        assertFalse(HumlaService.shouldExitStandbyOnStateChange(
                wasDeafened, wasMuted, newDeaf, newMute, transmitMode));
    }
}
