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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import junit.framework.TestCase;

/**
 * JVM coverage for the hand-rolled CrashReporter extractors via the public
 * renderSharePayload + mergePending surface (no Android runtime needed).
 */
public class CrashReporterTest extends TestCase {

    public void testRenderSharePayloadIncludesTriageHeaders() {
        String json = CrashReportStore.toJson("1-2", 1000L, 2000L, null,
                "exit-info", "REASON_CRASH", "v", "36", "model", "arm64-v8a",
                "proc", 2, true, "trace line", 11L, 22L);
        kotlin.Pair<String, String> out = CrashReporter.renderSharePayload(json);
        assertNotNull(out);
        String txt = out.getSecond();
        assertTrue(txt.contains("osVersion: 36"));
        assertTrue(txt.contains("deviceModel: model"));
        assertTrue(txt.contains("truncated: true"));
        assertTrue(txt.contains("trace line"));
        assertTrue(txt.contains("capturedAtUtc: "));
    }

    public void testMergePendingKeepsUnreadableExit() throws Exception {
        File dir = CrashTestFiles.tmpDir();
        try {
            CrashReportStore store = new CrashReportStore(dir);
            String pendingJson = CrashReportStore.toJson("100-42-7-pending", null, 100L, 100L,
                    "uncaught-handler", "REASON_CRASH", "v", "o", "m", "a", "p", 42, false, "", 0L, 0L);
            assertNotNull(store.write("100-42-7-pending", pendingJson));
            // Corrupt exit entry with same pid/reason inside the dedup window:
            // parseSummary skips it, so no match exists — pending is kept
            // via the trivial no-match path (documents the limit).
            File corruptExit = new File(dir, "150-42.json");
            Files.write(corruptExit.toPath(), "not json".getBytes(StandardCharsets.UTF_8));
            CrashReporter.INSTANCE.mergePending(store);
            assertTrue(new File(dir, CrashReportStore.sanitizeId("100-42-7-pending", 128) + ".json").isFile());
        } finally {
            CrashTestFiles.deleteRecursive(dir);
        }
    }

    public void testMergePendingKeepsDivergentExit() throws Exception {
        File dir = CrashTestFiles.tmpDir();
        try {
            CrashReportStore store = new CrashReportStore(dir);
            // Valid exit entry, same pid/reason inside the window, but the
            // first-512-chars trace differs (exit stream lacks the
            // process=/thread= headers) — pending must survive.
            String pendingTrace = "process=proc\nthread=main\njava.lang.RuntimeException: boom";
            String pendingJson = CrashReportStore.toJson("100-42-9-pending", null, 100L, 100L,
                    "uncaught-handler", "REASON_CRASH", "v", "o", "m", "a", "p", 42, false, pendingTrace, 0L, 0L);
            assertNotNull(store.write("100-42-9-pending", pendingJson));
            String exitTrace = "java.lang.RuntimeException: different";
            String exitJson = CrashReportStore.toJson("150-42", 150L, 151L, null,
                    "exit-info", "REASON_CRASH", "v", "o", "m", "a", "p", 42, false, exitTrace, 0L, 0L);
            assertNotNull(store.write("150-42", exitJson));
            CrashReporter.INSTANCE.mergePending(store);
            assertTrue(new File(dir, CrashReportStore.sanitizeId("100-42-9-pending", 128) + ".json").isFile());
        } finally {
            CrashTestFiles.deleteRecursive(dir);
        }
    }

    public void testMergePendingDropsConfirmedExit() throws Exception {
        File dir = CrashTestFiles.tmpDir();
        try {
            CrashReportStore store = new CrashReportStore(dir);
            // Same trace body on both sides (modulo pending headers): the
            // secondary prefix check passes, so the pending is dropped.
            String body = "java.lang.RuntimeException: boom\n\tat Foo.bar(Foo.java:1)";
            String pendingJson = CrashReportStore.toJson("100-42-11-pending", null, 100L, 100L,
                    "uncaught-handler", "REASON_CRASH", "v", "o", "m", "a", "p", 42, false,
                    "process=proc\nthread=main\n" + body, 0L, 0L);
            assertNotNull(store.write("100-42-11-pending", pendingJson));
            String exitJson = CrashReportStore.toJson("150-42", 150L, 151L, null,
                    "exit-info", "REASON_CRASH", "v", "o", "m", "a", "p", 42, false, body, 0L, 0L);
            assertNotNull(store.write("150-42", exitJson));
            CrashReporter.INSTANCE.mergePending(store);
            assertFalse(new File(dir, CrashReportStore.sanitizeId("100-42-11-pending", 128) + ".json").isFile());
            assertTrue(new File(dir, CrashReportStore.sanitizeId("150-42", 128) + ".json").isFile());
        } finally {
            CrashTestFiles.deleteRecursive(dir);
        }
    }
}
