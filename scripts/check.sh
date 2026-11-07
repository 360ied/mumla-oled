#!/usr/bin/env bash
#
# scripts/check.sh: Fast pre-commit and post-implementation verification for AI agents.
#
set -eo pipefail

# Run from the repository root so suite paths work regardless of the
# caller's working directory (mirrors test_native_audio.sh).
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT_PATH="$ROOT_DIR/scripts/$(basename "${BASH_SOURCE[0]}")"
cd "$ROOT_DIR"

FULL_TEST=false
for arg in "$@"; do
  if [ "$arg" = "--full" ]; then
    FULL_TEST=true
  fi
done

echo "========================================"
echo " 1. Checking Git Branch & Submodules"
echo "========================================"
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)
GIT_DIR=$(git rev-parse --git-dir 2>/dev/null || true)
GIT_COMMON_DIR=$(git rev-parse --git-common-dir 2>/dev/null || true)

if [ -z "$CURRENT_BRANCH" ]; then
  echo "ERROR: Not inside a git checkout; run from the repository."
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
  echo "ERROR: Uninitialized git submodules detected:"
  echo "$UNINITIALIZED_SUBMODULES"
  echo "Run 'git submodule update --init --recursive' or './scripts/worktree.py add' to initialize them."
  exit 1
fi

# Steps 2 and below need the dev shell (python3, NDK, Gradle toolchain).
# Enter it once for the whole run instead of once per step: re-exec this
# script under `nix develop` when invoked from outside. Already inside (or
# the marker is set, meaning re-exec did not land in a shell exporting
# IN_NIX_SHELL) -> run the steps directly. The marker doubles as the exec-
# loop guard. Note: if `nix` itself is missing, exec fails and `set -e`
# exits here; there is no fall-through.
if [ -z "${IN_NIX_SHELL:-}" ] && [ -z "${_CHECK_SH_REEXEC:-}" ]; then
  export _CHECK_SH_REEXEC=1
  exec nix develop --command "$SCRIPT_PATH" "$@"
fi

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
