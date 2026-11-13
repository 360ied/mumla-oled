# 23 — `SYNCING` is entered only on `UserState`, not on `ChannelState`

| | |
|---|---|
| Status | Resolved 2026-11-13 (`5d322791`, `0e8b4c15`, merged in `5ac34784`): `applyChannelState` advances `HANDSHAKING` → `SYNCING`, and tests pin the transition for both message types. |
| Category | Spec drift |
| Severity | Benign (no consumer distinguishes `HANDSHAKING` from `SYNCING`) |
| Verified | Yes |
| Area | State transitions |
| Spec | [state.md § Sync phases and publication](../state.md#sync-phases-and-publication) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/state/UserTransitions.kt` (`applyUserState`, lines 57-59); `state/ChannelTransitions.kt` (`applyChannelState`, no phase write) |
| Gates | Not gating |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

The spec says the first `ChannelState` **or** `UserState` moves the phase
from `HANDSHAKING` to `SYNCING`. Only `applyUserState` does it. murmur
sends the whole channel tree before any user (Version, CodecVersion,
ChannelState..., UserState..., ServerSync), so on a real server the entire
channel dump is applied in `HANDSHAKING`, and `SYNCING` begins only at the
first `UserState`.

## What the spec says

> | `SYNCING` | First `ChannelState` or `UserState` arrives | Not published: the message coroutine applies transitions to a private value |

## What the code does

`UserTransitions.kt` lines 57-59:

```kotlin
// First channel/user message leaves HANDSHAKING for SYNCING.
if (server.phase == SyncPhase.HANDSHAKING) {
    server = server.copy(phase = SyncPhase.SYNCING)
}
```

The comment says "channel/user", but `ChannelTransitions.kt` has no
reference to `phase` at all.

## Impact

None today: values before `SYNCED` are not published, and every consumer
tests only `phase == SYNCED`. But the phase does not describe the channel
part of sync, and no test pins the transition.

## Fix directions

Add the same `HANDSHAKING → SYNCING` step at the start of
`applyChannelState` (and of `applyChannelRemove` if channel removal can
arrive before any user), plus a transition test: `ChannelState` on a
`HANDSHAKING` state yields `SYNCING`.

## Acceptance

Test exists; `./scripts/check.sh` passes.
