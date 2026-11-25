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
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.service;

import junit.framework.TestCase;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

public class IChatMessageTest extends TestCase {

    public void testTextMessageHoldsValues() {
        IChatMessage.TextMessage textMsg = new IChatMessage.TextMessage(
                "Alice", Collections.singletonList("General"), "Hello from remote", 42L);

        assertFalse("Text entries default to not self-authored", textMsg.isSelfAuthored());
        assertEquals("Hello from remote", textMsg.getBody());
        assertEquals("Alice", textMsg.getActorName());
        assertEquals(Collections.singletonList("General"), textMsg.getTargetNames());
        assertEquals(42L, textMsg.getReceivedTime());
    }

    public void testTextMessageSelfAuthored() {
        IChatMessage.TextMessage textMsg = new IChatMessage.TextMessage(
                "Self", Arrays.asList("Alice", "Bob"), "Hello from me", 7L, true);

        assertTrue("Explicitly authored TextMessage must report isSelfAuthored as true",
                textMsg.isSelfAuthored());
        assertEquals("Hello from me", textMsg.getBody());
        assertEquals(Arrays.asList("Alice", "Bob"), textMsg.getTargetNames());
    }

    public void testTextMessageVisitor() {
        IChatMessage.TextMessage textMsg = new IChatMessage.TextMessage(
                "Self", Collections.emptyList(), "Test visitor", 1L, true);

        final AtomicBoolean visited = new AtomicBoolean(false);
        textMsg.accept(new IChatMessage.Visitor() {
            @Override
            public void visit(IChatMessage.TextMessage message) {
                visited.set(true);
                assertTrue(message.isSelfAuthored());
                assertEquals("Test visitor", message.getBody());
            }

            @Override
            public void visit(IChatMessage.InfoMessage message) {
                fail("InfoMessage visitor should not be called for TextMessage");
            }
        });

        assertTrue("Visitor visit(TextMessage) must be invoked", visited.get());
    }

    public void testInfoMessageVisitorAndTypes() {
        IChatMessage.InfoMessage infoMsg = new IChatMessage.InfoMessage(
                IChatMessage.InfoMessage.Type.WARNING, "Connection lost");

        assertEquals(IChatMessage.InfoMessage.Type.WARNING, infoMsg.getType());
        assertEquals("Connection lost", infoMsg.getBody());
        assertTrue(infoMsg.getReceivedTime() > 0);

        final AtomicBoolean visited = new AtomicBoolean(false);
        infoMsg.accept(new IChatMessage.Visitor() {
            @Override
            public void visit(IChatMessage.TextMessage message) {
                fail("TextMessage visitor should not be called for InfoMessage");
            }

            @Override
            public void visit(IChatMessage.InfoMessage message) {
                visited.set(true);
                assertEquals(IChatMessage.InfoMessage.Type.WARNING, message.getType());
                assertEquals("Connection lost", message.getBody());
            }
        });

        assertTrue("Visitor visit(InfoMessage) must be invoked", visited.get());
    }

    public void testInfoMessageTimestamp() {
        long before = System.currentTimeMillis();
        IChatMessage.InfoMessage infoMsg = new IChatMessage.InfoMessage(
                IChatMessage.InfoMessage.Type.INFO, "Timestamp check");
        long after = System.currentTimeMillis();

        assertTrue(infoMsg.getReceivedTime() >= before);
        assertTrue(infoMsg.getReceivedTime() <= after);
    }
}
