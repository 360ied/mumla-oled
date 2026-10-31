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

public class CrashReportScrubberTest extends TestCase {

    public void testMumbleUrlRedacted() {
        String out = CrashReportScrubber.scrub("open mumble://alice:secret@voice.example.com:64738/root\n\tat Foo.bar(Foo.java:1)");
        assertFalse(out.contains("secret"));
        assertFalse(out.contains("voice.example.com"));
        assertTrue(out.contains("[MUMBLE-URL-REDACTED]"));
    }

    public void testPasswordEqualsRedacted() {
        String out = CrashReportScrubber.scrub("java.lang.RuntimeException: password=hunter2\n\tat Foo.bar(Foo.java:10)");
        assertTrue(out.startsWith("java.lang.RuntimeException: password=[CREDENTIAL-REDACTED]"));
        assertFalse(out.contains("hunter2"));
        assertTrue(out.contains("at Foo.bar(Foo.java:10)"));
    }

    public void testPasswordColonRedacted() {
        String out = CrashReportScrubber.scrub("java.lang.RuntimeException: password: hunter2\n\tat Foo.bar(Foo.java:1)");
        assertTrue(out.contains("password=[CREDENTIAL-REDACTED]"));
        assertFalse(out.contains("hunter2"));
    }

    public void testTokenRedacted() {
        String out = CrashReportScrubber.scrub("auth token=abc123\n\tat Foo.bar(Foo.java:1)");
        assertFalse(out.contains("abc123"));
    }

    public void testUsernameRedacted() {
        String out = CrashReportScrubber.scrub("java.lang.IllegalStateException: username=alice\n\tat a.B.c(a.java:1)");
        assertTrue(out.contains("username=[USER-REDACTED]"));
        assertFalse(out.contains("alice"));
    }

    public void testCausedByCredentialRedacted() {
        String out = CrashReportScrubber.scrub("java.lang.RuntimeException: boom\nCaused by: java.lang.IllegalStateException: password=hunter2\n\tat Foo.bar(Foo.java:1)");
        assertFalse(out.contains("hunter2"));
        assertTrue(out.contains("Caused by:"));
    }

    // Non-credential content passes through verbatim by design: reports
    // stay debuggable (tombstone build IDs must survive symbolization).

    public void testHostnamePassesThrough() {
        String out = CrashReportScrubber.scrub("Failed to connect to voice.example.com\n\tat Foo.bar(Foo.java:1)");
        assertTrue(out.contains("voice.example.com"));
    }

    public void testIpPassesThrough() {
        String out = CrashReportScrubber.scrub("connect to 192.168.1.10 failed\n\tat Foo.bar(Foo.java:1)");
        assertTrue(out.contains("192.168.1.10"));
    }

    public void testHexPassesThrough() {
        String out = CrashReportScrubber.scrub("build-id abcdef0123456789abcdef0123456789abcdef01\n\tat Foo.bar(Foo.java:1)");
        assertTrue(out.contains("abcdef0123456789abcdef0123456789abcdef01"));
    }

    public void testEqualsWithoutCredentialKept() {
        String out = CrashReportScrubber.scrub("java.lang.IllegalArgumentException: bad index max=10\n\tat Foo.bar(Foo.java:5)");
        assertTrue(out.contains("bad index max=10"));
    }

    public void testFramesPreserved() {
        String inTrace = "java.lang.NullPointerException\n\tat se.lublin.mumla.app.MumlaActivity.onCreate(MumlaActivity.kt:42)";
        String out = CrashReportScrubber.scrub(inTrace);
        assertTrue(out.contains("se.lublin.mumla.app.MumlaActivity.onCreate(MumlaActivity.kt:42)"));
    }

    public void testNullGivesEmpty() {
        assertEquals("", CrashReportScrubber.scrub(null));
        assertEquals("", CrashReportScrubber.scrub(""));
    }
}
