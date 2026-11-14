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
scripts/check_architecture.py: Tripwires for the target architecture.

Three checks (see docs/target-architecture/enforcement.md):
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

# True once the step 5a engine refactor landed: the audio engines and the
# packet codec are single-threaded by contract and hold no locks.
SINGLE_THREADED_ENGINE = True

# New boundary files may only join the allowlist via these paths.
# Adding a path here is a deliberate, reviewed change (see enforcement.md).
BOUNDARY_FILES = (
    # TCP reader thread (step 3 keeps the file).
    "libraries/humla/src/main/java/se/lublin/humla/net/HumlaTCP.kt",
    # Transport reader thread (step 3 extracts the Transport).
    "libraries/humla/src/main/java/se/lublin/humla/net/TransportImpl.kt",
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
    r"\bsynchronized\s*\(",
    r"\bThread\s*[({]",
    r"\bHandlerThread\s*\(",
    r"\bHandler\s*\(",
    r"\brunBlocking\b",
    r"\bReentrantLock\b",
    r"\bReentrantReadWriteLock\b",
    r"\bExecutors\.",
    r"\bCountDownLatch\b",
    r"\bjava\.util\.concurrent\.locks\b",
    # Lowercase thread creation (kotlin.concurrent.thread { }): the bare word
    # is too noisy in prose ("render thread (" appears in comments, and
    # comments count by design), so only the import-qualified form is
    # enforced. Deliberately NOT Thread.* statics (sleep, currentThread,
    # getDefaultUncaughtExceptionHandler, ...): those are not thread creation.
    r"\bkotlin\.concurrent\.thread\b",
)
RE_CONCURRENCY = re.compile("|".join(f"(?:{p})" for p in CONCURRENCY_PATTERNS))

CONCURRENCY_ROOTS = (
    "app/src/main",
    "libraries/humla/src/main/java",
)
CONCURRENCY_EXTS = (".kt", ".java")
GENERATED_MARKER = "se/lublin/humla/protobuf/"

# package -> source dir (repo-relative). Missing package dirs pass trivially:
# Missing package dirs pass trivially; voice_codec arrives in step 5a.
ANDROID_FREE_PACKAGES = {
    "se.lublin.humla.state": "libraries/humla/src/main/java/se/lublin/humla/state",
    "se.lublin.humla.client": "libraries/humla/src/main/java/se/lublin/humla/client",
    "se.lublin.humla.net": "libraries/humla/src/main/java/se/lublin/humla/net",
}
# The TLS socket factory uses Android trust-store APIs by design.
ANDROID_FREE_NET_EXCLUDE = {"HumlaSSLSocketFactory.kt"}
# Step 3 removed the legacy grandfathering (HumlaConnection deleted;
# HumlaTCP and HumlaUDP are android-free via Executor/java-logging).

RE_ANDROID_IMPORT = re.compile(r"^\s*import\s+android\.")
RE_ANDROIDX_IMPORT = re.compile(r"^\s*import\s+androidx\.")
RE_R_IMPORT = re.compile(r"^\s*import\s+(?:static\s+)?[\w.]+\.R\b")

NATIVE_ROOT = "libraries/humla/src/main/jni"
NATIVE_DIRS = ("audio_engine", "voice_codec", "crypto")
NATIVE_EXTS = (".c", ".h", ".cpp", ".hpp", ".cc", ".cxx")
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
    """Parse allowlist text -> (plain dict, native list, error list).

    Malformed lines are errors (fail closed), not silent skips: a typo'd
    entry must surface as a parse error instead of a confusing
    "not on the allowlist" failure downstream.
    """
    plain = {}
    native = []
    errors = []
    in_native = False
    for lineno, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line == "[native]":
            in_native = True
            continue
        if in_native:
            if ":" not in line:
                errors.append(f"allowlist:{lineno}: native entry without ':' (expected 'path: <header>')")
                continue
            path, _, header = line.partition(":")
            path, header = path.strip(), header.strip()
            if not path or not header:
                errors.append(f"allowlist:{lineno}: native entry with empty path or header")
                continue
            if path.startswith("/"):
                errors.append(f"allowlist:{lineno}: absolute path {path} (use repo-relative)")
                continue
            if (path, header) in native:
                errors.append(f"allowlist:{lineno}: duplicate native entry for {path}: {header}")
                continue
            native.append((path, header))
        else:
            parts = line.split()
            if len(parts) != 2:
                errors.append(f"allowlist:{lineno}: expected 'path count', got {len(parts)} field(s)")
                continue
            path, count_s = parts
            if path in plain:
                errors.append(f"allowlist:{lineno}: duplicate entry for {path}")
                continue
            try:
                count = int(count_s)
            except ValueError:
                errors.append(f"allowlist:{lineno}: count is not an integer: {count_s!r}")
                continue
            if count < 0:
                errors.append(f"allowlist:{lineno}: negative count for {path}")
                continue
            if count == 0:
                errors.append(f"allowlist:{lineno}: zero count for {path} (remove the entry instead)")
                continue
            if path.startswith("/"):
                errors.append(f"allowlist:{lineno}: absolute path {path} (use repo-relative)")
                continue
            plain[path] = count
    return plain, native, errors


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
                if pkg == "se.lublin.humla.net" and name in ANDROID_FREE_NET_EXCLUDE:
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
                if not name.endswith(NATIVE_EXTS):
                    continue
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
                    inc = m.group(1).strip()
                    if inc.startswith("oboe/"):
                        # Report and keep scanning: a file can mix oboe with
                        # other forbidden headers, and each must be listed.
                        found.append((rel, "<oboe/...>"))
                        continue
                    for header in forbidden:
                        if header == "<oboe/":
                            continue
                        if inc == header[1:-1]:
                            found.append((rel, header))
                            break
    return found


