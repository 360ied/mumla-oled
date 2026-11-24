# Step 1 — Harness and gate

Part of the [migration](../migration.md). **Status: done.**
This is the step's plan as it was executed, kept as a record;
[`PROGRESS.md`](../PROGRESS.md) summarises what landed, and the living
specs hold the current design.

1. `scripts/check_architecture.py` with its tests: concurrency tripwire,
   native tripwire, Android-free package check (packages that do not exist
   yet pass trivially). `scripts/architecture-allowlist.txt` seeded with
   today's files and counts. Wired into `check.sh`.
2. Dev shell: `pkgs.murmur`. Test resources: the loopback test certificate
   and key. `:libraries:humla` test dependencies: Robolectric (version
   from the catalog). The `murmurInteropTest` Gradle task and
   `scripts/test_interop.sh` (not wired into `check.sh`).
3. Interop scenarios against today's `HumlaConnection` (see
   [testing](../testing.md#murmur-interop)).
4. `StateImmutabilityTest` scaffold (walks nothing until step 2) and
   `PROGRESS.md`.

**Done when:** the harness reproduces the stale-callback race fixed in
`2e2a067d` with that fix reverted in a throwaway worktree, failing on 10 of
10 runs, and passes on 10 of 10 runs on `master`.

## Kill criterion

**Step 1** cannot reproduce a known past race. The harness cannot vouch
for later steps; fall back to the satisficing
[whales list](../history/whales-list.md).
