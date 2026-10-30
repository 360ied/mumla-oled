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
import java.util.List;

public class CrashReportStoreTest extends TestCase {

    private File tmpDir() throws Exception {
        File base = new File(System.getProperty("java.io.tmpdir"));
        File dir = new File(base, "crashstore-test-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        return dir;
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursive(k);
                }
            }
        }
        f.delete();
    }

    private String json(String id) {
        return CrashReportStore.toJson(id, null, 1L, null, "uncaught-handler",
                "REASON_CRASH", "1.0", "36", "model", "arm64-v8a",
                "proc", 123, false, "trace", 0L, 0L);
    }

    public void testWriteAndList() throws Exception {
        File dir = tmpDir();
        try {
            CrashReportStore store = new CrashReportStore(dir);
            assertNotNull(store.write("a-1", json("a-1")));
            List<CrashReportStore.Entry> entries = store.list();
            assertEquals(1, entries.size());
            assertEquals("a-1", entries.get(0).getId());
        } finally {
            deleteRecursive(dir);
        }
    }

    public void testRotationKeepsNewest8() throws Exception {
        File dir = tmpDir();
        try {
            CrashReportStore store = new CrashReportStore(dir);
            for (int i = 0; i < 10; i++) {
                assertNotNull(store.write("id-" + i, json("id-" + i)));
                // Distinct mtimes for deterministic ordering.
                Thread.sleep(5);
            }
            List<CrashReportStore.Entry> entries = store.list();
            assertEquals(8, entries.size());
        } finally {
            deleteRecursive(dir);
        }
    }

    public void testCorruptFileSkipped() throws Exception {
        File dir = tmpDir();
        try {
            CrashReportStore store = new CrashReportStore(dir);
            assertNotNull(store.write("good-1", json("good-1")));
            java.io.FileWriter w = new java.io.FileWriter(new File(dir, "bad.json"));
            w.write("this is not json {{{");
            w.close();
            List<CrashReportStore.Entry> entries = store.list();
            assertEquals(1, entries.size());
            assertEquals("good-1", entries.get(0).getId());
        } finally {
            deleteRecursive(dir);
        }
    }

    public void testAtomicWriteNoTmpLeft() throws Exception {
        File dir = tmpDir();
        try {
            CrashReportStore store = new CrashReportStore(dir);
            assertNotNull(store.write("x-1", json("x-1")));
            File[] files = dir.listFiles();
            assertNotNull(files);
            for (File f : files) {
                assertFalse(f.getName().endsWith(".tmp"));
            }
        } finally {
            deleteRecursive(dir);
        }
    }

    public void testLargePayloadTruncated() throws Exception {
        File dir = tmpDir();
        try {
            CrashReportStore store = new CrashReportStore(dir);
            StringBuilder big = new StringBuilder();
            for (int i = 0; i < 300 * 1024; i++) {
                big.append('a');
            }
            File out = store.write("big-1", json("big-1") + big.toString());
            assertNotNull(out);
            assertTrue(out.length() <= CrashReportStore.MAX_FILE_BYTES);
        } finally {
            deleteRecursive(dir);
        }
    }
}
