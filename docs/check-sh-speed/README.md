# `check.sh` Speed Work: Deliberately Deferred Items

Context: the `perf/check-sh-speed` branch decoupled unit tests from native
rebuilds, parallelized and incrementalized
[`scripts/test_native_audio.sh`](../../scripts/test_native_audio.sh), and
hardened the result. A pedantic code review of that work surfaced the items
below. Each was evaluated against one rule — **fix now if fail-open or cheap
and likely; defer if safe-direction and rare, churn without behavioral gain,
or out of scope** — and deferred with the rationale, fix shape, and revisit
trigger recorded here so a future reader can tell intention from oversight.

## 1. Concurrent-run locking (`flock`)

Two processes running the script in the same worktree share
`build/test-native/obj/`. If one hits a wipe path (toolchain change, script
edit, header-set change) while the other is mid-compile, objects vanish under
the compiler, producing confusing link errors or mixed-toolchain links.

Deferred because no automated flow runs the script against itself anymore:
`check.sh` is strictly sequential and Gradle no longer fires the script
alongside step 2b. Only manual same-worktree concurrent runs can collide.
A correct fix also needs platform branching — `flock` is util-linux-only
(present in the nix dev shell, absent on macOS) — for a window nobody has hit.

Fix shape: `flock "$BUILD_DIR/.build.lock"` around `compile_and_link`, with a
macOS fallback (warn and proceed, or `shlock`).

Revisit if CI ever parallelizes worktree runs, or anyone actually collides.
The temp-file-plus-rename compiles already narrow the window: partial objects
cannot poison retries; only wipe races remain.

## 2. CXX as an array instead of a word-split string

The script wraps with `CXX="ccache $CXX"` and expands `$CXX` unquoted at four
sites (with `shellcheck disable=SC2086` annotations). A path or flag
containing spaces or quotes would break word-splitting and muddy what
`$CXX --version` records for the toolchain fingerprint.

Deferred because the inputs are fully controlled: `CXX` defaults to `g++` or
arrives as the nix gcc-wrapper path, neither of which can contain spaces.
Refactoring to `CXX_ARR=()` would touch every compile, link, and fingerprint
line for zero behavioral gain. The semantics that mattered (fingerprinting the
full `CXX` string) were fixed without the churn.

Revisit if custom toolchains with arguments (for example `CXX="g++ -m32"`)
need first-class support, or the next editor trips over the disables.

## 3. `-MMD` depfiles for precise header dependencies

Touching one header rebuilds all ~150 Opus translation units (about 40 s
true-cold, about 3 s ccache-warm). Per-TU depfiles would narrow rebuilds to
true dependents only.

Deferred as an explicit safe-direction trade-off: header edits are rare, the
penalty is bounded and usually ccache-absorbed, and depfile plumbing in bash
(stale `.d` handling, `-MP` phony targets for deleted headers, bootstrap
ordering) adds failure modes to a test gate where simplicity is reliability.
The current scheme can only over-rebuild, never under-rebuild; header
deletion/rename staleness is separately covered by the per-profile header-set
hash.

Revisit if someone iterates on widely-included headers and eats the cold
penalty repeatedly, or ccache becomes unavailable in some environment.

## 4. Pre-existing `check.sh` nits

[`scripts/check.sh`](../../scripts/check.sh) uses `==` inside single brackets
(a bashism, harmless under its `bash` shebang) and `set -eo pipefail` without
`-u`. No misbehavior observed.

Deferred as out of scope: `check.sh` was intentionally untouched by the speed
work (minimal-diff principle), and these are cosmetic. They belong in whatever
commit next touches that file for a real reason.
