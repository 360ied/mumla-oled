#!/usr/bin/env bash
#
# scripts/check.sh: Fast pre-commit and post-implementation verification for AI agents.
#
set -eo pipefail

FULL_TEST=false
for arg in "$@"; do
  if [ "$arg" == "--full" ]; then
    FULL_TEST=true
  fi
done

echo "========================================"
echo " 1. Checking Git Branch & Submodules"
echo "========================================"
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
GIT_DIR=$(git rev-parse --git-dir 2>/dev/null || true)
GIT_COMMON_DIR=$(git rev-parse --git-common-dir 2>/dev/null || true)

if [ "$GIT_DIR" != "$GIT_COMMON_DIR" ] && [ -n "$GIT_COMMON_DIR" ]; then
  echo "OK: Running inside Git worktree for branch '$CURRENT_BRANCH'."
elif [ "$CURRENT_BRANCH" == "master" ]; then
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

echo ""
echo "========================================"
echo " 2. Running Python Unit Tests"
echo "========================================"
python3 -m unittest discover -s scripts -p "test_*.py" -v

echo ""
echo "========================================"
echo " 2b. Running Native C++ Audio Tests"
echo "========================================"
nix develop --command ./scripts/test_native_audio.sh

echo ""
echo "========================================"
echo " 3. Running Gradle Tests"
echo "========================================"
if [ "$FULL_TEST" = true ]; then
  echo "Running full test suite..."
  nix develop --command ./gradlew test
else
  echo "Running fast FOSS debug unit tests..."
  nix develop --command ./gradlew testFossDebugUnitTest :libraries:humla:testDebugUnitTest
fi

echo ""
echo "========================================"
echo " ALL CHECKS PASSED SUCCESSFULLY!"
echo "========================================"
