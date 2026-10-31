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
import java.io.File;
import java.nio.file.Files;

/**
 * JVM coverage for the hand-rolled CrashReporter extractors via the public
 * renderSharePayload + mergePending surface (no Android runtime needed).
 */
public class CrashReporterTest extends TestCase {

    public void testRenderSharePayloadIncludesTriageHeaders() {
        String json = CrashReportStore.toJson("1-2", 1000L, 2000L, null,
                "exit-info", "REASON_CRASH", "v", "36", "model", "arm64-v8a",
                "proc", 2, true, "trace line", 11L, 22L);
        try {
            java.lang.reflect.Method m = CrashReporter.class.getMethod("renderSharePayload", String.class);
            @SuppressWarnings("unchecked")
            kotlin.Pair<String, String> out = (kotlin.Pair<String, String>) m.invoke(null, json);
            assertNotNull(out);
            String txt = out.getSecond();
            assertTrue(txt.contains("osVersion: 36"));
            assertTrue(txt.contains("deviceModel: model"));
            assertTrue(txt.contains("truncated: true"));
            assertTrue(txt.contains("trace line"));
        } catch (Exception e) {
            fail("renderSharePayload failed: " + e);
        }
    }

    public void testMergePendingKeepsUnreadableExit() throws Exception {
        File dir = Files.createTempDirectory("crashreporter-test-").toFile();
        try {
            CrashReportStore store = new CrashReportStore(dir);
            String pendingJson = CrashReportStore.toJson("100-42-7-pending", null, 100L, 100L,
                    "uncaught-handler", "REASON_CRASH", "v", "o", "m", "a", "p", 42, false, "", 0L, 0L);
            assertNotNull(store.write("100-42-7-pending", pendingJson));
            // Corrupt exit entry with same pid/reason inside the dedup window:
            // parseSummary skips it, but the raw file exists and is unreadable
            // as JSON — pending must be kept (fail safe toward duplicates).
            File corruptExit = new File(dir, "150-42.json");
            java.nio.file.Files.write(corruptExit.toPath(), "not json".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            CrashReporter.INSTANCE.mergePending(store);
            assertTrue(new File(dir, CrashReportStore.sanitizeId("100-42-7-pending", 128) + ".json").isFile());
        } finally {
            deleteRecursive(dir);
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteRecursive(k);
            }
        }
        if (!f.delete()) f.deleteOnExit();
    }
}
