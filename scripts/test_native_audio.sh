#!/usr/bin/env bash
#
# scripts/test_native_audio.sh: Configure, compile, and execute the host C++
# audio engine and crypto test suites (plus the host JNI shared library) via
# CMake and Ninja.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JNI_DIR="$ROOT_DIR/libraries/humla/src/main/jni"
BUILD_DIR="$ROOT_DIR/build/test-native"

# Locate cmake, ninja, and ctest (prefer PATH, fall back to the pinned SDK cmake under ANDROID_HOME).
CMAKE_BIN="$(command -v cmake || true)"
NINJA_BIN="$(command -v ninja || true)"
CTEST_BIN="$(command -v ctest || true)"
if [[ -z "$CMAKE_BIN" || -z "$NINJA_BIN" || -z "$CTEST_BIN" ]] && [[ -n "${ANDROID_HOME:-}" ]]; then
    # Keep in sync: build.gradle cmake version, flake.nix SDK cmake PATH.
    SDK_CMAKE_BIN_DIR="$ANDROID_HOME/cmake/4.1.2/bin"
    if [[ -d "$SDK_CMAKE_BIN_DIR" ]]; then
        [[ -z "$CMAKE_BIN" && -x "$SDK_CMAKE_BIN_DIR/cmake" ]] && CMAKE_BIN="$SDK_CMAKE_BIN_DIR/cmake"
        [[ -z "$NINJA_BIN" && -x "$SDK_CMAKE_BIN_DIR/ninja" ]] && NINJA_BIN="$SDK_CMAKE_BIN_DIR/ninja"
        [[ -z "$CTEST_BIN" && -x "$SDK_CMAKE_BIN_DIR/ctest" ]] && CTEST_BIN="$SDK_CMAKE_BIN_DIR/ctest"
    fi
fi

if [[ -z "$CMAKE_BIN" || -z "$NINJA_BIN" || -z "$CTEST_BIN" ]]; then
    echo "test_native_audio.sh: error: cmake, ninja, and ctest are required (enter 'nix develop' or install cmake/ninja; ctest ships with cmake)." >&2
    exit 1
fi

CC_BIN="${CC:-gcc}"
CXX_BIN="${CXX:-g++}"

# Configure when build.ninja is missing or the toolchain changed. The
# fingerprint covers compiler paths and versions plus cmake/ninja versions;
# anything else fails loudly at the next ninja/cmake invocation rather than
# misbuilding.
TOOLCHAIN_STAMP="$BUILD_DIR/.toolchain_fingerprint"
TOOLCHAIN_CANDIDATE="$BUILD_DIR/.toolchain_fingerprint.new"
mkdir -p "$BUILD_DIR"
{ printf '%s\n%s\n' "$CC_BIN" "$CXX_BIN"; "$CMAKE_BIN" --version; "$NINJA_BIN" --version; "$CC_BIN" --version; "$CXX_BIN" --version; } > "$TOOLCHAIN_CANDIDATE" 2>/dev/null || echo "unknown" > "$TOOLCHAIN_CANDIDATE"
if [[ ! -f "$BUILD_DIR/build.ninja" ]] || ! cmp -s "$TOOLCHAIN_STAMP" "$TOOLCHAIN_CANDIDATE" 2>/dev/null; then
    rm -rf "$BUILD_DIR/CMakeCache.txt" "$BUILD_DIR/CMakeFiles"
    "$CMAKE_BIN" -Wno-deprecated \
        -S "$JNI_DIR" \
        -B "$BUILD_DIR" \
        -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$NINJA_BIN" \
        -DCMAKE_C_COMPILER="$CC_BIN" \
        -DCMAKE_CXX_COMPILER="$CXX_BIN"
    mv "$TOOLCHAIN_CANDIDATE" "$TOOLCHAIN_STAMP"
else
    rm -f "$TOOLCHAIN_CANDIDATE"
fi

"$NINJA_BIN" -C "$BUILD_DIR"

"$CMAKE_BIN" -E chdir "$BUILD_DIR" "$CTEST_BIN" --output-on-failure
