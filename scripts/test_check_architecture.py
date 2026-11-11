#!/usr/bin/env python3
# Copyright (C) 2026 Brian Zhu
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <http://www.gnu.org/licenses/>.
"""
Unit tests for scripts/check_architecture.py.
"""

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
import check_architecture as ca


class TestParseAllowlist(unittest.TestCase):
    def test_plain_and_native(self):
        text = (
            "# comment\n"
            "a/b.kt  4\n"
            "c/d.java  1\n"
            "\n"
            "[native]\n"
            "libraries/humla/src/main/jni/crypto/CryptStateOCB2.cpp: <fcntl.h>\n"
        )
        plain, native, errors = ca.parse_allowlist(text)
        self.assertEqual(errors, [])
        self.assertEqual(plain, {"a/b.kt": 4, "c/d.java": 1})
        self.assertEqual(
            native,
            [("libraries/humla/src/main/jni/crypto/CryptStateOCB2.cpp", "<fcntl.h>")],
        )

    def test_malformed_lines_are_errors(self):
        plain, native, errors = ca.parse_allowlist(
            "justapath\n"
            "a.kt notanint\n"
            "b.kt -1\n"
            "c.kt 0\n"
            "/abs/path.kt 3\n"
            "ok.kt 4\n"
            "ok.kt 5\n"
            "\n[native]\n"
            "no-colon\n"
            "x.cpp: \n"
        )
        # The first ok.kt entry parses; the repeat is the duplicate error.
        self.assertEqual(plain, {"ok.kt": 4})
        self.assertEqual(native, [])
        self.assertEqual(len(errors), 8)

    def test_duplicate_plain_is_error(self):
        _, _, errors = ca.parse_allowlist("a.kt 1\na.kt 2\n")
        self.assertTrue(any("duplicate" in e for e in errors))

    def test_duplicate_native_is_error(self):
        _, _, errors = ca.parse_allowlist(
            "[native]\na.cpp: <jni.h>\na.cpp: <jni.h>\n"
        )
        self.assertTrue(any("duplicate" in e for e in errors))

    def test_same_path_different_header_is_fine(self):
        _, native, errors = ca.parse_allowlist(
            "[native]\na.cpp: <fcntl.h>\na.cpp: <unistd.h>\n"
        )
        self.assertEqual(errors, [])
        self.assertEqual(len(native), 2)


class TestConcurrencyCheck(unittest.TestCase):
    def test_unknown_file_fails(self):
        errs = ca.check_concurrency({"x.kt": 2}, {})
        self.assertEqual(len(errs), 1)
        self.assertIn("not on the allowlist", errs[0])

    def test_boundary_file_message(self):
        path = list(ca.BOUNDARY_FILES)[0]
        errs = ca.check_concurrency({path: 3}, {})
        self.assertEqual(len(errs), 1)
        self.assertIn("boundary file", errs[0].lower())

    def test_more_fails(self):
        errs = ca.check_concurrency({"a.kt": 5}, {"a.kt": 4})
        self.assertTrue(any("expected 4" in e for e in errs))

    def test_fewer_must_lower(self):
        errs = ca.check_concurrency({"a.kt": 2}, {"a.kt": 4})
        self.assertTrue(any("lower the count" in e for e in errs))

    def test_stale_entry_fails(self):
        errs = ca.check_concurrency({}, {"a.kt": 4})
        self.assertTrue(any("remove it" in e for e in errs))

    def test_exact_passes(self):
        self.assertEqual(ca.check_concurrency({"a.kt": 4}, {"a.kt": 4}), [])


