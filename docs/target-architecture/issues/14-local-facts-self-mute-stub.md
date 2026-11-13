# 14 — Persisted self-mute/deafen never reaches the session (`LocalFactsStore` stub)

| | |
|---|---|
| Status | Documented as an interim in `migration.md` (ground rules, "Other recorded interims"). The step-6 fix remains. |
| Category | Undocumented interim (behavior preserved from before the migration) |
| Severity | Moderate: not a regression, but a spec mechanism that looks wired is dead in production, and a trap for steps 6/7 |
| Verified | Yes |
| Area | Client / state / app |
| Spec | [client.md § ConnectTarget](../client.md#connecttarget) (`LocalFacts`), [client.md § Start sequence](../client.md#start-sequence) steps 1 and 6, [state.md § Self-mute and deafen](../state.md#self-mute-and-deafen), [app.md](../app.md) (settings ownership) |
| Code | `libraries/humla/src/main/java/se/lublin/humla/HumlaService.kt` (anonymous `localFactsStore`, around line 527); `client/HumlaClient.kt` (`runAttempt`); `client/Session.kt` (`initialState`, `setSelfMuteDeaf`); `app/src/main/java/se/lublin/mumla/service/MumlaService.kt` (`onConnectionSynchronized`, around line 792; `onUserStateUpdated`) |
| Gates | Must be resolved by step 6 (real `LocalFactsStore`), and before step 7 removes the app's post-sync push |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

The spec moves persisting and restoring self-mute/deafen into the session:
the client loads `LocalFacts` (including persisted self-mute/deafen) before
creating the session, and the session sends the restored `UserState` right
after `ServerSync` and writes every change back. The only production
`LocalFactsStore` hardcodes self-mute/deafen to unset and discards writes.
Restoring and persisting are still done by the app (`MumlaService`), as
before the migration. The session's restore and persist paths are
exercised only by tests (`InMemoryLocalFactsStore`).

## What the spec says

`client.md` § ConnectTarget: local mute/ignore history and the persisted
self-mute state "are not in the target; the session loads them from
`LocalFactsStore` when it starts, so a reconnect always uses current
values". `LocalFacts` has `selfMuted: Boolean // persisted settings, as
today`.

`client.md` § Start sequence:

> 1. The client loads `LocalFacts` (local mute/ignore lists for this
>    server, persisted self-mute/deafen) from `LocalFactsStore` on the I/O
>    dispatcher. ...
> 6. On `ServerSync`: phase `SYNCED`, state published, the transport starts
>    its ping loop, and the session sends the restored self-mute
>    `UserState` if one is set.

`state.md` § Self-mute and deafen: "A new session starts with
`selfMuteDesired`/`selfDeafDesired` from settings when either is set, and
sends the `UserState` right after `ServerSync` ... Every change is written
back to settings off the main thread".

`app.md`: "Persisting and restoring self-mute/deafen moves into the session
and `LocalFactsStore` ... it is no longer a `MumlaService` job."

The migration ground rules' interim list names `LocalFactsStore` among the
objects `HumlaService` constructs until step 6, but says nothing about its
self-mute half being a stub.

## What the code does

`HumlaService.kt` (around line 527):

```kotlin
val localFactsStore = object : LocalFactsStore {
    override suspend fun load(serverId: Long): LocalFacts {
        val pending = pendingConnection.get()
        return LocalFacts(
            mutedUserIds = (pending.localMuteHistory ?: emptyList()).toSet()...,
            ignoredUserIds = (pending.localIgnoreHistory ?: emptyList()).toSet()...,
            selfMuted = false,
            selfDeafened = false,
        )
    }
    override suspend fun setLocalMute(serverId: Long, userId: Int, muted: Boolean) { ... }      // implemented
    override suspend fun setLocalIgnore(serverId: Long, userId: Int, ignored: Boolean) { ... }  // implemented
    override suspend fun setSelfMuteDeaf(muted: Boolean, deafened: Boolean) = Unit             // writes dropped
}
```

The local mute/ignore lists are real (from the pending connection); the
self-mute/deafen half is hardcoded off and its writes are no-ops.

Restore today: `MumlaService.onConnectionSynchronized` (around line 792)
calls `setSelfMuteDeafState(settings.isMuted, settings.isDeafened)` after
sync. Persist today: `MumlaService.onUserStateUpdated` →
`settings.setMutedAndDeafened(...)`.

## Before the migration

Restore and persist were `MumlaService` jobs, done the same way. No
behavior changed; the new mechanism was built but not connected.

## Impact

- **Today:** none beyond pre-migration behavior. Between `ServerSync` and
  the app's post-sync call, the session's desired self-mute is unset, so
  `effectiveSelfMute` follows only the server echo; for a user whose
  persisted setting is "muted", the transmit gate can be open for that
  short window. This window also existed before the migration.
- **Trap:** the session-side restore looks implemented and is tested, so a
  later step could remove the app's post-sync push (the spec says to)
  before a real store exists, silently losing self-mute restore on
  reconnect. Conversely, once a real store lands (step 6), the app's push
  becomes a second restore: duplicate `UserState`, duplicate
  `SelfMuteStateChanged` event and chat line.

## Fix directions

1. **Document now:** add this to the interim list in `migration.md`
   ("until step 6, the self-mute half of `LocalFactsStore` is a stub;
   restore and persist stay in `MumlaService`"), or as an open item.
2. **Step 6:** implement the self-mute half against settings (load
   `settings.isMuted`/`isDeafened`; write back off the main thread), and in
   the **same change** remove `MumlaService`'s post-sync restore and the
   `onUserStateUpdated` persist, so there is exactly one owner at all
   times.
3. Add a test with the production store wiring (or a store backed by a fake
   settings object) proving restore happens exactly once after
   `ServerSync`.

## Acceptance

At any commit, exactly one component restores and persists self-mute/
deafen, and the spec says which. `./scripts/check.sh` passes.

## Related

- [30](30-connected-races-sync-tail.md): the session's restore runs after
  `Connected` may already have been published.
- [28](28-local-facts-load-failure-silent.md)
