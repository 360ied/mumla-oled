# 05 — Per-step invariants are not asserted; the edge check is looser than the spec

| | |
|---|---|
| Status | Resolved 2026-11-14 (`26b6c8c4`, `02e68f97`): one checker asserts all five invariants after every step (the policy check is limited; see [34](34-published-policy-not-an-invariant.md)); edges are exactly the diagram's. See [Resolution](#resolution). |
| Category | Verification gap |
| Severity | Harmful (the invariants the spec relies on are mostly unchecked) |
| Verified | Yes |
| Area | Client tests |
| Spec | [testing.md § Virtual-time tests](../testing.md#virtual-time-tests) (invariants), [client.md § ClientState](../client.md#clientstate) (edge diagram) |
| Code | `libraries/humla/src/test/java/se/lublin/humla/client/HumlaClientTest.kt` (`allowedEdges`, `assertEdges`) |
| Gates | Gated step 5a.3b with 04; gate met |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

The spec requires five invariants to be asserted **after every step of
every scenario**. There is no shared invariant harness. Only the
`ClientState` edge invariant has a helper, it is called from 2 of the 10
client tests, and its allowed-edge set contains an edge the spec's diagram
does not have.

## What the spec says

`testing.md` § Virtual-time tests:

> Invariants asserted after every step of every scenario:
> - at most one live session; nothing runs in a cancelled session's scope;
> - `VoiceCommand.Stop` is submitted before `RouteCommand.SessionEnded`;
> - no `VoicePolicy` allows transmit while `effectiveSelfMute` is true or
>   the user is server-muted, deafened, or suppressed;
> - every user's `channelId` refers to an existing channel, except
>   transiently after `ChannelRemove`;
> - `ClientState` transitions only along the edges in
>   client.md § ClientState.

`client.md` § ClientState diagram edges:

```
Idle → Connecting            (connect)
Connecting → Connected       (ServerSync)
Connecting → Reconnecting    (Connection error)
Connected → Reconnecting     (Connection error)
Reconnecting → Connecting    (backoff elapsed or network available)
Connecting → Failed          (Rejected, Removed, Trust)
Connected → Failed           (Rejected, Removed)
Failed → Connecting          (connect)
Connecting → Idle            (disconnect)
Connected → Idle             (disconnect)
Reconnecting → Idle          (disconnect or cancelReconnect)
Failed → Idle                (disconnect)
Connected → Connecting       (connect, other target)
```

## What the code does

`HumlaClientTest.allowedEdges()` (around line 290) returns the thirteen
spec edges **plus** `"Connecting" to "Connecting"`. `assertEdges`
(line 314) also `continue`s whenever `from == to`, so same-state
"transitions" are skipped regardless.

`assertEdges` is called at line 414 (`connectSyncDisconnect`) and line 475
(`reconnectBackoffSequence`) only. The other eight tests do not collect
the state stream or check edges.

The other four invariants:

| Invariant | Where checked |
|---|---|
| At most one live session; nothing runs in a cancelled session's scope | Once, in `connectWhileConnectedKeepsOneSession` |
| `Stop` before `SessionEnded` | Once (fake lifecycle ordering in one test) |
| No `VoicePolicy` allows transmit while muted/deafened/suppressed | Not in client tests (`VoicePolicyTest`/`PolicyCollectorTest` test the policy function itself, not as an invariant across scenarios) |
| Every user's `channelId` refers to an existing channel | Not as an invariant (transition tests check specific cases) |

## Is `Connecting → Connecting` legitimate?

Possibly. `HumlaClient` republishes `ClientState.Connecting(target, attempt)`
after creating the session (`HumlaClient.kt` around line 299) and when a
new attempt starts while connecting. Republishing an equal value is a
no-op on a `StateFlow`; a value with a different `attempt` or `target` is
a real transition the diagram does not list. Either the diagram should
gain the self-edge (with its trigger) or the client should not emit it;
the test should match whichever is decided, without blanket-skipping
`from == to`.

## Impact

An illegal edge such as `Failed → Reconnecting`, or an orphaned session
publishing state after cancellation, would pass in every test except the
two that collect edges, and the self-edge skip hides re-entry bugs even
there. The spec's argument that the client "cannot get lifecycle wrong
without visibly breaking" rests on these invariants being checked.

## Fix directions

1. Write one invariant checker (a function or a test rule) that, given the
   fake transport, fake voice/route lifecycles, and the client, asserts all
   five invariants. Call it after every `runCurrent()`/`advanceTimeBy()`
   step in every client and session scenario (a small wrapper around the
   scheduler step makes this mechanical).
2. Make the edge set exactly the diagram's. Decide on `Connecting →
   Connecting`: if kept, add it to `client.md`'s diagram with its trigger
   (on `master` first, per the ground rules); if not, fix the emitter.
   Remove the `from == to` skip or restrict it to equal values.
3. For "nothing runs in a cancelled session's scope", have the fake
   transport record calls and assert none arrive from a session after its
   `close()`/cancel.

## Acceptance

- Every `HumlaClientTest` scenario (and the new `Session` scenarios from
  [04](04-session-transport-virtual-time-gaps.md)) runs the invariant
  checker after each step.
- Temporarily adding an illegal transition in `HumlaClient` (for example,
  publishing `Reconnecting` from `Failed`) makes the suite fail.
- `./scripts/check.sh` passes.

## Resolution

`InvariantChecker` and `ClientHarness` in
`libraries/humla/src/test/java/se/lublin/humla/client/ClientTestSupport.kt`.
Unconfined collectors record every `ClientState` and every state each
session publishes, so values landing between two steps are still seen.
`ClientHarness.step()` and `advance()` wrap `runCurrent()` and
`advanceTimeBy()` and run the check after each; every `HumlaClientTest`
and `SessionTest` scenario steps through them.

| Invariant | Detector |
|---|---|
| At most one live session; nothing runs in a cancelled session's scope | Every live session (scope job active) is the client's current one, so a session the client let go of must be cancelled even before a successor exists; a cancelled scope has no unfinished children |
| `Stop` before `SessionEnded` | Every `route-end` in the shared lifecycle log follows its own `voice-stop` |
| No `VoicePolicy` allows transmit while muted, deafened, or suppressed | `voicePolicy(state, VoiceSettings.initial())` on every published state, against `effectiveSelfMute` and the server's mute, deafen, and suppress flags |
| Every user's `channelId` refers to an existing channel | On every published state; the one tolerated gap is the null `channelId` that [state](../state.md#server-message-transitions) gives occupants of a removed channel until the server moves them |
| `ClientState` edges | Every consecutive pair of recorded values, against exactly the diagram's edges; no same-state skip |

**`Connecting → Connecting` is legitimate.** testing.md's own scenario
(connect to another target while connecting) makes it: the client
cancels the attempt and publishes `Connecting` for the new target. The
diagram gained the self-edge, and connect as a trigger of
`Reconnecting → Connecting` (`26b6c8c4`). A same-server retry publishes
an equal value, which `StateFlow` does not emit, so no skip is needed;
`connectToOtherTargetWhileConnecting` asserts that the edge occurs.

The checker has its own tests (`invariantCheckerRejectsEdgeOutsideDiagram`,
`invariantCheckerRejectsRouteEndBeforeVoiceStop`,
`invariantCheckerRejectsTwoLiveSessions`). Publishing `Reconnecting` from
`Failed` in `HumlaClient` fails four scenarios. Swapping the teardown
order, or leaving `Session.close` without its scope cancel, fails most of
the suite (see [04](04-session-transport-virtual-time-gaps.md#resolution)).

**Limits.** Two detectors are weaker than the table suggests:

- The policy check recomputes `voicePolicy()` on each published state and
  compares it against the flags `voicePolicy()` itself reads, so it only
  catches a mutation inside that function. The policy `PolicyCollector`
  publishes, which is what the voice layer reads, is not observed. That
  is [34](34-published-policy-not-an-invariant.md), which gates 5a.3b.
- Fix direction 3 (the fake transport rejecting calls from a closed
  session) was not done. The scope-children check stands in for it: it
  covers coroutines in the session scope, not intents a caller makes on a
  dead session. Those still reach `FakeTransport.send` unflagged; in
  production `TransportImpl.send` drops them once closed.

Only the edge, stop-before-end, and live-session detectors have
self-tests. The channel detector was shown to fire by making
`applyChannelRemove` keep occupants in the removed channel; the policy
detector can only fire on a mutation inside `voicePolicy()`, as above.

## Related

- [04](04-session-transport-virtual-time-gaps.md)
- The `connectEpoch` open item in
  [open-items.md](../open-items.md): the invariant
  checker is what would make removing `connectEpoch` safe.
