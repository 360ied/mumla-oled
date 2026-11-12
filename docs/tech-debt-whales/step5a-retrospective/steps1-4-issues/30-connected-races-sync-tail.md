# 30 — `Connected` (and route/voice start) can run before the session finishes its `ServerSync` work

| | |
|---|---|
| Category | Spec drift (ordering) |
| Severity | Benign today (the racing step is currently a no-op in production) |
| Verified | Yes (code read) |
| Area | Client / session start sequence |
| Spec | [client.md § Start sequence](../../target-architecture/client.md#start-sequence) steps 6 and 7 |
| Code | `libraries/humla/src/main/java/se/lublin/humla/client/HumlaClient.kt` (`runAttempt`: `syncWatcher` collecting `session.state` on `clientDispatcher`, then `onSynced`, around lines 300-315); `client/Session.kt` (`ServerSync` branch of `handleMessage`, around lines 551-590) |
| Gates | Not gating; relevant once [14](14-local-facts-self-mute-stub.md) makes the session restore self-mute |

Line numbers below refer to `master` at `092d9dc8`.

## Summary

The spec orders start-up as: step 6, on `ServerSync` the session publishes
`SYNCED`, starts the transport's ping loop, and sends the restored
self-mute `UserState`; then step 7, the client publishes `Connected`,
sends `SessionStarted` to route, and starts voice. In the code, the client
learns about `SYNCED` from an independent collector (`syncWatcher`) on the
client dispatcher. The session publishes `SYNCED` inside its `update`, then
continues on its own dispatcher with event emission, observer fan-out,
`transport.startKeepalive()`, and the self-mute restore. Nothing makes
step 7 wait for the rest of step 6.

## What the spec says

> 6. On `ServerSync`: phase `SYNCED`, state published, the transport starts
>    its ping loop, and the session sends the restored self-mute
>    `UserState` if one is set.
> 7. The client publishes `Connected(session)`, sends `SessionStarted` to
>    `RouteControl`, and submits `VoiceCommand.Start` ...

## What the code does

`HumlaClient.runAttempt`:

```kotlin
val syncWatcher = attemptScope.launch(clientDispatcher) {
    session.state.collect { s ->
        if (s.server.phase == SyncPhase.SYNCED) {
            onSynced(target, session)      // publishes Connected, starts route + voice
            throw WatcherDone()
        }
    }
}
```

`Session.handleMessage`, `ServerSync` branch: `_state.update { ... synced
= true ... }` (publishes `SYNCED`), then `emitAll(result.events)`, observer
posts, then `transport.startKeepalive()`, then the restored self-mute
handling.

The two run on different dispatchers (`clientDispatcher` vs the session's
`workDispatcher`), so `onSynced` can run before `startKeepalive()` and
before the restored self-mute is enqueued.

## Impact

- Route start, voice start, and the app's `onConnected` can interleave with
  the session's sync tail. Harmless today because production never sends
  the restored self-mute `UserState` (see
  [14](14-local-facts-self-mute-stub.md)), and the keepalive starting a few
  milliseconds later does not matter.
- Once step 6 makes the session restore self-mute, voice could start before
  the restored mute is reflected in the desired state, depending on
  whether the restore updates `local` before or after `SYNCED` is
  published.

## Fix directions

Make the ordering explicit: either have the session complete a
`synced: CompletableDeferred<Unit>` (or emit an event) **after** its sync
tail, and have the client await that instead of collecting
`phase == SYNCED`; or apply the restored desired self-mute to `local`
inside the same `update` that publishes `SYNCED`, so any observer of
`SYNCED` sees it (the `UserState` send can still follow).

## Acceptance

A `Session`/client test with a fake transport shows `startKeepalive()` and
the restore happen before `Connected` is published (or the spec is updated
to say they may race and why that is safe). `./scripts/check.sh` passes.

## Related

- [14](14-local-facts-self-mute-stub.md),
  [02](02-service-destroy-leaks-transport.md)
