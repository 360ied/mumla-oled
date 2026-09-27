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
 * Unit tests verifying transitive channel link resolution via {@link Channel#getAllLinks()}.
 */
public class ChannelLinksTest extends TestCase {

    public void testChannelWithNoLinksReturnsSelfOnly() {
        Channel a = new Channel(1, false);
        Set<Channel> links = a.getAllLinks();
        assertEquals(1, links.size());
        assertTrue(links.contains(a));
    }

    public void testChannelWithDirectLinks() {
        Channel a = new Channel(1, false);
        Channel b = new Channel(2, false);
        a.addLink(b);
        b.addLink(a);

        Set<Channel> linksA = a.getAllLinks();
        assertEquals(2, linksA.size());
        assertTrue(linksA.contains(a));
        assertTrue(linksA.contains(b));

        Set<Channel> linksB = b.getAllLinks();
        assertEquals(2, linksB.size());
        assertTrue(linksB.contains(a));
        assertTrue(linksB.contains(b));
    }

    public void testTransitiveLinksChain() {
        Channel a = new Channel(1, false);
        Channel b = new Channel(2, false);
        Channel c = new Channel(3, false);

        // A <-> B
        a.addLink(b);
        b.addLink(a);

        // B <-> C
        b.addLink(c);
        c.addLink(b);

        Set<Channel> linksA = a.getAllLinks();
        assertEquals(3, linksA.size());
        assertTrue(linksA.contains(a));
        assertTrue(linksA.contains(b));
        assertTrue(linksA.contains(c));

        Set<Channel> linksC = c.getAllLinks();
        assertEquals(3, linksC.size());
        assertTrue(linksC.contains(a));
        assertTrue(linksC.contains(b));
        assertTrue(linksC.contains(c));
    }

    public void testCyclicLinksDoNotInfiniteLoop() {
        Channel a = new Channel(1, false);
        Channel b = new Channel(2, false);
        Channel c = new Channel(3, false);

        a.addLink(b);
        b.addLink(c);
        c.addLink(a);

        Set<Channel> links = a.getAllLinks();
        assertEquals(3, links.size());
        assertTrue(links.contains(a));
        assertTrue(links.contains(b));
        assertTrue(links.contains(c));
    }

    public void testAddNullLinkIsSafelyIgnored() {
        Channel a = new Channel(1, false);
        a.addLink(null);
        assertEquals(0, a.getLinks().size());
        assertEquals(1, a.getAllLinks().size());
    }

    public void testDuplicateLinksAreIgnored() {
        Channel a = new Channel(1, false);
        Channel b = new Channel(2, false);

        a.addLink(b);
        assertEquals(1, a.getLinks().size());

        a.addLink(b);
        assertEquals(1, a.getLinks().size());
        assertEquals(2, a.getAllLinks().size());
    }

    public void testAddSelfLinkIsSafelyIgnored() {
        Channel a = new Channel(1, false);
        a.addLink(a);
        assertEquals(0, a.getLinks().size());
        assertEquals(1, a.getAllLinks().size());
    }

    public void testAddNullSubchannelIsSafelyIgnored() {
        Channel a = new Channel(1, false);
        a.addSubchannel(null);
        assertEquals(0, a.getSubchannels().size());
    }

    public void testDuplicateSubchannelIsIgnored() {
        Channel a = new Channel(1, false);
        Channel b = new Channel(2, false);
        a.addSubchannel(b);
        assertEquals(1, a.getSubchannels().size());

        a.addSubchannel(b);
        assertEquals(1, a.getSubchannels().size());
    }

    public void testAddSelfSubchannelIsSafelyIgnored() {
        Channel a = new Channel(1, false);
        a.addSubchannel(a);
        assertEquals(0, a.getSubchannels().size());
    }
}
