# 13 — `cancelReconnect` resets the attempt counter, contrary to the spec's rule

| | |
|---|---|
| Category | Spec/behavior mismatch (behavior preserved from before the migration) |
| Severity | Benign as a regression question (none); the spec's rule and the code disagree |
| Verified | Yes, against the current tree and the pre-step-3 tree |
| Area | Client / reconnect policy |
| Spec | [client.md § Reconnect policy](../../target-architecture/client.md#reconnect-policy) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/client/HumlaClient.kt` (`cancelReconnect`); caller `app/src/main/java/se/lublin/mumla/app/MumlaActivity.kt` (around lines 245 and 1519) |
| Gates | Not gating |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

The spec lists exactly two events that reset the reconnect attempt counter
(reaching `Connected`, and user disconnect) and says a manual connect does
not reset it. `HumlaClient.cancelReconnect()` also resets it. The legacy
code did the same, so this is a spec inaccuracy, not a regression. But the
app's "cancel and connect now" flow goes through exactly this path, so the
spec's stated rationale does not hold in practice.

## What the spec says

`client.md` § Reconnect policy:

> Attempt counter: incremented by each automatic attempt; reset when a
> session reaches `Connected` and when the user disconnects. A manual
> `connect` does *not* reset it: if a manual retry fails, the server is
> still down, so the next automatic attempt keeps the grown delay.

The `ClientState` diagram has `Reconnecting → Idle: disconnect or
cancelReconnect`.

## What the code does

`HumlaClient.cancelReconnect()` (around line 117):

```kotlin
fun cancelReconnect() {
    scope.launch(clientDispatcher) {
        val s = _state.value
        if (s is ClientState.Reconnecting) {
            cancelJobs()
            attempt = 0
            connectEpoch += 1
            _state.value = ClientState.Idle
        }
    }
}
```

The comment above it says "Matches legacy parity".

`MumlaActivity.kt` calls `svc.cancelReconnect()` immediately before
connecting to a pending server (around line 245), and again around line
1519, so a user who taps connect while a backoff is armed gets a fresh
attempt counter.

## Before the migration

Pre-step-3 `HumlaService.cancelReconnect()` (at `5ff29253^`, around line
1407) began with `mReconnectAttempts.set(0)`. Behavior is unchanged.

## Impact

After "cancel and connect now", if the server is still down, the next
automatic retry waits the shortest delay instead of the grown one
(delay table in `client.md`), and `getReconnectAttempts()` reports 0. That
is arguably the right user-facing behavior (the user explicitly asked to
retry now), but it contradicts the spec's sentence and rationale.

## Fix directions

Decide which is intended and make spec and code agree:

1. **Spec follows code (recommended, preserves behavior):** add
   "`cancelReconnect`" to the counter-reset list in `client.md`, and note
   that the app's explicit connect goes through `cancelReconnect` first.
2. **Code follows spec:** stop resetting `attempt` in `cancelReconnect`, and
   check the app flow still behaves acceptably.

Add a `HumlaClientTest` case pinning whichever rule is chosen (there is
currently no `cancelReconnect` test at all; see
[04](04-session-transport-virtual-time-gaps.md)).

## Acceptance

`client.md` and `HumlaClient` agree; a test pins the counter after
`cancelReconnect`; `./scripts/check.sh` passes.
