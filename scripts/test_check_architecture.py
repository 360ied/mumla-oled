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
        self.assertIsNone(ca.RE_CONCURRENCY.search("ModelHandler.messageUserState"))
        self.assertIsNone(ca.RE_CONCURRENCY.search("mainHandler.post(r)"))
        self.assertIsNotNone(ca.RE_CONCURRENCY.search("Thread { }"))
        self.assertIsNotNone(ca.RE_CONCURRENCY.search("Handler(foo)"))
        self.assertIsNotNone(
            ca.RE_CONCURRENCY.search("Handler.createAsync(Looper.getMainLooper())")
        )
        self.assertIsNotNone(ca.RE_CONCURRENCY.search("@Volatile"))
        self.assertIsNotNone(ca.RE_CONCURRENCY.search("runBlocking { }"))

    def test_whitespace_variants_match(self):
        self.assertIsNotNone(ca.RE_CONCURRENCY.search("synchronized (lock)"))
        self.assertIsNotNone(ca.RE_CONCURRENCY.search("Handler (foo)"))
        self.assertIsNotNone(ca.RE_CONCURRENCY.search("HandlerThread (x)"))

    def test_new_thread_factories_match(self):
        for snippet in (
            "newSingleThreadContext(\"bg\")",
            "Timer()",
            "Timer(\"t\")",
            "GlobalScope.launch { }",
            "HandlerCompat.createAsync(looper)",
            "ThreadPoolExecutor(1, 2, 0L, TimeUnit.SECONDS, q)",
            "ScheduledThreadPoolExecutor(1)",
            "ForkJoinPool.commonPool()",
            "ScheduledExecutorService x",
            "dispatcher.asExecutor()",
            "AlarmManager x",
            "WorkManager.getInstance()",
        ):
            self.assertIsNotNone(ca.RE_CONCURRENCY.search(snippet), snippet)
        self.assertIsNone(ca.RE_CONCURRENCY.search("TimerTask()"))
        self.assertIsNone(ca.RE_CONCURRENCY.search("TimeUnit.SECONDS"))

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

    def test_detects_fqn_and_reflection(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pkg = root / "libraries/humla/src/main/java/se/lublin/humla/state"
            pkg.mkdir(parents=True)
            (pkg / "Fqn.kt").write_text("val x = android.util.Log.w(t, m)\n", encoding="utf-8")
            (pkg / "Graphics.kt").write_text(
                "val c = android.graphics.Color.RED\n", encoding="utf-8"
            )
            (pkg / "Bluetooth.kt").write_text(
                "val a = android.bluetooth.BluetoothAdapter.getDefaultAdapter()\n",
                encoding="utf-8",
            )
            (pkg / "Refl.kt").write_text('Class.forName("android.os.Foo")\n', encoding="utf-8")
            (pkg / "Lit.kt").write_text('val s = "android.os.Foo"\n', encoding="utf-8")
            (pkg / "Ok.kt").write_text("// no Android imports\n", encoding="utf-8")
            v = ca.scan_android_free(root)
            self.assertEqual(len(v), 5)
            flagged = sorted(path for path, _, _ in v)
            self.assertTrue(any("Graphics.kt" in p for p in flagged))
            self.assertTrue(any("Bluetooth.kt" in p for p in flagged))

    def test_net_excludes_tls_factory_only(self):
        # Step 3 removed the legacy grandfathering (HumlaConnection deleted;
        # HumlaTCP and HumlaUDP are android-free). Only the TLS factory stays excluded.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            pkg = root / "libraries/humla/src/main/java/se/lublin/humla/net"
            pkg.mkdir(parents=True)
            (pkg / "HumlaSSLSocketFactory.kt").write_text(
                "import android.net.SSL\n", encoding="utf-8"
            )
            for name in ("HumlaTCP.kt", "HumlaUDP.kt", "Transport.kt", "TransportImpl.kt"):
                (pkg / name).write_text("import android.os.Handler\n", encoding="utf-8")
            v = ca.scan_android_free(root)
            self.assertEqual(len(v), 4)
            flagged = sorted(path for path, _, _ in v)
            self.assertIn("libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.kt", flagged)
            self.assertIn("libraries/humla/src/main/java/se/lublin/humla/net/HumlaUDP.kt", flagged)


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

    def test_body_evasion_detected(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = self._jni_root(tmp) / "audio_engine"
            d.mkdir(parents=True)
            (d / "E.cpp").write_text('void* h = dlopen("libaaudio.so", 0);\n', encoding="utf-8")
            hits = ca.scan_native_body(Path(tmp))
            self.assertEqual(len(hits), 1)
            self.assertIn("E.cpp", hits[0][0])

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


class TestVoiceLifecycle(unittest.TestCase):
    # Step 5b splits the pin: asserts live in the JNI lifecycle file, both
    # guards live in the host-compiled AAudio callback bodies.
    LIFECYCLE = (
        "#include <cassert>\n"
        "void nativeDestroy() {\n"
        "  assert(!ctx->streams.renderActive.load(std::memory_order_acquire));\n"
        "  assert(!ctx->streams.captureActive.load(std::memory_order_acquire));\n"
        "}\n"
        "void nativeClear() {\n"
        "  assert(!ctx->streams.renderActive.load(std::memory_order_acquire));\n"
        "  assert(!ctx->streams.captureActive.load(std::memory_order_acquire));\n"
        "}\n"
    )
    CALLBACKS = (
        "#include \"VoiceStreamContext.h\"\n"
        "bool renderStreamFrames() { RenderGuard guard(&ctx->renderActive);\n"
        "  return true; }\n"
        "void captureStreamFrames() { CaptureGuard guard(&ctx->captureActive); }\n"
    )

    def write_lifecycle(self, root, lifecycle=None, callbacks=None):
        d = root / "libraries/humla/src/main/jni/voice_io"
        d.mkdir(parents=True)
        # Keep in sync with the gate: the pins follow the files, not the
        # other way round (renamed NativeVoiceIoJni ->
        # NativeVoiceEngineJni in 5a.3b).
        if lifecycle is not None:
            (d / Path(ca.VOICE_IO_LIFECYCLE_FILE).name).write_text(
                lifecycle, encoding="utf-8"
            )
        if callbacks is not None:
            (d / Path(ca.VOICE_IO_CALLBACKS_FILE).name).write_text(
                callbacks, encoding="utf-8"
            )

    def test_absent_voice_io_passes_trivially(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(ca.check_voice_lifecycle(Path(tmp)), [])

    def test_healthy_files_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_lifecycle(root, self.LIFECYCLE, self.CALLBACKS)
            self.assertEqual(ca.check_voice_lifecycle(root), [])

    def test_missing_assert_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            text = self.LIFECYCLE.replace(
                "void nativeClear() {\n"
                "  assert(!ctx->streams.renderActive.load(std::memory_order_acquire));\n"
                "  assert(!ctx->streams.captureActive.load(std::memory_order_acquire));\n"
                "}\n",
                "void nativeClear() {\n"
                "  assert(!ctx->streams.captureActive.load(std::memory_order_acquire));\n"
                "}\n",
            )
            self.write_lifecycle(root, text, self.CALLBACKS)
            errors = ca.check_voice_lifecycle(root)
            self.assertEqual(len(errors), 1)
            self.assertTrue(any("voice-lifecycle" in e for e in errors))

    def test_missing_capture_assert_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            text = self.LIFECYCLE.replace(
                "void nativeClear() {\n"
                "  assert(!ctx->streams.renderActive.load(std::memory_order_acquire));\n"
                "  assert(!ctx->streams.captureActive.load(std::memory_order_acquire));\n"
                "}\n",
                "void nativeClear() {\n"
                "  assert(!ctx->streams.renderActive.load(std::memory_order_acquire));\n"
                "}\n",
            )
            self.write_lifecycle(root, text, self.CALLBACKS)
            errors = ca.check_voice_lifecycle(root)
            self.assertEqual(len(errors), 1)
            self.assertTrue(any("captureActive" in e for e in errors))

    def test_missing_guard_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            text = self.CALLBACKS.replace(
                "RenderGuard guard(&ctx->renderActive);", "/* guard removed */"
            )
            self.write_lifecycle(root, self.LIFECYCLE, text)
            errors = ca.check_voice_lifecycle(root)
            self.assertEqual(len(errors), 1)
            self.assertTrue(any("RenderGuard" in e for e in errors))

    def test_missing_capture_guard_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            text = self.CALLBACKS.replace(
                "CaptureGuard guard(&ctx->captureActive);", "/* guard removed */"
            )
            self.write_lifecycle(root, self.LIFECYCLE, text)
            errors = ca.check_voice_lifecycle(root)
            self.assertEqual(len(errors), 1)
            self.assertTrue(any("CaptureGuard" in e for e in errors))

    def test_missing_callbacks_file_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_lifecycle(root, self.LIFECYCLE)
            errors = ca.check_voice_lifecycle(root)
            self.assertEqual(len(errors), 3)
            self.assertTrue(any("VoiceStreamCallbacks.cpp" in e for e in errors))

    def test_missing_lifecycle_file_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write_lifecycle(root, callbacks=self.CALLBACKS)
            errors = ca.check_voice_lifecycle(root)
            self.assertEqual(len(errors), 3)
            self.assertTrue(any("NativeVoiceEngineJni.cpp" in e for e in errors))


class TestLocalFactsWriter(unittest.TestCase):
    """Step 6: one writer for device-local facts."""

    def write(self, root, rel, text):
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def test_clean_tree_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(root, ca.LOCAL_FACTS_STORE_FILE, "dao.setLocalMute(1, 2, true)\n")
            self.write(
                root,
                ca.LOCAL_FACTS_DAO_DIR + "MumlaRoomDatabase.kt",
                "localMuteDao().getUsers(1)\n",
            )
            self.write(
                root,
                "app/src/main/java/se/lublin/mumla/service/MumlaService.kt",
                "settings.isMuted\n",
            )
            self.assertEqual(ca.check_local_facts_writers(root), [])

    def test_adapter_write_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(
                root,
                "app/src/main/java/se/lublin/mumla/channel/ChannelListAdapter.kt",
                "mDatabase.addLocalMutedUser(1, 2)\n",
            )
            errors = ca.check_local_facts_writers(root)
            self.assertEqual(len(errors), 1)
            self.assertIn("ChannelListAdapter.kt", errors[0])

    def test_service_self_mute_write_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(
                root,
                "app/src/main/java/se/lublin/mumla/service/MumlaService.kt",
                "settings.setMutedAndDeafened(muted, deafened)\n",
            )
            errors = ca.check_local_facts_writers(root)
            self.assertEqual(len(errors), 1)
            self.assertIn("MumlaService.kt", errors[0])

    def test_declarations_do_not_match(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(
                root,
                "app/src/main/java/se/lublin/mumla/Settings.kt",
                "fun setMutedAndDeafened(muted: Boolean, deafened: Boolean) {}\n",
            )
            self.assertEqual(ca.check_local_facts_writers(root), [])

    def test_dao_wrapper_call_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(
                root,
                "app/src/main/java/se/lublin/mumla/channel/ChannelListAdapter.kt",
                "db.insertLocalMutedUser(1, 2)\n",
            )
            errors = ca.check_local_facts_writers(root)
            self.assertEqual(len(errors), 1)
            self.assertIn("ChannelListAdapter.kt", errors[0])

    def test_database_suspend_writer_call_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(
                root,
                "app/src/main/java/se/lublin/mumla/service/Sneaky.kt",
                "container.database.setLocalMute(1, 2, true)\n",
            )
            errors = ca.check_local_facts_writers(root)
            self.assertEqual(len(errors), 1)
            self.assertIn("Sneaky.kt", errors[0])

    def test_dao_read_wrappers_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(
                root,
                "app/src/main/java/se/lublin/mumla/service/Sneaky.kt",
                "database.localMutedUsers(1)\n",
            )
            errors = ca.check_local_facts_writers(root)
            self.assertEqual(len(errors), 1)
            self.assertIn("Sneaky.kt", errors[0])

    def test_sanctioned_service_funnel_still_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(
                root,
                "app/src/main/java/se/lublin/mumla/channel/UserMenu.kt",
                "mService.setLocalMute(mUser.session, !mUser.isLocalMuted)\n"
                "mService.setLocalIgnore(mUser.session, !mUser.isLocalIgnored)\n",
            )
            self.assertEqual(ca.check_local_facts_writers(root), [])


class TestFeatureIsolation(unittest.TestCase):
    """Step 6: features share flows, never each other."""

    def write(self, root, name, text):
        path = root / ca.FEATURE_DIR / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def test_no_features_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(ca.check_feature_isolation(Path(tmp)), [])

    def test_shared_helper_is_not_a_feature(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(root, "ConnectionLines.kt", "class ConnectionLines {}\n")
            self.write(
                root, "ChatLogFeature.kt",
                "class ChatLogFeature { fun start(scope: CoroutineScope) {} }\n"
                "// ConnectionLines is a shared helper\n",
            )
            self.assertEqual(ca.check_feature_isolation(root), [])

    def test_cross_feature_reference_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(
                root, "ChatLogFeature.kt",
                "class ChatLogFeature { fun start(scope: CoroutineScope) {} }\n",
            )
            self.write(
                root, "TtsFeature.kt",
                "class TtsFeature { fun start(scope: CoroutineScope) { ChatLogFeature() } }\n",
            )
            errors = ca.check_feature_isolation(root)
            self.assertEqual(len(errors), 1)
            self.assertIn("TtsFeature.kt", errors[0])

    def test_start_less_helper_cannot_launder_a_cross_feature_call(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(
                root, "ChatLogFeature.kt",
                "class ChatLogFeature { fun start(scope: CoroutineScope) {} }\n",
            )
            self.write(
                root, "Helper.kt",
                "object Helper { fun forward() = ChatLogFeature() }\n",
            )
            errors = ca.check_feature_isolation(root)
            self.assertEqual(len(errors), 1)
            self.assertIn("Helper.kt", errors[0])

    def test_mismatched_start_signature_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(
                root, "SneakyFeature.kt",
                "class SneakyFeature { fun start() {} }\n",
            )
            errors = ca.check_feature_set_closed(root)
            self.assertEqual(len(errors), 1)
            self.assertIn("SneakyFeature.kt", errors[0])

    def test_suspend_start_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(
                root, "SneakyFeature.kt",
                "class SneakyFeature { suspend fun start(scope: CoroutineScope) {} }\n",
            )
            errors = ca.check_feature_set_closed(root)
            self.assertEqual(len(errors), 1)


class TestServiceBinding(unittest.TestCase):
    def write(self, root: Path, name: str, text: str):
        path = root / "app/src/main/java/se/lublin/mumla" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")

    def test_detects_bind_and_service_connection(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(
                root, "Binder.kt",
                "val bound = bindService(intent, connection, 0)\n"
                "val connection = object : ServiceConnection {}\n",
            )
            hits = ca.scan_service_binding(root)
            self.assertEqual(2, len(hits))
            self.assertTrue(hits[0][0].endswith("Binder.kt"))

    def test_detects_every_bind_variant_and_a_split_call(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(
                root, "Binder.kt",
                "bindIsolatedService(intent, 0, \"id\", executor, conn)\n"
                "bindServiceAsUser(intent, conn, 0, user)\n"
                "val bound = bindService\n"
                "    (intent, conn, 0)\n",
            )
            hits = ca.scan_service_binding(root)
            self.assertEqual([1, 2, 3], [hit[1] for hit in hits])

    def test_comments_and_strings_count(self):
        # Fail closed: both halves of the pattern treat a mention alike.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(
                root, "Screen.kt",
                "// bindService is not used here\n"
                "val note = \"no ServiceConnection\"\n",
            )
            self.assertEqual(2, len(ca.scan_service_binding(root)))

    def test_clean_tree_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.write(
                root, "Screen.kt",
                "val state = container.runtime.client.state\n"
                "override fun onBind(intent: Intent?): IBinder? = null\n"
                "context.startForegroundService(intent)\n",
            )
            self.assertEqual([], ca.scan_service_binding(root))

    def test_missing_root_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual([], ca.scan_service_binding(Path(tmp)))

    def test_run_all_reports_a_bind(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "libraries/humla/src/main/java").mkdir(parents=True)
            self.write(root, "Binder.kt", "bindService(intent, conn, 0)\n")
            allowlist = root / "allowlist.txt"
            allowlist.write_text("", encoding="utf-8")
            errors, _ = ca.run_all(root, allowlist)
            self.assertTrue(any("service-binding" in e for e in errors))


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
