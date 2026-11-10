#!/usr/bin/env python3
# Copyright (C) 2026 Mumla OLED Contributors
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
# along with this program.  If not, see <https://www.gnu.org/licenses/>.
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
        plain, native = ca.parse_allowlist(text)
        self.assertEqual(plain, {"a/b.kt": 4, "c/d.java": 1})
        self.assertEqual(
            native,
            [("libraries/humla/src/main/jni/crypto/CryptStateOCB2.cpp", "<fcntl.h>")],
        )

    def test_malformed_lines_skipped(self):
        plain, native = ca.parse_allowlist("justapath\n\n[native]\nno-colon\n")
        self.assertEqual(plain, {})
        self.assertEqual(native, [])


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
        # currentThread( must not match the Thread pattern; the
        # createAudioHandler( must not match the Handler pattern.
        self.assertIsNone(ca.RE_CONCURRENCY.search("currentThread()"))
        self.assertIsNone(ca.RE_CONCURRENCY.search("createAudioHandler()"))
        self.assertIsNotNone(ca.RE_CONCURRENCY.search("Thread { }"))
        self.assertIsNotNone(ca.RE_CONCURRENCY.search("Handler(foo)"))
        self.assertIsNotNone(ca.RE_CONCURRENCY.search("@Volatile"))
        self.assertIsNotNone(ca.RE_CONCURRENCY.search("runBlocking { }"))


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

    def test_missing_dirs_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(ca.scan_android_free(Path(tmp)), [])

    def test_net_excludes_tls_factory(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pkg = root / "libraries/humla/src/main/java/se/lublin/humla/net"
            pkg.mkdir(parents=True)
            (pkg / "HumlaSSLSocketFactory.kt").write_text(
                "import android.net.SSL\n", encoding="utf-8"
            )
            self.assertEqual(ca.scan_android_free(root), [])


class TestNative(unittest.TestCase):
    def test_include_detection(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            d = root / "libraries/humla/src/main/jni/crypto"
            d.mkdir(parents=True)
            (d / "A.cpp").write_text('#include <fcntl.h>\n', encoding="utf-8")
            found = ca.scan_native(root)
            self.assertIn(
                ("libraries/humla/src/main/jni/crypto/A.cpp", "<fcntl.h>"), found
            )

    def test_exception_stale(self):
        errs = ca.check_native([], [("a.cpp", "<jni.h>")])
        self.assertTrue(any("remove it" in e for e in errs))

    def test_unlisted_include_fails(self):
        errs = ca.check_native([("a.cpp", "<jni.h>")], [])
        self.assertEqual(len(errs), 1)


if __name__ == "__main__":
    unittest.main()
