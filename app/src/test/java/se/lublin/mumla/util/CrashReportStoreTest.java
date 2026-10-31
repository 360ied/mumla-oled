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
import java.io.FileWriter;
import java.util.HashSet;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.List;
import kotlin.Pair;

public class CrashReportStoreTest extends TestCase {



    private String json(String id) {
        return CrashReportStore.toJson(id, null, 1L, null, "uncaught-handler",
                "REASON_CRASH", "1.0", "36", "model", "arm64-v8a",
                "proc", 123, false, "trace", 0L, 0L);
    }

    public void testWriteAndList() throws Exception {
        File dir = CrashTestFiles.tmpDir();
        try {
            CrashReportStore store = new CrashReportStore(dir);
            assertNotNull(store.write("a-1", json("a-1")));
            List<CrashReportStore.Entry> entries = store.list();
            assertEquals(1, entries.size());
            assertEquals("a-1", entries.get(0).getId());
        } finally {
            CrashTestFiles.deleteRecursive(dir);
        }
    }

    public void testRotationKeepsNewest8() throws Exception {
        File dir = CrashTestFiles.tmpDir();
        try {
            CrashReportStore store = new CrashReportStore(dir);
            for (int i = 0; i < 10; i++) {
                assertNotNull(store.write("id-" + i, json("id-" + i)));
                // Deterministic ordering without sleeping: lastModified
                // granularity is coarse, and list() tie-breaks on id anyway.
                File f = new File(dir, "id-" + i + ".json");
                assertTrue(f.setLastModified(1_000_000L + i * 1000L));
            }
            List<CrashReportStore.Entry> entries = store.list();
            assertEquals(8, entries.size());
            Set<String> ids = new HashSet<String>();
            for (CrashReportStore.Entry e : entries) ids.add(e.getId());
            for (int i = 2; i < 10; i++) assertTrue("missing id-" + i, ids.contains("id-" + i));
            assertFalse(ids.contains("id-0"));
            assertFalse(ids.contains("id-1"));
        } finally {
            CrashTestFiles.deleteRecursive(dir);
        }
    }

    public void testTruncatedFlagSurvivesEscape() throws Exception {
        String tricky = "quote \" backslash \\ newline \n tab \t ctrl \u0001 emoji \uD83D\uDE00";
        String json = CrashReportStore.toJson("rt-1", 5L, 6L, 7L, "s", "r", "v", "o", "m", "a", "p", 9, true, tricky, 1L, 2L);
        File dir = CrashTestFiles.tmpDir();
        try {
            CrashReportStore store = new CrashReportStore(dir);
            assertNotNull(store.write("rt-1", json));
            List<CrashReportStore.Entry> entries = store.list();
            assertEquals(1, entries.size());
            String raw = store.read(entries.get(0));
            assertNotNull(raw);
            kotlin.Pair<String, String> rendered = se.lublin.mumla.util.CrashReporter.renderSharePayload(raw);
            assertNotNull(rendered);
            assertTrue(rendered.getSecond().contains("truncated: true"));
            // Escape round-trip: quotes and backslashes survive verbatim.
            assertTrue(rendered.getSecond().contains("quote \" backslash \\"));
        } finally {
            CrashTestFiles.deleteRecursive(dir);
        }
    }

    public void testCorruptFileSkipped() throws Exception {
        File dir = CrashTestFiles.tmpDir();
        try {
            CrashReportStore store = new CrashReportStore(dir);
            assertNotNull(store.write("good-1", json("good-1")));
            try (FileWriter w = new FileWriter(new File(dir, "bad.json"), StandardCharsets.UTF_8)) {
                w.write("this is not json {{{");
            }
            List<CrashReportStore.Entry> entries = store.list();
            assertEquals(1, entries.size());
            assertEquals("good-1", entries.get(0).getId());
        } finally {
            CrashTestFiles.deleteRecursive(dir);
        }
    }

    public void testAtomicWriteNoTmpLeft() throws Exception {
        File dir = CrashTestFiles.tmpDir();
        try {
            CrashReportStore store = new CrashReportStore(dir);
            assertNotNull(store.write("x-1", json("x-1")));
            File[] files = dir.listFiles();
            assertNotNull(files);
            for (File f : files) {
                assertFalse(f.getName().endsWith(".tmp"));
            }
        } finally {
            CrashTestFiles.deleteRecursive(dir);
        }
    }

    public void testDeleteAllRemovesTxt() throws Exception {
        File dir = CrashTestFiles.tmpDir();
        try {
            CrashReportStore store = new CrashReportStore(dir);
            assertNotNull(store.write("r-1", json("r-1")));
            try (FileWriter w = new FileWriter(new File(dir, "r-1.txt"), StandardCharsets.UTF_8)) {
                w.write("share payload");
            }
            // Watermark is intentionally retained (prevents re-surfacing).
            java.nio.file.Files.write(new File(dir, "watermark").toPath(), "123".getBytes(StandardCharsets.UTF_8));
            assertTrue(store.deleteAll());
            assertTrue(new File(dir, "watermark").isFile());
            File[] files = dir.listFiles();
            assertNotNull(files);
            assertEquals(1, files.length);
            assertEquals("watermark", files[0].getName());
        } finally {
            CrashTestFiles.deleteRecursive(dir);
        }
    }

