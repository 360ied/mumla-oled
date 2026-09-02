/*
 * Copyright (C) 2026 Mumla OLED Contributors
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

import java.util.concurrent.atomic.AtomicBoolean;

import se.lublin.humla.model.Message;

public class IChatMessageTest extends TestCase {

    public void testTextMessageDefaultNotSelfAuthored() {
        Message rawMessage = new Message("Hello from remote");
        IChatMessage.TextMessage textMsg = new IChatMessage.TextMessage(rawMessage);

        assertFalse("Default TextMessage constructor must not be self-authored",
                textMsg.isSelfAuthored());
        assertEquals("Hello from remote", textMsg.getBody());
        assertSame(rawMessage, textMsg.getMessage());
    }

    public void testTextMessageExplicitSelfAuthored() {
        Message rawMessage = new Message("Hello from me");
        IChatMessage.TextMessage textMsg = new IChatMessage.TextMessage(rawMessage, true);

        assertTrue("Explicitly authored TextMessage must report isSelfAuthored as true",
                textMsg.isSelfAuthored());
        assertEquals("Hello from me", textMsg.getBody());
    }

    public void testTextMessageVisitor() {
        Message rawMessage = new Message("Test visitor");
        IChatMessage.TextMessage textMsg = new IChatMessage.TextMessage(rawMessage, true);

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
