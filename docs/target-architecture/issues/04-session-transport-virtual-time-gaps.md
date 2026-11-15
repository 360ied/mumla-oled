# 04 — Most specified virtual-time scenarios have no test

| | |
|---|---|
| Status | Resolved 2026-11-14 (`bf1b37a3`..`cc3a673f`): `SessionTest` and `TransportImplTest` exist and `HumlaClientTest` fills the client gaps. See [Resolution](#resolution). |
| Category | Verification gap |
| Severity | Harmful (step 3's failure and recovery paths are untested; 5a.3 builds on them) |
| Verified | Yes (test inventory and scenario-by-scenario comparison) |
| Area | Client, session, transport |
| Spec | [testing.md § Virtual-time tests](../testing.md#virtual-time-tests), [testing.md § Test layers](../testing.md#test-layers) |
| Code | `libraries/humla/src/test/java/se/lublin/humla/client/HumlaClientTest.kt`, `client/ReconnectPolicyTest.kt`, `net/*Test*`; production `client/Session.kt`, `net/TransportImpl.kt` |
| Gates | Gated step 5a.3b; gate met |

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

## Resolution

The state at `cc3a673f`. Production changed only by test seams
(`bf1b37a3`): `TransportImpl` takes an `ioDispatcher` and a `nanoTime`
source, and starts its reader, writer, and voice collector through an
internal `start(input, output, socket)` that `connect()` calls with the
TLS socket; `Session` exposes its scope job. Every scenario below runs the
invariant checker from [05](05-client-edge-invariants-weak.md).

| Scenario | Now covered by |
|---|---|
| Disconnect at every message boundary of the sync sequence | `HumlaClientTest.disconnectAtEverySyncBoundaryEndsClean`; at session level `SessionTest.closeAtEverySyncBoundaryLeavesNothingRunning` |
| `connect` to another target while connected / connecting / reconnecting | `connectWhileConnectedKeepsOneSession`, `connectToOtherTargetWhileConnecting` (before and after the session exists), `connectToOtherTargetWhileReconnecting`, plus the three stale-attempt tests |
| Reconnect racing a user disconnect and a `cancelReconnect` | `disconnectBeatsPendingReconnect`, `disconnectRacingReconnectTimerEndsIdle`, `cancelReconnectBeatsPendingReconnect`, `cancelReconnectRacingReconnectTimerLetsAttemptRun` (a timer that fires first wins; only a pending `Reconnecting` is disarmed, as the code documents) |
| Network-available events during each `ClientState` | `networkAvailableRetriesAtOnce` (also repeated events while `Connecting`), `networkAvailableIgnoredOutsideReconnecting` (Idle, Connecting before and after the session, Connected, Failed) |
| Reconnect delay sequence and counter rules | `reconnectBackoffSequence` (delays and `retryAtMillis`), `attemptCounterResetsOnConnected`, `attemptCounterResetsOnDisconnect`, `cancelReconnectResetsAttemptCounter` (the pinning test [13](13-cancel-reconnect-resets-attempts.md) asked for), `manualConnectKeepsAttemptCounter` |
| Mute, deafen, PTT spam during connect and disconnect | `muteDeafenPttSpamDuringConnectAndDisconnectIsSafe`, pre-sync and connected, with server mute, deafen, and suppress interleaved |
| Route flaps, SCO failure, `RetrySco` | Unchanged: route-level tests in `RouteControlTest` (`scoFailedPublishesFallback`, `retryScoStartsLinkWhenRequestedAndIdle`, `backToBackStartThenEndSettlesIdle`); not a client concern |
| `Reject`, removal of self, trust failure → `Failed`, no reconnect | `rejectGoesFailedWithoutReconnect`, `removalOfSelfWhileConnectedGoesFailedWithoutReconnect`, `removalOfSelfInSyncBatchGoesFailedWithoutReconnect`, `trustFailureGoesFailedWithoutReconnect`; at session level `rejectFailsOnceAndStopsProcessing`, `selfRemoveFailsWithRemoved` |
| Forced TCP from `ServerSync`; UDP→tunnel and back; one empty `CryptSetup` per `CryptResyncRequested` | `TransportImplTest`: `forcedTcpTunnelsFromServerSyncAndNeverUsesUdp`, `udpGivesUpOnlyAfter20Seconds`, `udpGivesUpWhenOnlyLocalSideIsDead`, `switchesBackToUdpWhenBothSidesHaveMoreThanThreeGood`, `udpUnavailableSwitchesToTunnelOnce`, `cryptResyncRequestSendsExactlyOneEmptyCryptSetup`; `SessionTest.serverSyncStartsKeepaliveOnce` |
| Full outbound channel fails the session | `fullOutboundFailsTheTransport`, `fullOutboundFailsTheSession` (a real `Session` over a real `TransportImpl`) |

Also from fix direction 1: the keepalive cadence
(`keepaliveCadenceBootstrapThenSteady`), the `CryptSetup` decision table
and its protocol violations, inbound routing, an invalid frame, a server
hangup, and a quiet, final `close()`. From direction 2: the handshake
order, the restored self-mute sent at `ServerSync`, transport failures
passed through unchanged, tunnelled packets reaching voice in order, and
users kept in existing channels across `ChannelRemove`.

**Not pinned: scope cancellation closing the socket.** Direction 1 asked
for it, but it is [02](02-service-destroy-leaks-transport.md)'s open bug
and would fail today. `close()` is pinned instead; 02's fix adds the
scope-cancellation case to `TransportImplTest`.

**Shown to fail.** Each guard was broken on purpose in production code
and the suite rerun. These were caught: a `Failed → Reconnecting`
publish, route end before voice stop, `Session.close` not cancelling its
scope, `TransportImpl.close` not cancelling its scope, keepalive
restartable, an unguarded tunnel switch, give-up at exactly 20 s,
switch-back on one side's count, the session processing messages after a
failure, no keepalive at `ServerSync`, two `CryptSetup`s per resync
request, a full outbound channel ignored, `cancelReconnect` or `Connected`
not resetting the counter, the policy ignoring suppress, a move to an
unknown channel kept, disconnect without teardown, keys not pushed to
voice, self-removal not recognised, and the self-mute not restored. One
mutation survived as equivalent: dropping the network collector's
`Reconnecting` check, since every exit from `Reconnecting` cancels the
collector.

[22](22-keepalive-anchored-at-construction.md) and
[26](26-stats-two-collectors.md) stay open. The injected clock makes 22
directly testable in `TransportImplTest`.

## Related

- [05](05-client-edge-invariants-weak.md): the invariant harness.
- [02](02-service-destroy-leaks-transport.md): a defect these tests would
  catch.
- The step-1 harness validation and the murmur interop suite it left
  behind (resolved; see [open items](../open-items.md#resolved)).