    public void testTruncatedFlagBounded() throws Exception {
        File dir = CrashTestFiles.tmpDir();
        try {
            CrashReportStore store = new CrashReportStore(dir);
            // Trace body contains the literal; header flag stays false.
            String trace = "body with \\\"truncated\\\":true inside";
            String payload = CrashReportStore.toJson("t-1", null, 1L, null, "s", "r", "v", "o", "m", "a", "p", 1, false, trace, 0L, 0L);
            assertNotNull(store.write("t-1", payload));
            List<CrashReportStore.Entry> entries = store.list();
            assertEquals(1, entries.size());
            assertFalse(entries.get(0).getTruncated());
        } finally {
            CrashTestFiles.deleteRecursive(dir);
        }
    }

    public void testLargePayloadTruncated() throws Exception {
        File dir = CrashTestFiles.tmpDir();
        try {
            CrashReportStore store = new CrashReportStore(dir);
            // Backstop path: uncapped write of invalid JSON (raw suffix past
            // '}') exercises only the length cap, not the toJsonCapped path
            // (covered separately by testToJsonCappedFitsExplodingTrace).
            StringBuilder big = new StringBuilder();
            for (int i = 0; i < 300 * 1024; i++) {
                big.append('a');
            }
            File out = store.write("big-1", json("big-1") + big.toString());
            assertNotNull(out);
            assertTrue(out.length() <= CrashReportStore.MAX_FILE_BYTES);
        } finally {
            CrashTestFiles.deleteRecursive(dir);
        }
    }

    public void testToJsonCappedSmallPassthrough() throws Exception {
        String expected = CrashReportStore.toJson("c-1", null, 1L, null, "s", "r", "v", "o", "m", "a", "p", 1, false, "trace", 0L, 0L);
        String actual = CrashReportStore.toJsonCapped("c-1", null, 1L, null, "s", "r", "v", "o", "m", "a", "p", 1, false, "trace", 0L, 0L);
        assertEquals(expected, actual);
    }

    public void testToJsonCappedFitsExplodingTrace() throws Exception {
        File dir = CrashTestFiles.tmpDir();
        try {
            // Tombstone-like control-heavy trace: JSON escaping expands each
            // control byte to a 6-char unicode escape, far beyond the raw cap.
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 256 * 1024; i++) {
                sb.append((char) (i % 32));
            }
            String payload = CrashReportStore.toJsonCapped("big-2", 1L, 2L, null,
                    "exit-info", "REASON_CRASH_NATIVE", "v", "o", "m", "a", "p", 1, false,
                    sb.toString(), 0L, 0L);
            assertTrue(payload.getBytes(StandardCharsets.UTF_8).length <= CrashReportStore.MAX_FILE_BYTES);
            // Tail fields intact: valid JSON, not a mid-string cut.
            assertTrue(payload.endsWith("}"));
            assertTrue(payload.contains("\"pssKb\":0"));
            assertTrue(payload.contains("\"reason\":\"REASON_CRASH_NATIVE\""));
            CrashReportStore store = new CrashReportStore(dir);
            assertNotNull(store.write("big-2", payload));
            List<CrashReportStore.Entry> entries = store.list();
            assertEquals(1, entries.size());
            assertEquals("big-2", entries.get(0).getId());
            assertEquals("REASON_CRASH_NATIVE", entries.get(0).getReason());
            assertTrue(entries.get(0).getTruncated());
        } finally {
            CrashTestFiles.deleteRecursive(dir);
        }
    }

    public void testPreviewSmallPassthrough() throws Exception {
        Pair<String, Integer> r = CrashReportStore.previewForDisplay("a\nb");
        assertEquals("a\nb", r.getFirst());
        assertEquals(Integer.valueOf(0), r.getSecond());
    }

    public void testPreviewCapsLines() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            sb.append("line ").append(i).append("\n");
        }
        Pair<String, Integer> r = CrashReportStore.previewForDisplay(sb.toString());
        assertEquals(Integer.valueOf(150), r.getSecond());
        assertTrue(r.getFirst().startsWith("line 0\n"));
        assertTrue(r.getFirst().endsWith("line 99"));
    }

    public void testPreviewCapsLongLines() throws Exception {
        // One huge line: the char budget still bounds the preview.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50000; i++) {
            sb.append('x');
        }
        Pair<String, Integer> r = CrashReportStore.previewForDisplay(sb.toString());
        assertTrue(r.getFirst().length() <= 12000);
        assertTrue(r.getFirst().length() > 0);
        assertEquals(Integer.valueOf(0), r.getSecond());
    }
}
