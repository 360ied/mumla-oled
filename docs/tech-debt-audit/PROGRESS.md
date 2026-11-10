# Technical debt audit — implementation progress

Tracks paydown of the plan in
[07-remediation-roadmap.md](07-remediation-roadmap.md). Each `R` item lands
as its own worktree branch; this file records what landed, what was
decided, and what is still owed. Item text and done-criteria live in `07`;
this file is the log, not the plan.

## Phase 0 — Unblock and gate (2026-11-10)

| item | status | branch / commit | notes |
|---|---|---|---|
| R1 — Fix `modelHash` → `modelVersionOrDigest` (P0, XS) | done | `fix/r1-model-hash`, `786357c4` | Two-line fix in `libraries/humla/build.gradle` (`prepareRnnoiseModel`). Verified: pre-fix fully cold run reproduced `Could not get unknown property 'modelHash'`; post-fix cold run downloads, digest-verifies, and regenerates; roadmap gate (`rm -rf libraries/humla/build/model_cache build/test-native && ./scripts/check.sh`) green. |
| R2 — Add a CI workflow (P1, S) | declined | — | Declined by maintainer decision 2026-11-10: no CI wanted. Consequence: the roadmap's "every other automated guard in this plan is advisory until R2 lands" clause has no landing point — `./scripts/check.sh` stays the manual gate indefinitely, and cold-cache coverage stays manual via the R1 gate command above. |

## Incidental findings (unactioned, for a later item)

- `:app:lintFossDebug` is red on unmodified `master` (found while scoping
  the declined R2): `MissingSuperCall` on `MumlaService.onBind` and
  `WrongConstant` in `ChannelListAdapter`. The audit assumed lint was green;
  whichever phase takes lint on must disposition these two errors first
  (fix, baseline, or documented advisory).