def check_native(found, allowed_native):
    errors = []
    allowed_set = set(allowed_native)
    for path, header in found:
        # Oboe bucket entries compare loosely; the allowlist stores the concrete include.
        if header == "<oboe/...>":
            if not any(p == path and h.startswith("<oboe/") for p, h in allowed_set):
                errors.append(f"native: {path} includes oboe (not in allowlist exceptions)")
            continue
        if (path, header) not in allowed_set:
            errors.append(f"native: {path} includes {header} (not in allowlist exceptions)")
    found_set = set(found)
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
    plain, native, errors = parse_allowlist(text)
    if errors:
        return None, None, "; ".join(errors)
    return plain, native, None


def run_all(root=ROOT_DIR, allowlist_path=ALLOWLIST_PATH):
    errors = []
    for base in CONCURRENCY_ROOTS:
        if not (root / base).is_dir():
            errors.append(f"source root missing: {base} (run from the repository root)")
    plain, native_allowed, err = load_allowlist(allowlist_path)
    if err:
        return errors + [err], {}
    actual = scan_concurrency(root)
    errors.extend(check_concurrency(actual, plain))
    for rel, lineno, line in scan_android_free(root):
        errors.append(f"android-free: {rel}:{lineno}: {line}")
    errors.extend(check_native(scan_native(root), native_allowed))
    return errors, actual


DEFAULT_NATIVE_EXCEPTIONS = [
    ("libraries/humla/src/main/jni/crypto/CryptStateOCB2.cpp", "<fcntl.h>"),
    ("libraries/humla/src/main/jni/crypto/CryptStateOCB2.cpp", "<unistd.h>"),
    ("libraries/humla/src/main/jni/audio_engine/NativeAudioInputEngineJni.cpp", "<jni.h>"),
    ("libraries/humla/src/main/jni/audio_engine/NativeAudioOutputEngineJni.cpp", "<jni.h>"),
    # Step 5a.1 moved the engine lock into the interim JNI seam; both entries
    # go when the bridge files do (5a.4). Keep this list identical to the
    # committed allowlist's [native] section: it is the fallback used when the
    # file is missing.
    ("libraries/humla/src/main/jni/audio_engine/NativeAudioInputEngineJni.cpp", "<mutex>"),
    ("libraries/humla/src/main/jni/audio_engine/NativeAudioOutputEngineJni.cpp", "<mutex>"),
    ("libraries/humla/src/main/jni/audio_engine/NativeCrashTestJni.cpp", "<jni.h>"),
    ("libraries/humla/src/main/jni/crypto/NativeCryptStateJni.cpp", "<jni.h>"),
]


def write_allowlist(root=ROOT_DIR, path=ALLOWLIST_PATH):
    """Regenerate the plain section from the current scan, keep [native].

    Returns 0 on success, 1 when new violations appear outside
    BOUNDARY_FILES: those must be removed from the code (or the path added
    to BOUNDARY_FILES in a reviewed change), never auto-legalized here.
    """
    if path.exists():
        existing, native_allowed, err = load_allowlist(path)
        if err:
            print(f"cannot update allowlist: {err}", file=sys.stderr)
            return 1
    else:
        existing, native_allowed = {}, None
    if native_allowed is None:
        native_allowed = list(DEFAULT_NATIVE_EXCEPTIONS)
    actual = scan_concurrency(root)
    newcomers = sorted(set(actual) - set(existing))
    illegal = [p for p in newcomers if p not in BOUNDARY_FILES]
    raised = sorted(p for p in set(actual) & set(existing) if actual[p] > existing[p])
    if illegal or raised:
        if illegal:
            print("refusing to auto-legalize new violations outside BOUNDARY_FILES:", file=sys.stderr)
            for p in illegal:
                print(f"  {p}  {actual[p]}", file=sys.stderr)
            print("Remove the patterns, or add the path to BOUNDARY_FILES in a reviewed change.", file=sys.stderr)
        if raised:
            print("refusing to raise counts for already-listed files (counts may only fall):", file=sys.stderr)
            for p in raised:
                print(f"  {p}  {existing[p]} -> {actual[p]}", file=sys.stderr)
            print("Remove patterns until the count falls, then lower it in the same commit.", file=sys.stderr)
        return 1
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
    return 0


def main(argv):
    if "--write-allowlist" in argv:
        rc = write_allowlist()
        if rc == 0:
            print(f"Wrote {ALLOWLIST_PATH}.")
        return rc
    errors, actual = run_all()
    if errors:
        print("Architecture check FAILED:", file=sys.stderr)
        for e in errors:
            print(f"  {e}", file=sys.stderr)
        print(
            "\nLower counts in the same commit; never raise them. "
            "See docs/target-architecture/enforcement.md.",
            file=sys.stderr,
        )
        return 1
    print(f"Architecture check passed ({len(actual)} files on allowlist).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
