/*
 * Copyright (C) 2026 Andrew Comminos <andrew@comminos.com>
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

package se.lublin.mumla.util;

import junit.framework.TestCase;

import java.util.List;

import se.lublin.humla.model.Channel;
import se.lublin.humla.model.IChannel;

public class ModelUtilsTest extends TestCase {

    public void testGetChannelListNullChannel() {
        List<IChannel> channels = ModelUtils.getChannelList(null);
        assertNotNull(channels);
        assertTrue(channels.isEmpty());
    }

    public void testGetChannelListHierarchicalOrder() {
        Channel root = new Channel(0, false);
        root.setName("Root");

        Channel sub1 = new Channel(1, false);
        sub1.setName("Sub1");
        Channel sub2 = new Channel(2, false);
        sub2.setName("Sub2");

        root.addSubchannel(sub1);
        root.addSubchannel(sub2);

        List<IChannel> channels = ModelUtils.getChannelList(root);
        assertEquals(3, channels.size());
        assertEquals(root, channels.get(0));
        assertEquals(sub1, channels.get(1));
        assertEquals(sub2, channels.get(2));
    }

    public void testGetChannelListCyclePrevention() {
        Channel chA = new Channel(1, false);
        chA.setName("A");
        Channel chB = new Channel(2, false);
        chB.setName("B");
        Channel chC = new Channel(3, false);
        chC.setName("C");

        chA.addSubchannel(chB);
        chB.addSubchannel(chC);
        chC.addSubchannel(chA); // Cycle

        List<IChannel> channels = ModelUtils.getChannelList(chA);
        assertEquals(3, channels.size());
        assertEquals(chA, channels.get(0));
        assertEquals(chB, channels.get(1));
        assertEquals(chC, channels.get(2));
    }
}
