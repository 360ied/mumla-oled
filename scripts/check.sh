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
echo " 1. Checking Git Branch"
echo "========================================"
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [ "$CURRENT_BRANCH" == "master" ]; then
  echo "WARNING: Currently on 'master' branch! AGENTS.md mandates dedicated branches."
else
  echo "OK: On dedicated branch '$CURRENT_BRANCH'."
fi

echo ""
echo "========================================"
echo " 2. Running Python Unit Tests"
echo "========================================"
python3 -m unittest discover -s scripts -p "test_*.py" -v

echo ""
echo "========================================"
echo " 3. Running Gradle Tests"
echo "========================================"
if [ "$FULL_TEST" = true ]; then
  echo "Running full test suite..."
  nix develop --command ./gradlew test
else
  echo "Running fast FOSS debug unit tests..."
  nix develop --command ./gradlew testFossDebugUnitTest
fi

echo ""
echo "========================================"
echo " 4. Validating Latest Commit (50/72)"
echo "========================================"
python3 scripts/commit.py --check -m "$(git log -1 --pretty=%B)"

echo ""
echo "========================================"
echo " ALL CHECKS PASSED SUCCESSFULLY!"
echo "========================================"
