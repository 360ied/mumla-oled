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

package se.lublin.humla.model;

import junit.framework.TestCase;

import java.util.Set;

/**
 * Unit tests verifying User equals/hashCode contract, compareTo ordering, and listening channels.
 */
public class UserTest extends TestCase {

    public void testEqualsAndHashCodeConsistentWithSession() {
        User u1 = new User(42, "Alice");
        User u2 = new User(42, "Alice");
        User u3 = new User(43, "Bob");

        assertEquals(u1, u2);
        assertEquals(u1.hashCode(), u2.hashCode());
        assertFalse(u1.equals(u3));

        // Mutating user registration ID does not break hashCode or equals
        u1.setUserId(100);
        u2.setUserId(200);
        assertEquals(u1, u2);
        assertEquals(u1.hashCode(), u2.hashCode());
    }

    public void testCompareToOrdering() {
        User alice = new User(1, "Alice");
        User aliceLower = new User(2, "alice");
        User bob = new User(3, "Bob");

        // Case-insensitive comparison with session tie-breaker
        assertEquals(-1, Integer.signum(alice.compareTo(aliceLower)));
        assertEquals(1, Integer.signum(aliceLower.compareTo(alice)));
        assertEquals(-1, Integer.signum(alice.compareTo(bob)));
        assertEquals(1, Integer.signum(bob.compareTo(alice)));

        // Null name safety
        User nameless1 = new User(10, null);
        User nameless2 = new User(20, null);
        assertEquals(-1, Integer.signum(nameless1.compareTo(alice)));
        assertEquals(1, Integer.signum(alice.compareTo(nameless1)));
        assertEquals(-1, Integer.signum(nameless1.compareTo(nameless2)));
    }

    public void testListeningChannelsThreadSafety() {
        User user = new User(1, "Alice");
        user.addListeningChannel(5);
        user.addListeningChannel(10);

        Set<Integer> channels = user.getListeningChannels();
        assertEquals(2, channels.size());
        assertTrue(channels.contains(5));
        assertTrue(channels.contains(10));

        // Iteration during mutation does not throw ConcurrentModificationException
        for (int ch : channels) {
            user.addListeningChannel(ch + 100);
        }
        user.removeListeningChannel(5);
        assertFalse(user.isListeningTo(5));
    }

    public void testSelfMuteDeafenCoercionMatchesDesktop() {
        // Desktop parity (ClientUser::setSelfMute/setSelfDeaf) and murmur:
        // deaf implies mute, unmute implies undeafen.
        User user = new User(1, "Alice");

        user.setSelfDeafened(true);
        assertTrue(user.isSelfDeafened());
        assertTrue(user.isSelfMuted());

        user.setSelfMuted(false);
        assertFalse(user.isSelfMuted());
        assertFalse(user.isSelfDeafened());

        user.setSelfMuted(true);
        assertTrue(user.isSelfMuted());
        assertFalse(user.isSelfDeafened());

        user.setSelfDeafened(false);
        assertFalse(user.isSelfDeafened());
        assertTrue(user.isSelfMuted());
    }
}
