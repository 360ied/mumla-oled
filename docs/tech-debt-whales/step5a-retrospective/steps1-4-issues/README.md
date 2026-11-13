# Steps 1–4 Deep Audit: Issues

Part of the [step 5a retrospective](../README.md). Snapshot: 2026-11-12,
`master` at `092d9dc8`. All line numbers in these files refer to that
commit; symbols are given alongside so they stay findable as code moves.
The `Status` rows were refreshed later the same day, at `4e9474c7`, after
the spec commits that resolved the spec-only issues.

## How this audit was done

Five read-only reviewer processes each compared one area of the
[target architecture](../../target-architecture/README.md) spec against
the code implementing migration steps 1–4: transport, client and session,
state, route plus step-4 voice control, and verification claims (tests and
checks the spec says exist). They were told to report drift only (not
general code review), to treat the documented interims as expected, and to
skip three already-known gaps (`connectEpoch`, the 5-scenario interop
suite, and the never-run step-1 race validation; all tracked in
[open-items.md](../../target-architecture/open-items.md)).

Every finding the reviewers rated harmful was then checked by hand against
the current code and, where the question was "did this change?", against
the pre-migration tree in git history (the reviewers could not read
history). Several were downgraded as a result: behavior preserved from
before the migration is recorded as a spec/behavior mismatch, not a
regression. Benign findings were spot-checked; each file's "Verified" row
says how far it was checked.

This supersedes the shorter conformance read in
[what's still fine](../whats-still-fine.md), whose overall verdict (steps
1–4 are sound and in daily use) still holds, with the corrections below.

## Handing an issue off

Each file is self-contained: summary, the spec text it is measured
against, the code as it is, what the pre-migration code did, impact, fix
directions, and acceptance criteria. Fix directions describe options; they
are not prescriptive. For code changes, follow `AGENTS.md`: a dedicated
worktree (`./scripts/worktree.py add <branch>`), atomic commits through
`scripts/commit.py`, and `./scripts/check.sh` before reporting done. Where
an issue says the spec must change, that change lands on `master` as a
standalone docs commit **before** the code that depends on it (migration
ground rules).

## Issues

State is current as of 2026-11-12, after the three spec commits that
followed the audit (`028d2222`, `ec9faf63`, `4e9474c7`). The spec-only
fixes (12, 13, 24, 25, 27, 31, and the interim entries for 14, 15, 18)
are applied on `master`; the decisions for 09, 10, 11, and 19 are
recorded; 06's review rules are in the pedantic-review skill. What
remains is code, tests, and the two sweeps listed below. Each issue file
carries the same state in its `Status` row.

### Confirmed regressions

| # | Issue | Introduced by | State |
|---|---|---|---|
| [01](01-sco-manager-two-thread-mutation.md) | `BluetoothScoManager` is mutated from two threads (route and main); spec's route-thread confinement not done | Step 4 | Open; fix before 5a.3 |
| [02](02-service-destroy-leaks-transport.md) | Service destroy can leak the TLS connection; transport not closed by scope cancellation | Step 3 | Open; independent bugfix, not gating |

### Verification gaps

| # | Issue | State |
|---|---|---|
| [03](03-voice-control-test-vacuous.md) | `VoiceControlTest` never starts a session, so the pipeline lifecycle is untested | Open; deferred to 5a.4, lesson applies to its replacement |
| [04](04-session-transport-virtual-time-gaps.md) | Most specified virtual-time scenarios missing; no `Session` or `TransportImpl` tests | Open; should close before 5a.3 |
| [05](05-client-edge-invariants-weak.md) | Per-step invariants not asserted; edge check looser than the diagram | Open; with 04 |
| [06](06-update-conventions-not-enforced.md) | The two `update {}` conventions are not in any review checklist; one violation in `Session` | Half applied: review rules landed; `ServerSync` lambda and the rule-2 sweep open |
| [07](07-channel-adapter-spike-weaker.md) | Step-2 live-handle spike test is weaker than specified | Open; not gating |
| [08](08-hash-clearing-untested.md) | Hash-driven clearing has no transition test | Open; not gating |

### Spec/behavior mismatches (behavior preserved from before the migration)

