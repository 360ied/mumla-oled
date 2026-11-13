# 22 — Keepalive bootstrap window and the 20 s tunnel rule start at transport creation, not `ServerSync`

| | |
|---|---|
| Status | Open — fold into the transport tests from [04](04-session-transport-virtual-time-gaps.md). |
| Category | Spec drift |
| Severity | Benign (affects reactivity on slow connects, not correctness) |
| Verified | Lens-reported; key lines confirmed (`startNanos` set in the constructor, `startKeepalive` does not reset it) |
| Area | Transport |
| Spec | [transport.md § Keepalive and UDP health](../transport.md#keepalive-and-udp-health) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/net/TransportImpl.kt` (`startNanos` line 150, `elapsedMicros` line 164, `nextPingDelaySeconds`, `handlePing`, `startKeepalive` line 583, constants `BOOTSTRAP_DURATION_MICROS = 30_000_000L` line 86, `UDP_GIVEUP_MICROS = 20_000_000L` line 87) |
| Gates | Not gating; fix alongside the transport tests in [04](04-session-transport-virtual-time-gaps.md) |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

The spec measures the ping bootstrap window ("every 5 s for the first 30 s
after `ServerSync`") and the UDP give-up rule ("more than 20 s have passed")
from `ServerSync`. `TransportImpl` captures `startNanos = System.nanoTime()`
when the transport object is constructed, before DNS/SRV, TCP connect, the
TLS handshake, and authentication, and `startKeepalive()` (called at
`ServerSync`) does not re-anchor it.

## What the spec says

`transport.md` § Keepalive and UDP health: "a ping every 5 s for the first
30 s after `ServerSync`", and the tunnel switch when UDP is in use and
"more than 20 s have passed" with no good UDP packets.

## What the code does

`private val startNanos = System.nanoTime()` (line 150, in the class body,
so at construction). `elapsedMicros() = (System.nanoTime() - startNanos) / 1000`
(line 164) feeds both `nextPingDelaySeconds` (bootstrap vs steady cadence)
and the `UDP_GIVEUP_MICROS` test in `handlePing`.

## Before the migration

Not checked against `HumlaConnection` (the audit lens could not read
history). If the old connection object was created at the same point
relative to the handshake, this may be inherited behavior.

## Impact

On a slow connect (the connect timeout alone is 10 s; TLS and sync add
more), the session can drop to the 10 s steady cadence during its real
first 30 s, and the 20 s UDP rule can trigger on the very first ping reply
after sync, switching to tunnel mode earlier than intended. Correctness is
unaffected (the switch-back rule recovers).

## Fix directions

Re-anchor at `ServerSync`: set the anchor in `startKeepalive()` (make it an
`AtomicLong` or confine it), or keep a separate `syncNanos`. Inject a clock
(`() -> Long`) so the transport tests in
[04](04-session-transport-virtual-time-gaps.md) can drive time.

## Acceptance

A transport test with an injected clock: 25 s of connect/handshake, then
`ServerSync`; pings stay at 5 s for 30 s after sync, and the UDP give-up
does not fire before 20 s after sync. `./scripts/check.sh` passes.
