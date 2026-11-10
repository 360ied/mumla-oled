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
scripts/check_architecture.py: Tripwires for the target architecture.

Three checks (see docs/tech-debt-whales/target-architecture/enforcement.md):
  1. Concurrency tripwire: forbidden thread/lock patterns in Kotlin/Java.
  2. Android-free packages: state/client/net must not import android(x)/R.
  3. Native tripwire: forbidden I/O/thread/JNI headers in no-I/O native dirs.

The check fails if a file not on the allowlist has a match, if a listed
file has more matches than its count, or if it has fewer (the count must
be lowered in the same commit). A file with zero matches must be removed.
Only ever gets stricter.
"""

import os
import re
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT_DIR = SCRIPT_DIR.parent
ALLOWLIST_PATH = SCRIPT_DIR / "architecture-allowlist.txt"

# Step 5a flips this to True in the last engine-refactor commit.
SINGLE_THREADED_ENGINE = False

# New boundary files may only join the allowlist via these paths.
# Adding a path here is a deliberate, reviewed change (see enforcement.md).
BOUNDARY_FILES = (
    # TCP reader thread (step 3 keeps the file).
    "libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.kt",
    # TLS callbacks on the socket thread.
    "libraries/humla/src/main/java/se/lublin/humla/net/HumlaSSLSocketFactory.kt",
    # Owns its HandlerThread + platform-callback handler (step 4).
    "libraries/humla/src/main/java/se/lublin/humla/route/RouteControl.kt",
    # Owns the voice control thread + JNI handle (step 5a;
    # step 4 uses voice/VoiceControl.kt, removed in 5a).
    "libraries/humla/src/main/java/se/lublin/humla/voice/VoiceEngine.kt",
    # Uncaught-exception handler + its background thread.
    "app/src/main/java/se/lublin/mumla/util/CrashReporter.kt",
)

CONCURRENCY_PATTERNS = (
    r"@Volatile",
    r"@Synchronized",
    r"\bsynchronized\(",
    r"\bThread\s*[({]",
    r"\bHandlerThread\(",
    r"\bHandler\(",
    r"runBlocking",
    r"ReentrantLock",
    r"ReentrantReadWriteLock",
    r"Executors\.",
    r"CountDownLatch",
    r"java\.util\.concurrent\.locks",
)
RE_CONCURRENCY = re.compile("|".join(f"(?:{p})" for p in CONCURRENCY_PATTERNS))

CONCURRENCY_ROOTS = (
    "app/src/main",
    "libraries/humla/src/main/java",
)
CONCURRENCY_EXTS = (".kt", ".java")
GENERATED_MARKER = "se/lublin/humla/protobuf/"

# package -> source dir (repo-relative). Missing dirs pass trivially.
ANDROID_FREE_PACKAGES = {
    "se.lublin.humla.state": "libraries/humla/src/main/java/se/lublin/humla/state",
    "se.lublin.humla.client": "libraries/humla/src/main/java/se/lublin/humla/client",
    "se.lublin.humla.net": "libraries/humla/src/main/java/se/lublin/humla/net",
}
# The TLS socket factory uses Android trust-store APIs by design.
ANDROID_FREE_NET_EXCLUDE = {"HumlaSSLSocketFactory.kt"}
# TODO(step3): legacy net files use Handler/Looper/Log today. The Transport
# extraction makes net Android-free (except the factory above); remove this
# grandfathering then. Starting without it would fail the gate on master,
# and the script may only ever get stricter (removing exclusions is stricter).
ANDROID_FREE_NET_LEGACY_EXCLUDE = {
    "HumlaConnection.kt",
    "HumlaTCP.kt",
    "HumlaUDP.kt",
}

RE_ANDROID_IMPORT = re.compile(r"^\s*import\s+android\.")
RE_ANDROIDX_IMPORT = re.compile(r"^\s*import\s+androidx\.")
RE_R_IMPORT = re.compile(r"^\s*import\s+[\w.]+\.R\b")

NATIVE_ROOT = "libraries/humla/src/main/jni"
NATIVE_DIRS = ("audio_engine", "voice_codec", "crypto")
NATIVE_FORBIDDEN_ALL = (
    "<sys/socket.h>",
    "<netinet/in.h>",
    "<arpa/inet.h>",
    "<aaudio/AAudio.h>",
    "<oboe/",
    "<thread>",
    "<pthread.h>",
    "<semaphore.h>",
    "<fcntl.h>",
    "<unistd.h>",
    "<jni.h>",
)
NATIVE_FORBIDDEN_SINGLE_THREADED = (
    "<mutex>",
    "<shared_mutex>",
    "<condition_variable>",
    "<atomic>",
)
RE_INCLUDE = re.compile(r'^\s*#\s*include\s*[<"]([^>"]+)[>"]')


def repo_root() -> Path:
    return ROOT_DIR


def scan_concurrency(root: Path):
    """Return {repo-relative path: matching-line count} for files with >=1 hit."""
    found = {}
    for base in CONCURRENCY_ROOTS:
        base_dir = root / base
        if not base_dir.is_dir():
            continue
        for dirpath, _, filenames in os.walk(base_dir):
            for name in filenames:
                if not name.endswith(CONCURRENCY_EXTS):
                    continue
                full = Path(dirpath) / name
                rel = full.relative_to(root).as_posix()
                if GENERATED_MARKER in rel:
                    continue
                try:
                    text = full.read_text(encoding="utf-8", errors="replace")
                except OSError:
                    continue
                count = sum(1 for line in text.splitlines() if RE_CONCURRENCY.search(line))
                if count > 0:
                    found[rel] = count
    return found


def parse_allowlist(text):
    """Parse allowlist text -> (plain dict path->count, native list of (path, header))."""
    plain = {}
    native = []
    in_native = False
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            if line.strip() == "[native]":
                pass
            continue
        if line == "[native]":
            in_native = True
            continue
        if in_native:
            if ":" not in line:
                continue
            path, _, header = line.partition(":")
            native.append((path.strip(), header.strip()))
        else:
            parts = line.split()
            if len(parts) != 2:
                continue
            try:
                plain[parts[0]] = int(parts[1])
            except ValueError:
                continue
    return plain, native


def check_concurrency(actual, allowed):
    """Return list of error strings for the concurrency tripwire."""
    errors = []
    for path in sorted(actual):
        count = actual[path]
        if path not in allowed:
            if path in BOUNDARY_FILES:
                errors.append(
                    f"concurrency: {path} has {count} matching line(s) but is not on the "
                    f"allowlist; add it (boundary file) with count {count}"
                )
            else:
                errors.append(
                    f"concurrency: {path} has {count} matching line(s) but is not on the "
                    f"allowlist and is not a BOUNDARY_FILES path; remove the pattern instead"
                )
        elif count > allowed[path]:
            errors.append(
                f"concurrency: {path} has {count} matching line(s), expected "
                f"{allowed[path]}; patterns may only be removed"
            )
        elif count < allowed[path]:
            errors.append(
                f"concurrency: {path} has {count} matching line(s), expected "
                f"{allowed[path]}; lower the count in the same commit"
            )
    for path in sorted(allowed):
        if path not in actual:
            errors.append(
                f"concurrency: {path} listed with count {allowed[path]} but has no "
                f"matches; remove it from the allowlist"
            )
    return errors


def scan_android_free(root: Path):
    """Return list of (path, line-no, line) violations in Android-free packages."""
    violations = []
    for pkg, rel_dir in ANDROID_FREE_PACKAGES.items():
        d = root / rel_dir
        if not d.is_dir():
            continue
        for dirpath, _, filenames in os.walk(d):
            for name in filenames:
                if not name.endswith(CONCURRENCY_EXTS):
                    continue
                if pkg == "se.lublin.humla.net" and (
                    name in ANDROID_FREE_NET_EXCLUDE
                    or name in ANDROID_FREE_NET_LEGACY_EXCLUDE
                ):
                    continue
                full = Path(dirpath) / name
                rel = full.relative_to(root).as_posix()
                try:
                    lines = full.read_text(encoding="utf-8", errors="replace").splitlines()
                except OSError:
                    continue
                for i, line in enumerate(lines, 1):
                    if (
                        RE_ANDROID_IMPORT.search(line)
                        or RE_ANDROIDX_IMPORT.search(line)
                        or RE_R_IMPORT.search(line)
                    ):
                        violations.append((rel, i, line.strip()))
    return violations


def native_forbidden_for(dirname):
    forbidden = list(NATIVE_FORBIDDEN_ALL)
    if SINGLE_THREADED_ENGINE and dirname in ("audio_engine", "voice_codec"):
        forbidden.extend(NATIVE_FORBIDDEN_SINGLE_THREADED)
    return forbidden


def scan_native(root: Path):
    """Return list of (repo-rel path, header) for forbidden includes found."""
    found = []
    for dirname in NATIVE_DIRS:
        d = root / NATIVE_ROOT / dirname
        if not d.is_dir():
            continue
        forbidden = native_forbidden_for(dirname)
        for dirpath, _, filenames in os.walk(d):
            for name in filenames:
                full = Path(dirpath) / name
                rel = full.relative_to(root).as_posix()
                try:
                    lines = full.read_text(encoding="utf-8", errors="replace").splitlines()
                except OSError:
                    continue
                for line in lines:
                    m = RE_INCLUDE.search(line)
                    if not m:
                        continue
                    inc = m.group(1)
                    token = f"<{inc}>" if line.strip().endswith(">") or "<" in line else inc
                    for header in forbidden:
                        if header == "<oboe/":
                            if inc.startswith("oboe/"):
                                found.append((rel, "<oboe/...>"))
                                break
                        elif header in line:
                            found.append((rel, header))
                            break
                    _ = token
    return found


def check_native(found, allowed_native):
    errors = []
    allowed_set = set(allowed_native)
    for path, header in found:
        # Normalize oboe bucket: allowlist stores the concrete include.
        if header == "<oboe/...>":
            match = any(p == path and h.startswith("<oboe/") for p, h in allowed_set)
            if not match:
                errors.append(f"native: {path} includes oboe (not in allowlist exceptions)")
            continue
        if (path, header) not in allowed_set:
            errors.append(f"native: {path} includes {header} (not in allowlist exceptions)")
    found_set = set(found)
    # Oboe bucket entries compare loosely; skip exact stale-check for them.
    for path, header in allowed_native:
        if header.startswith("<oboe/"):
            if not any(p == path and h == "<oboe/...>" for p, h in found_set):
                errors.append(
                    f"native: exception {path}: {header} no longer found; remove it"
                )
            continue
        if (path, header) not in found_set:
            errors.append(
                f"native: exception {path}: {header} no longer found; remove it"
            )
    return errors


def load_allowlist(path=ALLOWLIST_PATH):
    if not path.exists():
        return None, None, f"allowlist not found: {path}"
    text = path.read_text(encoding="utf-8")
    plain, native = parse_allowlist(text)
    return plain, native, None


def run_all(root=ROOT_DIR, allowlist_path=ALLOWLIST_PATH):
    errors = []
    plain, native_allowed, err = load_allowlist(allowlist_path)
    if err:
        return [err]
    actual = scan_concurrency(root)
    errors.extend(check_concurrency(actual, plain))
    for rel, lineno, line in scan_android_free(root):
        errors.append(f"android-free: {rel}:{lineno}: {line}")
    errors.extend(check_native(scan_native(root), native_allowed))
    return errors


DEFAULT_NATIVE_EXCEPTIONS = [
    ("libraries/humla/src/main/jni/crypto/CryptStateOCB2.cpp", "<fcntl.h>"),
    ("libraries/humla/src/main/jni/crypto/CryptStateOCB2.cpp", "<unistd.h>"),
    ("libraries/humla/src/main/jni/audio_engine/NativeAudioInputEngineJni.cpp", "<jni.h>"),
    ("libraries/humla/src/main/jni/audio_engine/NativeAudioOutputEngineJni.cpp", "<jni.h>"),
    ("libraries/humla/src/main/jni/audio_engine/NativeCrashTestJni.cpp", "<jni.h>"),
    ("libraries/humla/src/main/jni/crypto/NativeCryptStateJni.cpp", "<jni.h>"),
]


def write_allowlist(root=ROOT_DIR, path=ALLOWLIST_PATH):
    """Regenerate the plain section from the current scan, keep [native]."""
    if path.exists():
        _, native_allowed, _ = load_allowlist(path)
    else:
        native_allowed = None
    if not native_allowed:
        native_allowed = list(DEFAULT_NATIVE_EXCEPTIONS)
    actual = scan_concurrency(root)
    lines = [
        "# Architecture allowlist: path + matching-line count (grep -cE).",
        "# Counts may only fall; a file with zero matches must be removed.",
        "# Boundary files may join only via BOUNDARY_FILES in check_architecture.py.",
        "# [native] exceptions follow the same ratchet (removals only).",
    ]
    for p in sorted(actual):
        lines.append(f"{p}  {actual[p]}")
    lines.append("")
    lines.append("[native]")
    for p, h in native_allowed:
        lines.append(f"{p}: {h}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return actual


def main(argv):
    if "--write-allowlist" in argv:
        actual = write_allowlist()
        print(f"Wrote {ALLOWLIST_PATH} ({len(actual)} files).")
        return 0
    errors = run_all()
    if errors:
        print("Architecture check FAILED:", file=sys.stderr)
        for e in errors:
            print(f"  {e}", file=sys.stderr)
        print(
            "\nLower counts in the same commit; never raise them. "
            "See docs/tech-debt-whales/target-architecture/enforcement.md.",
            file=sys.stderr,
        )
        return 1
    files = len(scan_concurrency(ROOT_DIR))
    print(f"Architecture check passed ({files} files on allowlist).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
