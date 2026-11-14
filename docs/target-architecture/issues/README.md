# Issues

The live queue of drift between the [target architecture](../README.md)
spec and the code. Each issue file's `Status` row is authoritative; the
`State` column below is its summary, updated in the same commit.
[`open-items.md`](../open-items.md) links here for anything that gates a
step rather than restating it.

The queue began as a deep audit of steps 1–4, prompted by the
[step 5a retrospective](../history/step5a-retrospective/README.md). An
issue's bar is conformance, not the [admission bar](../README.md#admission-bar):
the code disagrees with the spec, or a verification the spec promises is
missing. New findings take the next number. Audit snapshot: 2026-11-12,
`master` at `092d9dc8`. All line numbers in these files refer to that
commit; symbols are given alongside so they stay findable as code moves.
The `Status` rows were refreshed later the same day, at `4e9474c7`, after
the spec commits that resolved the spec-only issues.

## How this audit was done

Five read-only reviewer processes each compared one area of the
[target architecture](../README.md) spec against
the code implementing migration steps 1–4: transport, client and session,
state, route plus step-4 voice control, and verification claims (tests and
checks the spec says exist). They were told to report drift only (not
general code review), to treat the documented interims as expected, and to
skip three already-known gaps (`connectEpoch`, the 5-scenario interop
suite, and the never-run step-1 race validation; all tracked in
[open-items.md](../open-items.md)).

Every finding the reviewers rated harmful was then checked by hand against
the current code and, where the question was "did this change?", against
the pre-migration tree in git history (the reviewers could not read
history). Several were downgraded as a result: behavior preserved from
before the migration is recorded as a spec/behavior mismatch, not a
regression. Benign findings were spot-checked; each file's "Verified" row
says how far it was checked.

