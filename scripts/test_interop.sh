#!/usr/bin/env bash
#
# scripts/test_interop.sh: Run the murmur interop suite against a local
# mumble-server (see docs/tech-debt-whales/target-architecture/testing.md).
#
# Never called by check.sh: the suite takes a minute or more. Recommended
# when a change touches networking code (transport, client and session, or
# native networking in voice_io). Re-executes itself inside the nix dev
# shell (like check.sh), which provides `mumble-server` (pkgs.murmur) and
# the Gradle toolchain.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

# The outer agent shell is often already in a nix dev shell from the root
# flake (without pkgs.murmur). Re-exec once into this worktree's shell when
# mumble-server is missing, so the binary (flake buildInputs) is on PATH.
# The marker guards against an exec loop, mirroring check.sh's _CHECK_SH_REEXEC.
if [ -z "${_TEST_INTEROP_REEXEC:-}" ] && ! command -v mumble-server >/dev/null 2>&1; then
  _TEST_INTEROP_REEXEC=1 exec nix develop --command "$SCRIPT_DIR/$(basename "${BASH_SOURCE[0]}")" "$@"
fi

if ! command -v mumble-server >/dev/null 2>&1; then
  echo "test_interop.sh: error: mumble-server not on PATH (enter 'nix develop' or install murmur)." >&2
  exit 1
fi

# CryptState loads libhumlaaudio from build/test-native (same as check.sh
# step 2b); build it first so direct script invocations work without a
# prior check.sh run.
"$ROOT_DIR/scripts/test_native_audio.sh"

# Until step 3 the harness drives HumlaConnection under Robolectric; from
# step 3 on it drives HumlaClient directly. The task includes only the
# se.lublin.humla.interop suite; every default unit-test task excludes it.
./gradlew :libraries:humla:murmurInteropTest "$@"
