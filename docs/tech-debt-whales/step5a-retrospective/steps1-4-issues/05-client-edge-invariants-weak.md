# 05 — Per-step invariants are not asserted; the edge check is looser than the spec

| | |
|---|---|
| Category | Verification gap |
| Severity | Harmful (the invariants the spec relies on are mostly unchecked) |
| Verified | Yes |
| Area | Client tests |
| Spec | [testing.md § Virtual-time tests](../../target-architecture/testing.md#virtual-time-tests) (invariants), [client.md § ClientState](../../target-architecture/client.md#clientstate) (edge diagram) |
| Code | `libraries/humla/src/test/java/se/lublin/humla/client/HumlaClientTest.kt` (`allowedEdges`, `assertEdges`) |
| Gates | Should be fixed together with [04](04-session-transport-virtual-time-gaps.md) |

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

## Related

- [04](04-session-transport-virtual-time-gaps.md)
- The `connectEpoch` open item in
  [open-items.md](../../target-architecture/open-items.md): the invariant
  checker is what would make removing `connectEpoch` safe.
