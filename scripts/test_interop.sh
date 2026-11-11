#!/usr/bin/env bash
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

# Unlike check.sh (which skips re-exec when IN_NIX_SHELL is set), this script
# must guarantee pkgs.murmur: the outer shell often comes from the root flake
# without it. So re-exec purely on the missing binary, not on IN_NIX_SHELL.
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
"$ROOT_DIR/gradlew" :libraries:humla:murmurInteropTest "$@"
