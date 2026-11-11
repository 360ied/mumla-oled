# Target-architecture progress

One row per migration step, updated in the step's final commit.
See [testing](testing.md#progressmd) and [migration](migration.md).

| Step | Status | Commits | Allowlist (files / matches) | Notes |
|---|---|---|---|---|
| 1 Harness and gate | done | fafa6126..51f67972 plus round-3 fixes (see log) | 34 / 336 | Gate + interop land; `check.sh` green. Interop 5/5 scenarios pass. Revert check: buggy `HumlaService` generation order still passes interop (harness drives `HumlaConnection`, insensitive by construction); deterministic lifecycle-race cover arrives with step-3 virtual-time client tests. Includes `KeyManagerFactory` default-algorithm compat fix and two pedantic-review rounds (gate strictness, interop robustness, fail-loud wiring, doc accuracy). |
| 2 Immutable state | not started | | | gated by live-handle spike + immutable-collections pin |
| 3 Client and session | not started | | | |
| 4 Voice control and route | not started | | | |
| 5a Engine refactor, codec, network threads | not started | | | pool size settled by stress test |
| 5b AAudio | not started | | | gated by AAudio/Oboe + callback + echo-cancel spikes |
| 6 Container, storage, and features | not started | | | |
| 7 Screens and service cut-over | not started | | | |
| 8 Clean up | not started | | | end state: allowlist holds only boundary files |
