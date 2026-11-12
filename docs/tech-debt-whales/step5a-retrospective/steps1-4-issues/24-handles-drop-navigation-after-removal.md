# 24 — Removed handles drop navigation; the spec says they return cached values

| | |
|---|---|
| Category | Spec wording too broad (behavior matches the pre-migration model) |
| Severity | Benign |
| Verified | Yes, against the current tree and the pre-step-2 tree |
| Area | State / compatibility handles |
| Spec | [state.md § Compatibility handles](../../target-architecture/state.md#compatibility-handles-migration-only) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/state/Handles.kt` (`UserHandle.channel` around line 58; `ChannelHandle.parent`, `users`, `subchannels`, `links`, `subchannelUserCount` around lines 134-170); `libraries/humla/src/test/java/se/lublin/humla/state/HandlesTest.kt` (`testScalarsCachedAfterRemove`, `testRemovedUserKeepsScalars`) |
| Gates | Not gating; a one-sentence spec fix |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

The spec says a handle whose user or channel has left state returns its
cached last value, "as a departed `User` object does today", and that
navigation properties return handles. The code applies the cache to
scalar properties only: navigation (`channel`, `parent`, `users`,
`subchannels`, `links`, `subchannelUserCount`) reads live state and returns
`null`/empty/0 once the object is gone.

The audit lens flagged this as drift from "today". Checking the
pre-migration model shows the code is faithful to it and the spec sentence
is too broad.

## What the spec says

> - Each handle caches the last value it saw. When the user or channel is
>   gone from state, reads return that cached value, as a departed `User`
>   object does today.
> - Navigation properties return handles, not snapshots.

## What the code does

`Handles.kt`:

```kotlin
private fun live(): UserInfo? = state.value.server.users[sessionId]

override val channel: IChannel?
    get() {
        // Detached (removed) users read null, as today.
        val id = live()?.channelId ?: return null
        return ChannelHandle(id, state)
    }
```

Scalars use `current()` (live value or cached seed). `ChannelHandle`'s
navigation uses `live()` similarly. `HandlesTest` pins "scalars cached
after remove".

## Before the migration

At `ac38e205^`, `ModelHandler.messageUserRemove` did `user.channel = null`
before notifying observers, and `messageChannelRemove` set `u.channel =
null` for users in the removed subtree, cleared links (`node.clearLinks()`,
`linked.removeLink(node)`), and detached the subtree from its parent
(`parent.removeSubchannel(channel)`), "fully disconnecting" removed nodes.
So departed objects kept their scalars but **not** their navigation, which
is what the handles do now.

## Impact

None. The spec sentence would lead a reader (or an implementer of a
future replacement) to expect stale navigation from removed handles.

## Fix directions

Change the spec sentence to: "When the user or channel is gone from state,
scalar reads return the cached value; navigation reads (`channel`,
`parent`, `users`, `subchannels`, `links`) return null or empty, as a
departed `User`/`Channel` does today." Optionally add a `HandlesTest` case
pinning navigation after removal.

## Acceptance

Spec and code agree; optional test exists.
