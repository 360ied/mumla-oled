# 20 — The 64-message batch drain still publishes once per message

| | |
|---|---|
| Status | Open — needs a decision: implement publish-once, or correct the spec's cost model. |
| Category | Spec drift (performance mechanism missing) |
| Severity | Benign (cost only; no incorrect state) |
| Verified | Yes (code read) |
| Area | Session / state publication |
| Spec | [state.md § Sync phases and publication](../state.md#sync-phases-and-publication) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/client/Session.kt` (`runMessages` around lines 297-340, `handleMessage` from line 358); `HumlaService.kt` (session-state mirror collector) |
| Gates | Not gating |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

After `SYNCED`, the spec has the message coroutine drain up to 64 queued
messages, apply them in order, and publish **once** per batch. The code
does drain up to 64 messages into a batch, but then calls `handleMessage`
for each, and every branch of `handleMessage` does its own
`_state.update { ... }`. A burst of N messages produces N publications.
The batching therefore has no effect on publication.

## What the spec says

> After `SYNCED`, the message coroutine takes whatever is already queued
> ... up to 64 messages, applies them in order, and publishes once ... A
> burst therefore costs one publication, while a lone message is published
> immediately.

## What the code does

`Session.runMessages` (around line 315):

```kotlin
for (first in transport.inbound) {
    val batch = ArrayList<Inbound>(64)
    batch.add(first)
    while (batch.size < 64) {
        val next = transport.inbound.tryReceive().getOrNull() ?: break
        batch.add(next)
    }
    for (item in batch) {
        when (item) {
            is Inbound.Tunnelled -> voiceLink.queueTunnelled(item.packet)
            is Inbound.Message -> handleMessage(item.type, item.message)
        }
        if (failureDeferred.isCompleted) return
    }
}
```

`handleMessage` (line 358 onward) calls `_state.update { current ->
applyX(current, msg).state }` in each message branch (lines 365, 373, 391,
416, 462, 517, 530, ...). Only the pre-sync private value is coalesced.

`HumlaService` mirrors each published `SessionState` into its own flow, so
each publication also costs a mirror publication and observer fan-out.

## Before the migration

`ModelHandler` mutated the model per message and notified observers per
message; there was no publication concept. No regression.

## Impact

Up to 64 `SessionState` values (each with a fresh lazily built
`ChannelTree`) and 64 mirror publications per burst, the cost the
batching was designed to avoid. `StateFlow` conflation hides intermediate
values from slow collectors but not the allocation. No correctness
impact.

## Fix directions

Apply a batch's transitions to a local value and publish once:
`_state.update { current -> batch.fold(current) { s, item -> apply(s, item) } }`,
collecting events and side effects (observer posts, `startKeepalive`,
outbound messages) during the fold and running them **after** the update
returns, so the lambda stays pure (see
[06](06-update-conventions-not-enforced.md)). This requires splitting
`handleMessage` into a pure transition part and an effects part, which is
the bigger change; the alternative is to correct the spec's cost model.

## Acceptance

A test feeding a 64-message burst observes one publication (count
emissions with an unconflated collector, or count `update` calls through a
seam). `./scripts/check.sh` passes.
