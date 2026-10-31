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

    public void testIpv4Redacted() {
        // First-line message with an IP collapses to the skeleton (<redacted>).
        String out = CrashReportScrubber.scrub("java.io.IOException: connect to 192.168.1.10 failed\n\tat Foo.bar(Foo.java:1)");
        assertFalse(out.contains("192.168.1.10"));
        assertTrue(out.contains("<redacted>"));
        // Off-first-line IPs keep the token form.
        String trace = CrashReportScrubber.scrub("java.io.IOException\n\tconnect to 192.168.1.10 failed\n\tat Foo.bar(Foo.java:1)");
        assertTrue(trace.contains("[IP-REDACTED]"));
    }

    public void testIpv6Redacted() {
        String out = CrashReportScrubber.scrub("cause fe80::1 port\n\tat Foo.bar(Foo.java:1)");
        assertFalse(out.contains("fe80::1"));
    }

    public void testUserAtHostRedacted() {
        String out = CrashReportScrubber.scrub("login alice@example.com denied\n\tat Foo.bar(Foo.java:1)");
        assertFalse(out.contains("alice@example.com"));
    }

    public void testMumbleUrlRedacted() {
        String out = CrashReportScrubber.scrub("open mumble://alice:secret@voice.example.com:64738/root\n\tat Foo.bar(Foo.java:1)");
        assertFalse(out.contains("voice.example.com"));
        assertTrue(out.contains("[MUMBLE-URL-REDACTED]"));
    }

    public void testBareHostnameRedacted() {
        String out = CrashReportScrubber.scrub("Failed to connect to voice.example.com\n\tat Foo.bar(Foo.java:1)");
        assertFalse(out.contains("voice.example.com"));
        assertTrue(out.contains("[HOST-REDACTED]") || out.contains("<redacted>"));
    }

    public void testFingerprintRedacted() {
        String out = CrashReportScrubber.scrub("SHA-256 AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99\n\tat Foo.bar(Foo.java:1)");
        assertFalse(out.contains("AA:BB:CC"));
        assertTrue(out.contains("[FINGERPRINT-REDACTED]"));
    }

    public void testCredentialKeyMaterialSkeleton() {
        String out = CrashReportScrubber.scrub("java.lang.RuntimeException: password=hunter2\n\tat Foo.bar(Foo.java:10)");
        // Message body collapses to <redacted>, class + frame preserved.
        assertTrue(out.startsWith("java.lang.RuntimeException: <redacted>"));
        assertTrue(out.contains("at Foo.bar(Foo.java:10)"));
    }

    public void testUsernameRedacted() {
        String out = CrashReportScrubber.scrub("java.lang.IllegalStateException: username=alice\n\tat a.B.c(a.java:1)");
        assertTrue(out.contains("<redacted>"));
        assertFalse(out.contains("alice"));
    }

    public void testFilePathRedacted() {
        String out = CrashReportScrubber.scrub("open /data/data/se.lublin.mumla/files/x failed\n\tat Foo.bar(Foo.java:1)");
        assertFalse(out.contains("/data/data"));
        assertTrue(out.contains("[PATH-REDACTED]"));
    }

    public void testFramesPreserved() {
        String inTrace = "java.lang.NullPointerException\n\tat se.lublin.mumla.app.MumlaActivity.onCreate(MumlaActivity.kt:42)";
        String out = CrashReportScrubber.scrub(inTrace);
        assertTrue(out.contains("se.lublin.mumla.app.MumlaActivity.onCreate(MumlaActivity.kt:42)"));
    }

    public void testCausedByKeyMaterialCollapsed() {
        String out = CrashReportScrubber.scrub("java.lang.RuntimeException: boom\nCaused by: java.lang.IllegalStateException: password=hunter2\n\tat Foo.bar(Foo.java:1)");
        assertFalse(out.contains("hunter2"));
        assertTrue(out.contains("Caused by:") && out.contains("<redacted>"));
    }

    public void testBareHostXyzRedacted() {
        String out = CrashReportScrubber.scrub("Failed to connect to voice.example.xyz\n\tat Foo.bar(Foo.java:1)");
        assertFalse(out.contains("voice.example.xyz"));
    }

    public void testSingleLabelUserHostRedacted() {
        String out = CrashReportScrubber.scrub("login alice@intranet denied\n\tat Foo.bar(Foo.java:1)");
        assertFalse(out.contains("alice@intranet"));
    }

    public void testColonCredentialCollapsed() {
        String out = CrashReportScrubber.scrub("java.lang.RuntimeException: password: hunter2\n\tat Foo.bar(Foo.java:1)");
        assertTrue(out.contains("<redacted>"));
        assertFalse(out.contains("hunter2"));
    }

    public void testNullGivesEmpty() {
        assertEquals("", CrashReportScrubber.scrub(null));
        assertEquals("", CrashReportScrubber.scrub(""));
    }

    public void testBenignMessageKept() {
        String out = CrashReportScrubber.scrub("java.lang.IllegalArgumentException: bad index\n\tat Foo.bar(Foo.java:5)");
        assertTrue(out.contains("bad index"));
    }
}