class TestPatterns(unittest.TestCase):
    def test_word_boundaries(self):
        # currentThread() must not match the Thread pattern; the
        # createAudioHandler( must not match the Handler pattern.
        self.assertIsNone(ca.RE_CONCURRENCY.search("currentThread()"))
        self.assertIsNone(ca.RE_CONCURRENCY.search("createAudioHandler()"))
        self.assertIsNone(ca.RE_CONCURRENCY.search("Thread.sleep(1)"))
        self.assertIsNone(ca.RE_CONCURRENCY.search("Thread.currentThread()"))
        self.assertIsNotNone(ca.RE_CONCURRENCY.search("Thread { }"))
        self.assertIsNotNone(ca.RE_CONCURRENCY.search("Handler(foo)"))
        self.assertIsNotNone(ca.RE_CONCURRENCY.search("@Volatile"))
        self.assertIsNotNone(ca.RE_CONCURRENCY.search("runBlocking { }"))

    def test_whitespace_variants_match(self):
        self.assertIsNotNone(ca.RE_CONCURRENCY.search("synchronized (lock)"))
        self.assertIsNotNone(ca.RE_CONCURRENCY.search("Handler (foo)"))
        self.assertIsNotNone(ca.RE_CONCURRENCY.search("HandlerThread (x)"))

    def test_kotlin_thread_import_matches_prose_does_not(self):
        self.assertIsNotNone(ca.RE_CONCURRENCY.search("import kotlin.concurrent.thread"))
        # Bare prose "thread (" in comments must not match (too noisy).
        self.assertIsNone(ca.RE_CONCURRENCY.search("// otherwise, NPEing the render thread (dead audio)"))

    def test_generated_marker_excluded(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            gen = root / "libraries/humla/src/main/java/se/lublin/humla/protobuf"
            gen.mkdir(parents=True)
            (gen / "Mumble.java").write_text("synchronized (x) {}\n", encoding="utf-8")
            self.assertEqual(ca.scan_concurrency(root), {})


class TestAndroidFree(unittest.TestCase):
    def test_detects_android_import(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pkg = root / "libraries/humla/src/main/java/se/lublin/humla/state"
            pkg.mkdir(parents=True)
            (pkg / "Foo.kt").write_text("package x\nimport android.os.Bundle\n", encoding="utf-8")
            (pkg / "Bar.kt").write_text("package x\n// clean\n", encoding="utf-8")
            v = ca.scan_android_free(root)
            self.assertEqual(len(v), 1)
            self.assertIn("Foo.kt", v[0][0])

    def test_detects_androidx_and_static_r(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pkg = root / "libraries/humla/src/main/java/se/lublin/humla/client"
            pkg.mkdir(parents=True)
            (pkg / "A.kt").write_text("import androidx.core.app.Foo\n", encoding="utf-8")
            (pkg / "B.java").write_text("import static foo.R.CONST;\n", encoding="utf-8")
            v = ca.scan_android_free(root)
            self.assertEqual(len(v), 2)

    def test_missing_dirs_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(ca.scan_android_free(Path(tmp)), [])

    def test_net_excludes_tls_factory_and_legacy(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pkg = root / "libraries/humla/src/main/java/se/lublin/humla/net"
            pkg.mkdir(parents=True)
            (pkg / "HumlaSSLSocketFactory.kt").write_text(
                "import android.net.SSL\n", encoding="utf-8"
            )
            for legacy in ("HumlaConnection.kt", "HumlaTCP.kt", "HumlaUDP.kt"):
                (pkg / legacy).write_text("import android.os.Handler\n", encoding="utf-8")
            (pkg / "Transport.kt").write_text("import android.os.Handler\n", encoding="utf-8")
            v = ca.scan_android_free(root)
            # Only the future Transport.kt is flagged; factory + legacy are excluded.
            self.assertEqual(len(v), 1)
            self.assertIn("Transport.kt", v[0][0])


class TestNative(unittest.TestCase):
    def _jni_root(self, tmp):
        return Path(tmp) / "libraries/humla/src/main/jni"

    def test_include_detection(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = self._jni_root(tmp) / "crypto"
            d.mkdir(parents=True)
            (d / "A.cpp").write_text('#include <fcntl.h>\n', encoding="utf-8")
            found = ca.scan_native(Path(tmp))
            self.assertIn(
                ("libraries/humla/src/main/jni/crypto/A.cpp", "<fcntl.h>"), found
            )

    def test_quoted_include_detected(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = self._jni_root(tmp) / "crypto"
            d.mkdir(parents=True)
            (d / "B.cpp").write_text('#include "jni.h"\n', encoding="utf-8")
            found = ca.scan_native(Path(tmp))
            self.assertIn(
                ("libraries/humla/src/main/jni/crypto/B.cpp", "<jni.h>"), found
            )

    def test_include_interior_whitespace_detected(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = self._jni_root(tmp) / "crypto"
            d.mkdir(parents=True)
            (d / "W.cpp").write_text('#include < jni.h >\n', encoding="utf-8")
            found = ca.scan_native(Path(tmp))
            self.assertIn(
                ("libraries/humla/src/main/jni/crypto/W.cpp", "<jni.h>"), found
            )

    def test_absolute_native_path_rejected(self):
        _, _, errors = ca.parse_allowlist("[native]\n/crypto/A.cpp: <jni.h>\n")
        self.assertTrue(any("absolute path" in e for e in errors))

    def test_positive_boundary_newcomer_allowed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "app/src/main"
            src.mkdir(parents=True)
            newcomer = ca.BOUNDARY_FILES[0]
            target = root / newcomer
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("@Volatile var f = 1\n", encoding="utf-8")
            path = root / "allowlist.txt"
            path.write_text("", encoding="utf-8")
            rc = ca.write_allowlist(root, path)
            self.assertEqual(rc, 0)
            self.assertIn(newcomer, path.read_text(encoding="utf-8"))

    def test_single_threaded_engine_voice_codec_and_crypto(self):
        with tempfile.TemporaryDirectory() as tmp:
            v = self._jni_root(tmp) / "voice_codec"
            v.mkdir(parents=True)
            (v / "V.cpp").write_text('#include <mutex>\n', encoding="utf-8")
            c = self._jni_root(tmp) / "crypto"
            c.mkdir(parents=True)
            (c / "C.cpp").write_text('#include <mutex>\n#include <jni.h>\n', encoding="utf-8")
            old = ca.SINGLE_THREADED_ENGINE
            try:
                ca.SINGLE_THREADED_ENGINE = True
                found = ca.scan_native(Path(tmp))
                headers = sorted(h for _, h in found)
                self.assertIn("<mutex>", headers)  # voice_codec flagged
                self.assertIn("<jni.h>", headers)  # crypto flags JNI, not mutex
                self.assertNotIn(
                    ("libraries/humla/src/main/jni/crypto/C.cpp", "<mutex>"),
                    found,  # crypto keeps its locks
                )
            finally:
                ca.SINGLE_THREADED_ENGINE = old

    def test_non_source_files_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = self._jni_root(tmp) / "crypto"
            d.mkdir(parents=True)
            (d / "notes.md").write_text('#include <jni.h>\n', encoding="utf-8")
            self.assertEqual(ca.scan_native(Path(tmp)), [])

    def test_oboe_prefix(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = self._jni_root(tmp) / "audio_engine"
            d.mkdir(parents=True)
            (d / "C.cpp").write_text('#include <oboe/Oboe.h>\n', encoding="utf-8")
            found = ca.scan_native(Path(tmp))
            self.assertIn(
                ("libraries/humla/src/main/jni/audio_engine/C.cpp", "<oboe/...>"), found
            )

    def test_oboe_does_not_hide_later_headers(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = self._jni_root(tmp) / "audio_engine"
            d.mkdir(parents=True)
            (d / "D.cpp").write_text(
                '#include <oboe/Oboe.h>\n#include <jni.h>\n', encoding="utf-8"
            )
            found = ca.scan_native(Path(tmp))
            headers = sorted(h for _, h in found)
            self.assertEqual(headers, ["<jni.h>", "<oboe/...>"])

    def test_single_threaded_engine_flag(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = self._jni_root(tmp) / "audio_engine"
            d.mkdir(parents=True)
            (d / "E.cpp").write_text('#include <mutex>\n', encoding="utf-8")
            old = ca.SINGLE_THREADED_ENGINE
            try:
                ca.SINGLE_THREADED_ENGINE = False
                self.assertEqual(ca.scan_native(Path(tmp)), [])
                ca.SINGLE_THREADED_ENGINE = True
                found = ca.scan_native(Path(tmp))
                self.assertEqual(len(found), 1)
                self.assertEqual(found[0][1], "<mutex>")
            finally:
                ca.SINGLE_THREADED_ENGINE = old

    def test_exception_stale(self):
        errs = ca.check_native([], [("a.cpp", "<jni.h>")])
        self.assertTrue(any("remove it" in e for e in errs))

    def test_unlisted_include_fails(self):
        errs = ca.check_native([("a.cpp", "<jni.h>")], [])
        self.assertEqual(len(errs), 1)

    def test_oboe_exception_roundtrip(self):
        errs = ca.check_native(
            [("a.cpp", "<oboe/...>")], [("a.cpp", "<oboe/Oboe.h>")]
        )
        self.assertEqual(errs, [])

    def test_write_allowlist_keeps_empty_native_empty(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "app/src/main"
            src.mkdir(parents=True)
            path = root / "allowlist.txt"
            # x.kt is already listed under its repo-relative path,
            # so no non-boundary newcomers exist.
            path.write_text("app/src/main/x.kt  1\n\n[native]\n", encoding="utf-8")
            (src / "x.kt").write_text("@Volatile var f = 1\n", encoding="utf-8")
            rc = ca.write_allowlist(root, path)
            self.assertEqual(rc, 0)
            _, native, errors = ca.parse_allowlist(path.read_text(encoding="utf-8"))
            self.assertEqual(errors, [])
            self.assertEqual(native, [])

    def test_write_allowlist_refuses_raised_count(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "app/src/main"
            src.mkdir(parents=True)
            path = root / "allowlist.txt"
            path.write_text("app/src/main/x.kt  1\n\n[native]\n", encoding="utf-8")
            (src / "x.kt").write_text(
                "@Volatile var a = 1\n@Volatile var b = 2\n", encoding="utf-8"
            )
            rc = ca.write_allowlist(root, path)
            self.assertEqual(rc, 1)
            # Count left untouched on refusal.
            self.assertIn("x.kt  1", path.read_text(encoding="utf-8"))

    def test_write_allowlist_refuses_non_boundary_newcomer(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            src = root / "app/src/main"
            src.mkdir(parents=True)
            path = root / "allowlist.txt"
            path.write_text("", encoding="utf-8")
            (src / "New.kt").write_text("@Volatile var f = 1\n", encoding="utf-8")
            rc = ca.write_allowlist(root, path)
            self.assertEqual(rc, 1)
            # Nothing written on refusal.
            self.assertEqual(path.read_text(encoding="utf-8"), "")


class TestRunAll(unittest.TestCase):
    def test_missing_source_root_is_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            errors, _ = ca.run_all(Path(tmp), Path(tmp) / "missing.txt")
            self.assertTrue(any("source root missing" in e for e in errors))

    def test_missing_allowlist_is_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "app/src/main").mkdir(parents=True)
            (root / "libraries/humla/src/main/java").mkdir(parents=True)
            errors, _ = ca.run_all(root, root / "no-such-file.txt")
            self.assertTrue(any("allowlist not found" in e for e in errors))

    def test_malformed_allowlist_is_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "app/src/main").mkdir(parents=True)
            (root / "libraries/humla/src/main/java").mkdir(parents=True)
            allowlist = root / "allowlist.txt"
            allowlist.write_text("bogus line here\n", encoding="utf-8")
            errors, _ = ca.run_all(root, allowlist)
            self.assertTrue(any("expected 'path count'" in e for e in errors))


if __name__ == "__main__":
    unittest.main()
