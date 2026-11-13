# 04 — Most specified virtual-time scenarios have no test

| | |
|---|---|
| Status | Open — should be closed before step 5a.3. |
| Category | Verification gap |
| Severity | Harmful (step 3's failure and recovery paths are untested; 5a.3 builds on them) |
| Verified | Yes (test inventory and scenario-by-scenario comparison) |
| Area | Client, session, transport |
| Spec | [testing.md § Virtual-time tests](../testing.md#virtual-time-tests), [testing.md § Test layers](../testing.md#test-layers) |
| Code | `libraries/humla/src/test/java/se/lublin/humla/client/HumlaClientTest.kt`, `client/ReconnectPolicyTest.kt`, `net/*Test*`; production `client/Session.kt`, `net/TransportImpl.kt` |
| Gates | Should gate step 5a.3 (native receive plugs into the transport and `VoiceLink`) |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

`testing.md` lists ten virtual-time scenarios and five invariants for the
client, session, and transport. About four scenarios have tests, all in
`HumlaClientTest` against a fake transport. There is **no `SessionTest`**
and **no `TransportImplTest`**: the session's message handling and failure
semantics, and the transport's keepalive, UDP health, tunnel switching,
crypt resync, and backpressure logic are covered only by small pure-helper
tests and by the (partial) murmur interop suite.

## What the spec says

`testing.md` § Virtual-time tests:

> Under `kotlinx-coroutines-test`, with fakes for `Transport`,
> `NetworkMonitor`, `AudioPlatform`, `ScoLink`, and the native voice layer.
>
> Scenarios:
> - disconnect at every message boundary of the sync sequence;
> - `connect` to another target while connected, while connecting, and
>   while reconnecting;
> - reconnect racing a user disconnect and a `cancelReconnect`;
> - network-available events during each `ClientState`;
> - the reconnect delay sequence and counter rules (from
>   `ReconnectBackoffTest`);
> - mute, deafen, and PTT spam during connect and disconnect;
> - route flaps mid-connect, SCO failure while `Settling`, `RetrySco`;
> - `Reject`, removal of self, and a trust failure each ending in `Failed`
>   with no reconnect;
> - forced TCP from `ServerSync`, the UDP-to-tunnel switch and back, and a
>   `CryptResyncRequested` event producing exactly one empty `CryptSetup`;
> - a full transport outbound channel failing the session.

(The invariants are covered in [05](05-client-edge-invariants-weak.md).)

## What exists

`HumlaClientTest.kt` (10 tests):
`connectSyncDisconnect`, `reconnectBackoffSequence`,
`rejectGoesFailedWithoutReconnect`, `networkAvailableRetriesAtOnce`,
`connectWhileConnectedKeepsOneSession`, `muteSpamDuringConnectIsSafe`,
`staleConnectFailureDoesNotClobberNewerAttempt`,
`staleEqualTargetFailureDoesNotClobberNewerAttempt`,
`staleBugFailureDoesNotClobberNewerAttempt`,
`ioErrorReconnectsButBugDoesNot`.

`ReconnectPolicyTest.kt`: the delay table (this is the spec's
`ReconnectBackoffTest` under another name; it pins delays, not the counter
reset rules).

`net/` tests: `AdaptiveKeepaliveTest`, `PingIntervalTest` (pure helpers for
the ping cadence), `TlsIdentityTest`, `HumlaSSLSocketFactoryTest`,
`HumlaCertificateGeneratorTest`, `CryptStateTest`, and the Kotlin
`HumlaUDP*` tests (which test the interim UDP class, not `TransportImpl`).

Route scenarios are covered separately by the route tests (not assessed
here in detail).

## Scenario-by-scenario

| Scenario | Status |
|---|---|
| Disconnect at every message boundary of the sync sequence | **Missing** |
| `connect` to another target while connected / connecting / reconnecting | Partly: while connected (`connectWhileConnectedKeepsOneSession`); stale-attempt tests cover connecting; reconnecting not covered |
| Reconnect racing a user disconnect and a `cancelReconnect` | **Missing** (`HumlaClient.cancelReconnect` has no test at all) |
| Network-available events during each `ClientState` | Partly: only during `Reconnecting` |
| Reconnect delay sequence and counter rules | Partly: delays yes; counter reset rules no (see [13](13-cancel-reconnect-resets-attempts.md)) |
| Mute, deafen, PTT spam during connect and disconnect | Partly: mute spam during connect only |
| Route flaps, SCO failure while `Settling`, `RetrySco` | Not in client tests; route-level tests not assessed here |
| `Reject`, removal of self, trust failure → `Failed`, no reconnect | Partly: `Reject` only; removal of self and trust failure **missing** |
| Forced TCP from `ServerSync`; UDP→tunnel and back; one empty `CryptSetup` per `CryptResyncRequested` | **Missing** (`TransportImpl.startKeepalive`, `switchToTunnel`, the switch-back branch of `handlePing`, and the resync handling around `TransportImpl.kt:396` are untested) |
| Full outbound channel fails the session | **Missing** (`TransportImpl.send`/`sendTunnelled` failure path untested) |

## Why it matters

- These are exactly the paths step 3 rewrote (reconnect, failure mapping,
  teardown, UDP health), and exactly the paths daily use exercises least
  (they need server restarts, network loss, blocked UDP, or crypt desync).
- Issue [02](02-service-destroy-leaks-transport.md) (transport not closed
  by scope cancellation) is the kind of defect a `Session`/`Transport` test
  with a fake would have caught.
- Step 5a.3 replaces `LegacyVoiceLink` with the native `VoiceEngine` behind
  the same `VoiceLink` contract (keys, IVs, pings, tunnelled packets,
  `SetVoiceRoute`, resync requests). Without tests pinning the transport's
  side of that contract, 5a.3 has nothing to regress against except
  interop and daily use, which is the verification shape that failed in
  the first 5a attempt.

## Fix directions

1. **`TransportImpl` tests with a fake socket and fake `VoiceLink`.** The
   transport currently builds its own TLS socket in `DefaultTransportFactory`;
   a test needs either an injectable socket/stream pair or a constructor
   that accepts already-connected streams. With that, drive:
   - ping cadence and the 20 s UDP give-up rule under virtual time (note
     `startNanos` uses `System.nanoTime()`; inject a clock, see
     [22](22-keepalive-anchored-at-construction.md));
   - the switch to tunnel (`VoiceCommand.SetVoiceRoute(false)` submitted
     once) and back (`SetVoiceRoute(true)` after more than 3 good pings
     each way);
   - forced TCP at `ServerSync` (dummy tunnel frame);
   - `CryptResyncRequested` → exactly one empty `CryptSetup`, throttled;
   - outbound channel full → session failure with `ClientError.Connection`;
   - invalid frame → protocol-violation failure;
   - scope cancellation → socket closed, reader exits without failure (pins
     [02](02-service-destroy-leaks-transport.md)).
2. **`Session` tests with a fake `Transport`.** Feed scripted inbound
   message sequences and assert: `failure` completes exactly once for each
   fatal condition (I/O, `Reject`, self `UserRemove`, protocol violation);
   disconnect (scope cancel) at each sync boundary leaves no live
   coroutine; `ServerSync` triggers `startKeepalive` and the restored
   self-mute `UserState`.
3. **Fill the `HumlaClientTest` gaps:** `cancelReconnect` racing a pending
   retry; removal of self and trust failure ending in `Failed` with no
   reconnect; network-available in each state; connect while reconnecting.

## Acceptance

- New `SessionTest` and `TransportImplTest` (names indicative) exist under
  `libraries/humla/src/test/java/se/lublin/humla/`, run in
  `testFossDebugUnitTest`, and cover every row marked Missing above.
- Each new test is shown to fail when the behavior it pins is broken (for
  example, temporarily removing the resync throttle makes the
  "exactly one `CryptSetup`" test fail). This is the same discipline as
  the step-1 harness validation.
- `./scripts/check.sh` passes.

## Related

- [05](05-client-edge-invariants-weak.md): the invariant harness.
- [02](02-service-destroy-leaks-transport.md): a defect these tests would
  catch.
- Open item "Step 1 harness validation" in
  [open-items.md](../open-items.md).
