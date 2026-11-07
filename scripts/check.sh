#!/usr/bin/env bash
#
# scripts/check.sh: Fast pre-commit and post-implementation verification for AI agents.
#
set -eo pipefail

# Run from the repository root so suite paths work regardless of the
# caller's working directory (test_native_audio.sh resolves paths from
# BASH_SOURCE but does not cd; this gate goes one step further and cds).
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_PATH="$ROOT_DIR/scripts/$(basename "${BASH_SOURCE[0]}")"
cd "$ROOT_DIR"

echo "========================================"
echo " 1. Checking Git Branch & Submodules"
echo "========================================"
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)
GIT_DIR=$(git rev-parse --git-dir 2>/dev/null || true)
GIT_COMMON_DIR=$(git rev-parse --git-common-dir 2>/dev/null || true)

if [ -z "$CURRENT_BRANCH" ]; then
  echo "ERROR: Not inside a git checkout; run from the repository." >&2
  exit 1
fi

if [ "$GIT_DIR" != "$GIT_COMMON_DIR" ] && [ -n "$GIT_COMMON_DIR" ]; then
  echo "OK: Running inside Git worktree for branch '$CURRENT_BRANCH'."
elif [ "$CURRENT_BRANCH" = "master" ]; then
  echo "WARNING: Currently on 'master' branch! AGENTS.md mandates dedicated worktrees."
else
  echo "OK: On branch '$CURRENT_BRANCH'."
fi

# Verify git submodules are initialized
UNINITIALIZED_SUBMODULES=$(git submodule status 2>/dev/null | grep '^-' || true)
if [ -n "$UNINITIALIZED_SUBMODULES" ]; then
  echo "ERROR: Uninitialized git submodules detected:" >&2
  echo "$UNINITIALIZED_SUBMODULES" >&2
  echo "Run 'git submodule update --init --recursive' or './scripts/worktree.py add' to initialize them." >&2
  exit 1
fi

# Steps 2 and below need the dev shell (python3, NDK, Gradle toolchain).
# Enter it once for the whole run instead of once per step: re-exec this
# script under `nix develop` when invoked from outside. Past the guard
# (already inside, or re-execed as shown by the marker) the steps run
# directly. The marker doubles as the exec-loop guard. Note: if `nix`
# itself is missing, exec fails and `set -e` exits here.
if [ -z "${IN_NIX_SHELL:-}" ] && [ -z "${_CHECK_SH_REEXEC:-}" ]; then
  _CHECK_SH_REEXEC=1 exec nix develop --command "$SCRIPT_PATH" "$@"
fi

# Parsed here so the pre-exec pass does no dead work; "$@" forwarding
# above preserves the flags across the re-exec.
FULL_TEST=false
for arg in "$@"; do
  if [ "$arg" = "--full" ]; then
    FULL_TEST=true
  fi
done

echo ""
echo "========================================"
echo " 2. Running Python Unit Tests"
echo "========================================"
python3 -m unittest discover -s "$ROOT_DIR/scripts" -p "test_*.py" -v

echo ""
echo "========================================"
echo " 2b. Running Native C++ Audio Tests"
echo "========================================"
"$ROOT_DIR/scripts/test_native_audio.sh"

echo ""
echo "========================================"
echo " 3. Running Gradle Tests"
echo "========================================"
if [ "$FULL_TEST" = true ]; then
  echo "Running full test suite..."
  "$ROOT_DIR/gradlew" test
else
  echo "Running fast FOSS debug unit tests..."
  "$ROOT_DIR/gradlew" testFossDebugUnitTest :libraries:humla:testDebugUnitTest
fi

echo ""
echo "========================================"
echo " ALL CHECKS PASSED SUCCESSFULLY!"
echo "========================================"
