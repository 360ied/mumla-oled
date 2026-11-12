# Steps 1–4 Deep Audit: Issues

Part of the [step 5a retrospective](../README.md). Snapshot: 2026-11-12,
`master` at `092d9dc8`. All line numbers in these files refer to that
commit; symbols are given alongside so they stay findable as code moves.

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

### Confirmed regressions

| # | Issue | Introduced by | Gates |
|---|---|---|---|
| [01](01-sco-manager-two-thread-mutation.md) | `BluetoothScoManager` is mutated from two threads (route and main); spec's route-thread confinement not done | Step 4 | Before 5a.3 |
| [02](02-service-destroy-leaks-transport.md) | Service destroy can leak the TLS connection; transport not closed by scope cancellation | Step 3 | — |

### Verification gaps

| # | Issue | Gates |
|---|---|---|
| [03](03-voice-control-test-vacuous.md) | `VoiceControlTest` never starts a session, so the pipeline lifecycle is untested | Lesson for 5a.3 |
| [04](04-session-transport-virtual-time-gaps.md) | Most specified virtual-time scenarios missing; no `Session` or `TransportImpl` tests | Before 5a.3 |
| [05](05-client-edge-invariants-weak.md) | Per-step invariants not asserted; edge check looser than the diagram | With 04 |
| [06](06-update-conventions-not-enforced.md) | The two `update {}` conventions are not in any review checklist; one violation in `Session` | — |
| [07](07-channel-adapter-spike-weaker.md) | Step-2 live-handle spike test is weaker than specified | — |
| [08](08-hash-clearing-untested.md) | Hash-driven clearing has no transition test | — |

### Spec/behavior mismatches (behavior preserved from before the migration)

| # | Issue | Gates |
|---|---|---|
| [09](09-route-settling-unobservable.md) | `Settling` never observable; `Ready` published before the route is confirmed | **Decide before 5a.3** |
| [10](10-route-fallback-unreachable.md) | `Fallback` unreachable when a media sink exists; `ScoFailed` and state disagree | **Decide before 5a.3** |
| [11](11-route-changed-suppressed-on-recheck.md) | `RouteChanged` not emitted for recheck-driven state changes | With 09/10 |
| [12](12-route-prefs-skip-debounce-and-settling.md) | Re-decisions skip `Settling`; preference changes skip the debounce | With 09 |
| [13](13-cancel-reconnect-resets-attempts.md) | `cancelReconnect` resets the attempt counter (legacy parity) | — |

### Undocumented interims

| # | Issue | Gates |
|---|---|---|
| [14](14-local-facts-self-mute-stub.md) | Persisted self-mute/deafen never reaches the session; `LocalFactsStore` self-mute half is a stub | By step 6 |
| [15](15-current-bandwidth-bypasses-stats.md) | `getCurrentBandwidth` reads `AudioHandler`, not session stats | — |
| [16](16-voice-link-owns-udp-route.md) | The voice link, not the transport, decides the initial UDP/TCP route | Before 5a.3 |
| [17](17-udp-format-per-packet.md) | UDP packet format re-decided per packet via an extra `VoiceLink` method | Before 5a.3 |
| [18](18-native-mute-writers-bypass-policy.md) | Native mute/PTT gates have writers outside the interim policy path | Ends in 5a.4 |
| [19](19-pre-sync-local-intents-published.md) | Local intents published before `SYNCED`, merged back at `ServerSync` | — |

### Benign drift

| # | Issue |
|---|---|
| [20](20-batch-drain-publishes-per-message.md) | 64-message batch drain still publishes once per message |
| [21](21-retry-sco-no-counter-check.md) | `RetrySco` skips the counter check, publishes nothing, always returns true |
| [22](22-keepalive-anchored-at-construction.md) | Keepalive window and 20 s tunnel rule anchored at transport creation |
| [23](23-syncing-not-entered-on-channelstate.md) | `SYNCING` entered only on `UserState` |
| [24](24-handles-drop-navigation-after-removal.md) | Removed handles drop navigation (spec wording too broad; matches old model) |
| [25](25-user-state-writes-local.md) | `applyUserState` writes `local.listeningVolumes` (spec self-contradiction) |
| [26](26-stats-two-collectors.md) | Stats from two collectors; `usingUdp` seeded from the target |
| [27](27-failed-with-connection-error.md) | `Failed` can carry a `ClientError.Connection` |
| [28](28-local-facts-load-failure-silent.md) | A failed `LocalFacts` load is not logged |
| [29](29-half-duplex-raw-preference.md) | `VoiceSettings.halfDuplex` is the raw preference |
| [30](30-connected-races-sync-tail.md) | `Connected` can run before the session's `ServerSync` tail |
| [31](31-boundary-files-mismatch.md) | `BOUNDARY_FILES` has an entry `enforcement.md` does not list |

## Suggested grouping for delegation

- **Route (one worktree):** 01, then the 09/10/11/12 decision (spec first),
  then 21. 01 is independent of the decision and can go first.
- **Client/transport lifecycle and tests (one or two worktrees):** 02, 04,
  05, then 16, 17, 22, 26, 30 (each is easiest to pin with the tests from
  04).
- **State (one worktree):** 06 (code part), 08, 19, 23, 20.
- **Docs only, straight to `master`:** 06 (skill checklist), 13 or its code
  alternative, 24, 25, 31, and the interim-list entries for 14, 15, 18.
- **Small standalone:** 27, 28, 29, 15.
- **Defer to the step that removes the code:** 03 (5a.4 deletes
  `VoiceControl`; carry the lesson into 5a.3), 18 (5a.4), 14 (step 6).
