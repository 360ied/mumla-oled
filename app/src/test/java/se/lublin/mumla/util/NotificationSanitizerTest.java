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

package se.lublin.mumla.util;

import junit.framework.TestCase;

public class NotificationSanitizerTest extends TestCase {

    public void testSanitizeActor_StripsHtml() {
        assertEquals("Evil", NotificationSanitizer.sanitizeActor("<b>Evil</b>"));
    }

    public void testSanitizeActor_NeutralizesLinkMarkup() {
        String actor = NotificationSanitizer.sanitizeActor(
                "<a href=\"javascript:alert(1)\">click</a>");
        assertEquals("click", actor);
    }

    public void testSanitizeActor_CollapsesNewlineInjection() {
        String actor = NotificationSanitizer.sanitizeActor("alice\ninjected line");
        assertFalse(actor.contains("\n"));
        assertFalse(actor.contains("\r"));
        assertEquals("alice injected line", actor);
    }

    public void testSanitizeActor_RemovesControlChars() {
        String actor = NotificationSanitizer.sanitizeActor("al\u0000ice\u0007bob");
        assertEquals("alicebob", actor);
    }

    public void testSanitizeActor_TrimsWhitespace() {
        assertEquals("alice", NotificationSanitizer.sanitizeActor("   alice   "));
    }

    public void testSanitizeActor_TruncatesLongName() {
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longName.append('a');
        }
        String actor = NotificationSanitizer.sanitizeActor(longName.toString());
        assertTrue(actor.length() <= NotificationSanitizer.MAX_ACTOR_LENGTH);
        assertEquals(NotificationSanitizer.MAX_ACTOR_LENGTH, actor.length());
    }

    public void testSanitizeActor_NullGivesEmpty() {
        assertEquals("", NotificationSanitizer.sanitizeActor(null));
    }

    public void testSanitizeActor_EmptyGivesEmpty() {
        assertEquals("", NotificationSanitizer.sanitizeActor(""));
    }

    public void testSanitizeActor_TagOnlyGivesEmpty() {
        assertEquals("", NotificationSanitizer.sanitizeActor("<img src=x onerror=alert(1)>"));
    }

    public void testSanitizeBody_StripsHtml() {
        assertEquals("Hello world",
                NotificationSanitizer.sanitizeBody("<p>Hello <b>world</b></p>"));
    }

    public void testSanitizeBody_CollapsesNewlines() {
        String body = NotificationSanitizer.sanitizeBody("line one\nline two\r\nline three");
        assertFalse(body.contains("\n"));
        assertFalse(body.contains("\r"));
        assertEquals("line one line two line three", body);
    }

    public void testSanitizeBody_RemovesControlChars() {
        assertEquals("helloworld",
                NotificationSanitizer.sanitizeBody("hello\u0000world\u001B"));
    }

    public void testSanitizeBody_NullGivesEmpty() {
        assertEquals("", NotificationSanitizer.sanitizeBody(null));
    }

    public void testSanitizeBody_EmptyGivesEmpty() {
        assertEquals("", NotificationSanitizer.sanitizeBody(""));
    }

    public void testSanitizeActor_StripsBidiOverrides() {
        assertEquals("alice", NotificationSanitizer.sanitizeActor("a\u202Elic\u202Ce"));
    }

    public void testSanitizeActor_TruncatesWithoutSplittingSurrogate() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 63; i++) {
            name.append('a');
        }
        name.append("\uD83D\uDE00"); // U+1F600, one code point, two chars
        name.append('b');
        String actor = NotificationSanitizer.sanitizeActor(name.toString());
        assertEquals(NotificationSanitizer.MAX_ACTOR_LENGTH, actor.codePointCount(0, actor.length()));
        assertEquals(0x1F600, actor.codePointBefore(actor.length()));
    }
}