| # | Issue | State |
|---|---|---|
| [09](09-route-settling-unobservable.md) | `Settling` never observable; `Ready` published before the route is confirmed | Decided (spec follows code); one pinning test open |
| [10](10-route-fallback-unreachable.md) | `Fallback` unreachable when a media sink exists; `ScoFailed` and state disagree | Decided; `route.md` rewritten, `Fallback` code change open |
| [11](11-route-changed-suppressed-on-recheck.md) | `RouteChanged` not emitted for recheck-driven state changes | Decided; `route.md` rewritten, drop-`isRecheck` code change open |
| [12](12-route-prefs-skip-debounce-and-settling.md) | Re-decisions skip `Settling`; preference changes skip the debounce | Resolved (spec follows code) |
| [13](13-cancel-reconnect-resets-attempts.md) | `cancelReconnect` resets the attempt counter (legacy parity) | Resolved (spec); pinning test open |

### Undocumented interims

| # | Issue | State |
|---|---|---|
| [14](14-local-facts-self-mute-stub.md) | Persisted self-mute/deafen never reaches the session; `LocalFactsStore` self-mute half is a stub | Interim documented; code fix at step 6 |
| [15](15-current-bandwidth-bypasses-stats.md) | `getCurrentBandwidth` reads `AudioHandler`, not session stats | Interim documented; which source wins is open |
| [16](16-voice-link-owns-udp-route.md) | The voice link, not the transport, decides the initial UDP/TCP route | Open; settle before 5a.3 |
| [17](17-udp-format-per-packet.md) | UDP packet format re-decided per packet via an extra `VoiceLink` method | Open; settle before 5a.3 |
| [18](18-native-mute-writers-bypass-policy.md) | Native mute/PTT gates have writers outside the interim policy path | Interim documented; ends in 5a.4 |
| [19](19-pre-sync-local-intents-published.md) | Local intents published before `SYNCED`, merged back at `ServerSync` | Decided; `state.md` updated, code change open |

### Benign drift

| # | Issue | State |
|---|---|---|
| [20](20-batch-drain-publishes-per-message.md) | 64-message batch drain still publishes once per message | Open; needs a decision (implement publish-once, or correct the spec's cost model) |
| [21](21-retry-sco-no-counter-check.md) | `RetrySco` skips the counter check, publishes nothing, always returns true | Half resolved (spec); truthful return open |
| [22](22-keepalive-anchored-at-construction.md) | Keepalive window and 20 s tunnel rule anchored at transport creation | Open; fold into 04's transport tests |
| [23](23-syncing-not-entered-on-channelstate.md) | `SYNCING` entered only on `UserState` | Open; small code fix |
| [24](24-handles-drop-navigation-after-removal.md) | Removed handles drop navigation (spec wording too broad; matches old model) | Resolved (spec) |
| [25](25-user-state-writes-local.md) | `applyUserState` writes `local.listeningVolumes` (spec self-contradiction) | Resolved (spec); optional test |
| [26](26-stats-two-collectors.md) | Stats from two collectors; `usingUdp` seeded from the target | Open; fold into 04 |
| [27](27-failed-with-connection-error.md) | `Failed` can carry a `ClientError.Connection` | Decided; `client.md` updated, `ClientError.Internal` code change open |
| [28](28-local-facts-load-failure-silent.md) | A failed `LocalFacts` load is not logged | Open; trivial |
| [29](29-half-duplex-raw-preference.md) | `VoiceSettings.halfDuplex` is the raw preference | Open; small |
| [30](30-connected-races-sync-tail.md) | `Connected` can run before the session's `ServerSync` tail | Open; revisit once 14 restores self-mute in the session |
| [31](31-boundary-files-mismatch.md) | `BOUNDARY_FILES` has an entry `enforcement.md` does not list | Resolved (spec); `HumlaTCP` boundary question in [open items](../../target-architecture/open-items.md) |

## Remaining work, in dependency order

- **Small fixes, one worktree, no new test infrastructure:** 28, 23, 29,
  27, 13's pinning test, and 19 together with 06's `ServerSync` lambda
  (same branch of `handleMessage`). One file each, except 27's app
  `when (ClientError)` sites.
- **Route (one worktree):** 01 first, because the confinement turns 10's
  "SCO failed in this route session" tracking into a plain field instead
  of a third atomic; then 10, 11, 21, and 09's pinning test.
- **Client/transport lifecycle and tests (one or two worktrees):** 02,
  then 04 in scenario batches, then 05, then 16, 17, 22, 26, 30, which
  are each easiest to pin with the tests from 04.
- **Sweeps:** 06's rule-2 read-then-act pass over `Session`,
  `PolicyCollector`, and `RouteControl` (the audit did not do it); 07 and
  08 are test-only.
- **Needs a decision before it can be scheduled:** 20 (implement
  publish-once or correct the spec's cost model) and 15 (which source
  wins).
- **Defer to the step that removes the code:** 03 and 18 (5a.4), 14
  (step 6).