This supersedes the shorter conformance read in
[what's still fine](../history/step5a-retrospective/whats-still-fine.md), whose overall verdict (steps
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

State is current as of 2026-11-13, after the three spec commits that
followed the audit (`028d2222`, `ec9faf63`, `4e9474c7`) and the first code
batch (`5ac34784`). The spec-only fixes (12, 13, 24, 25, 27, 31, and the
interim entries for 14, 15, 18) are applied on `master`, and 08, 23, and
28 are now fixed and tested; the decisions for 09, 10, 11, and 19 are
recorded; 19 and 06's `ServerSync` lambda are fixed on branch
`session-serversync-update-hygiene`, pending merge, and the review skills
that were to carry 06's two rules were deleted (`8e88589f`), leaving rule 1
with no enforcement artifact. What remains
is code, tests, and the two sweeps listed below. Each issue file carries
the same state in its `Status` row.

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
| [06](06-update-conventions-not-enforced.md) | The two `update {}` conventions have no enforcement artifact; one violation in `Session` | Split: `ServerSync` lambda fixed on `session-serversync-update-hygiene`; review skills deleted, so enforcement is open (tripwire not written); rule-2 sweep undone |
| [07](07-channel-adapter-spike-weaker.md) | Step-2 live-handle spike test is weaker than specified | Open; not gating |
| [08](08-hash-clearing-untested.md) | Hash-driven clearing has no transition test | Resolved (`3333918a`, `71bd3a54`) |

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
| [19](19-pre-sync-local-intents-published.md) | Local intents published before `SYNCED`, merged back at `ServerSync` | Resolved on `session-serversync-update-hygiene` (`89dd9b57`), pending merge |

### Benign drift

| # | Issue | State |
|---|---|---|
| [20](20-batch-drain-publishes-per-message.md) | 64-message batch drain still publishes once per message | Open; needs a decision (implement publish-once, or correct the spec's cost model) |
| [21](21-retry-sco-no-counter-check.md) | `RetrySco` skips the counter check, publishes nothing, always returns true | Half resolved (spec); truthful return open |
| [22](22-keepalive-anchored-at-construction.md) | Keepalive window and 20 s tunnel rule anchored at transport creation | Open; fold into 04's transport tests |
| [23](23-syncing-not-entered-on-channelstate.md) | `SYNCING` entered only on `UserState` | Resolved (`5d322791`, `0e8b4c15`) |
| [24](24-handles-drop-navigation-after-removal.md) | Removed handles drop navigation (spec wording too broad; matches old model) | Resolved (spec) |
| [25](25-user-state-writes-local.md) | `applyUserState` writes `local.listeningVolumes` (spec self-contradiction) | Resolved (spec); optional test |
| [26](26-stats-two-collectors.md) | Stats from two collectors; `usingUdp` seeded from the target | Open; fold into 04 |
| [27](27-failed-with-connection-error.md) | `Failed` can carry a `ClientError.Connection` | Decided; `client.md` updated, `ClientError.Internal` code change open |
| [28](28-local-facts-load-failure-silent.md) | A failed `LocalFacts` load is not logged | Resolved (`7459c0de`) |
| [29](29-half-duplex-raw-preference.md) | `VoiceSettings.halfDuplex` is the raw preference | Open; small |
| [30](30-connected-races-sync-tail.md) | `Connected` can run before the session's `ServerSync` tail | Open; revisit once 14 restores self-mute in the session |
| [31](31-boundary-files-mismatch.md) | `BOUNDARY_FILES` has an entry `enforcement.md` does not list | Resolved (spec); `HumlaTCP` boundary question in [open items](../open-items.md) |

## Remaining work

Ordered by what must land before step 5a.3, then by dependency. Each row
is one worktree unless noted.

### Before step 5a.3

| Order | Batch | Issues | Notes |
|---|---|---|---|
| 1 | Route confinement | [01](01-sco-manager-two-thread-mutation.md), then [10](10-route-fallback-unreachable.md), [11](11-route-changed-suppressed-on-recheck.md), [21](21-retry-sco-no-counter-check.md), [09](09-route-settling-unobservable.md)'s pinning test | 01 first. Once routing state is confined to the route thread, 10's "SCO failed in this route session" flag can be a plain field instead of another atomic. 11 and 21 are not gating but touch the same `RouteControl` code. |
| 2 | Session and transport tests | [04](04-session-transport-virtual-time-gaps.md) in scenario batches, then [05](05-client-edge-invariants-weak.md) | May take two worktrees. Builds the `Session` and `TransportImpl` fakes that row 3 reuses. |
| 3 | Transport ownership | [16](16-voice-link-owns-udp-route.md), [17](17-udp-format-per-packet.md); with them [22](22-keepalive-anchored-at-construction.md), [26](26-stats-two-collectors.md) | 16 and 17 decide what the transport owns before 5a.3 reimplements the voice side natively. 22 and 26 are not gating; each is easiest to pin with 04's transport tests. |

### Independent, any time

| Batch | Issues | Notes |
|---|---|---|
| Small library fixes (one worktree) | [29](29-half-duplex-raw-preference.md), [27](27-failed-with-connection-error.md), [13](13-cancel-reconnect-resets-attempts.md)'s pinning test, [19](19-pre-sync-local-intents-published.md) together with [06](06-update-conventions-not-enforced.md)'s `ServerSync` lambda | All in `libraries/humla`. 29 touches `VoiceSettingsMapper` and `VoiceControl`. 27 adds `ClientError.Internal`; `HumlaService`'s exhaustive `when` must handle it, and the app has no `ClientError` switches. 19 and 06 are the same branch of `Session.handleMessage`. `HumlaClientTest` already drives sync through a fake transport, so 13's and 19's tests need no new harness. |
| Service destroy leak | [02](02-service-destroy-leaks-transport.md) | Independent bugfix. |
| Rule-2 review sweep | [06](06-update-conventions-not-enforced.md) | Read-then-act pass over `Session`, `PolicyCollector`, and `RouteControl` (the audit did not do it). Sweep `RouteControl` after row 1, since confinement changes its reads. |
| Spike test | [07](07-channel-adapter-spike-weaker.md) | Test-only. |

### Blocked

| Issues | Waiting on |
|---|---|
| [20](20-batch-drain-publishes-per-message.md) | A decision: implement publish-once, or correct the spec's cost model. |
| [15](15-current-bandwidth-bypasses-stats.md) | A decision on which source wins; goes away if 5a's `VoiceStatus` replaces it. |
| [03](03-voice-control-test-vacuous.md), [18](18-native-mute-writers-bypass-policy.md) | Step 5a.4, which removes the code. |
| [14](14-local-facts-self-mute-stub.md) | Step 6 (real `LocalFactsStore`). |
| [30](30-connected-races-sync-tail.md) | 14: the race only matters once the session restores self-mute. |
